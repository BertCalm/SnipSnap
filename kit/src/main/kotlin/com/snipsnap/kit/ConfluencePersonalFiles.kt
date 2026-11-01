package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** The small, explicit source dependency a personal-voice pad carries beside its rendered WAV. */
object ConfluencePersonalFiles {
    const val DIRECTORY = ".confluence-personal"
    const val SOURCE_ID_KEY = "personalVoiceId"
    const val MAX_RAW_BYTES = 24L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 4L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 128L * 1024
    const val MAX_VOICES = 16
    const val MAX_KIT_BYTES = 128L * 1024 * 1024
    const val KIT_METADATA_RESERVE_BYTES = 2L * 1024 * 1024
    const val MAX_VOICE_BYTES = MAX_RAW_BYTES + MAX_SOURCE_BYTES + MAX_MANIFEST_BYTES
    internal val ID = Regex("[0-9a-f]{32}")

    /**
     * Admission before a voice is copied or a pad is assigned. This performs no writes and reads
     * only safe file lengths for existing references; the source loader owns their full analysis.
     * Existing immutable IDs are deduplicated, so a second pad using one voice spends no new slot.
     */
    fun requireCapacity(kit: Kit, kitDir: File, newVoiceId: String, newVoiceBytes: Long) {
        require(ID.matches(newVoiceId)) { "invalid incoming personal voice ID" }
        require(newVoiceBytes in 1..MAX_VOICE_BYTES) { "incoming personal voice exceeds its bounded source size" }
        val existing = referencedIds(kit)
        require((existing + newVoiceId).distinct().size <= MAX_VOICES) { "personal voice capacity exceeds $MAX_VOICES distinct voices" }
        val currentBytes = existing.sumOf { id -> safeVoiceLengths(kitDir, id) }
        val additional = if (newVoiceId in existing) 0L else newVoiceBytes
        require(currentBytes <= MAX_KIT_BYTES - KIT_METADATA_RESERVE_BYTES - additional) { "personal voice capacity exceeds the native backup byte budget including kit metadata reserve" }
    }

    /** Refuse missing/corrupt personal dependencies before the caller creates a child kit. */
    fun requireImportable(kit: Kit, sourceDir: File) = ConfluencePersonalTransfer.requireTogether(listOf(kit to sourceDir))

    /** Copy only referenced verified immutable voices, refusing same-ID conflicts or over-capacity. */
    fun copyReferenced(kit: Kit, sourceDir: File, targetDir: File) = ConfluencePersonalTransfer.copyReferenced(kit, sourceDir, targetDir)

    private fun safeVoiceLengths(root: File, id: String): Long = listOf("voice.json", "raw.wav", "source.wav").sumOf { name ->
        val path = "$DIRECTORY/$id/$name"
        val file = child(root, path)
        val minimum = if (name == "voice.json") 1L else 44L
        require(file.isFile && file.length() in minimum..limit(path)) { "missing or oversized existing personal voice file: $path" }
        file.length()
    }

    internal fun referencedIds(kit: Kit): List<String> = kit.pads.mapNotNull { it.source[SOURCE_ID_KEY] }
        .distinct().sorted().also { ids ->
            require(ids.size <= MAX_VOICES) { "personal voice backup exceeds $MAX_VOICES referenced voices" }
            require(ids.all { ID.matches(it) }) { "invalid personal voice ID in pad source metadata" }
        }

    internal fun allowedPath(path: String): Boolean = path == KitStore.FILE_NAME ||
        Regex("\\.confluence-personal/[0-9a-f]{32}/(voice\\.json|raw\\.wav|source\\.wav)").matches(path)

    internal fun limit(path: String): Long = when (path.substringAfterLast('/')) {
        "voice.json" -> MAX_MANIFEST_BYTES
        "raw.wav" -> MAX_RAW_BYTES
        "source.wav" -> MAX_SOURCE_BYTES
        KitStore.FILE_NAME -> KIT_METADATA_RESERVE_BYTES
        else -> throw IllegalArgumentException("unknown personal voice backup path $path")
    }

    /** Reject links before following them, including a link used as either parent directory. */
    internal fun child(root: File, path: String): File {
        require(allowedPath(path)) { "unsafe personal voice backup path: $path" }
        require(!Files.isSymbolicLink(root.toPath())) { "personal voice backup cannot follow a symbolic link" }
        var file = root
        for (part in path.split('/')) {
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "personal voice backup cannot follow a symbolic link: $path" }
        }
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "personal voice path escapes its kit" }
        return file
    }

    internal fun files(kit: Kit, root: File): List<Pair<String, File>> {
        val ids = referencedIds(kit)
        val files = ids.flatMap { id ->
            val identity = validateVoice(root, id)
            for (pad in kit.pads.filter { it.source[SOURCE_ID_KEY] == id && it.source["engine"] == "CONFLUENCE_PERSONAL" }) {
                require(pad.source["personalVoiceManifestSha256"] == identity.manifestHash &&
                    pad.source["personalSourceSha256"] == identity.pcmHash &&
                    pad.source["personalSourceFileSha256"] == identity.sourceFileHash &&
                    pad.source["sourceHashes"] == identity.pcmHash) { "personal voice source binding differs at pad ${pad.slot}" }
            }
            listOf("voice.json", "raw.wav", "source.wav").map { name ->
                val path = "$DIRECTORY/$id/$name"
                path to child(root, path)
            }
        }
        require(files.sumOf { it.second.length() } <= MAX_KIT_BYTES) { "personal voice source closure exceeds the per-kit byte budget" }
        return files
    }

    /** File integrity and bounded WAV shape; the synth/library layer interprets analysis and pitch. */
    internal data class Identity(val manifestHash: String, val sourceFileHash: String, val pcmHash: String)

    internal fun validateVoice(root: File, id: String): Identity {
        try { return validateManifest(root, id) }
        catch (e: JsonException) { throw IllegalArgumentException("unreadable personal voice manifest $id: ${e.message}", e) }
        catch (e: NoSuchElementException) { throw IllegalArgumentException("incomplete personal voice manifest $id: ${e.message}", e) }
    }

    private fun validateManifest(root: File, id: String): Identity {
        require(ID.matches(id)) { "invalid personal voice ID" }
        val prefix = "$DIRECTORY/$id"
        val manifest = child(root, "$prefix/voice.json")
        require(manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES) { "missing or oversized personal voice manifest $id" }
        val o = Json.parse(manifest.readText(Charsets.UTF_8)).obj()
        require(o.getValue("format").str() == "snipsnap-confluence-personal-voice" && o.getValue("schema").int() == 1) {
            "unsupported personal voice manifest $id"
        }
        require(o.getValue("id").str() == id) { "personal voice manifest ID differs from its directory" }
        for ((key, name, bytes) in listOf(Triple("raw", "raw.wav", MAX_RAW_BYTES), Triple("source", "source.wav", MAX_SOURCE_BYTES))) {
            val description = o.getValue(key).obj()
            require(description.getValue("path").str() == name) { "personal voice $key path must be $name" }
            val file = child(root, "$prefix/$name")
            require(file.isFile && file.length() in 44..bytes) { "missing or oversized personal voice $name" }
            val hash = description.getValue("fileSha256").str()
            require(hash.matches(Regex("[0-9a-f]{64}")) && XpnSourceMetadata.sha256(file) == hash) { "personal voice $name checksum differs" }
            val shape = shape(file)
            require(shape.frames == description.getValue("frames").long() && shape.rate == description.getValue("sampleRate").int() &&
                shape.channels == description.getValue("channels").int() && shape.bits == description.getValue("bitDepth").int() &&
                file.length() == description.getValue("bytes").long()) { "personal voice $name WAV shape differs from its manifest" }
            if (key == "source") {
                require(description.getValue("encoding").str() == "PCM_24" && shape.format == 1 && shape.bits == 24 &&
                    shape.rate == WavWriter.MPC_SAMPLE_RATE && shape.channels == 1 &&
                    shape.frames in (WavWriter.MPC_SAMPLE_RATE / 4).toLong()..(WavWriter.MPC_SAMPLE_RATE * 30).toLong()) { "personal voice canonical source must be mono 44.1 kHz PCM24, .25..30 seconds" }
                require(description.getValue("pcmHash").str().matches(Regex("[0-9a-f]{64}"))) { "invalid personal voice canonical PCM identity" }
                require(description.getValue("pcmHash").str() == canonicalHash(file)) { "personal voice canonical PCM identity differs from its source WAV" }
            } else {
                require(description.getValue("encoding").str() == "IEEE_FLOAT_32" && shape.format == 3 && shape.bits == 32 &&
                    shape.rate in 8_000..384_000 && shape.channels in 1..2 && shape.frames > 0) { "personal voice raw source must be a bounded float WAV" }
            }
        }
        val source = o.getValue("source").obj()
        val pcmHash = source.getValue("pcmHash").str()
        require(o.getValue("region").obj().getValue("assetHash").str() == pcmHash) { "personal voice region identity differs from its canonical source" }
        return Identity(XpnSourceMetadata.sha256(manifest), source.getValue("fileSha256").str(), pcmHash)
    }

    private data class Shape(val rate: Int, val channels: Int, val bits: Int, val frames: Long, val format: Int)

    /** The same canonical rate/length/little-endian float identity carried by source-based recipes. */
    private fun canonicalHash(file: File): String {
        val snip = WavReader.read(file.readBytes(), maxFrames = WavWriter.MPC_SAMPLE_RATE * 30)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(snip.sampleRate).putInt(snip.frameCount).array())
        val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in snip.samples) {
            if (buffer.remaining() < 4) { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putFloat(sample)
        }
        digest.update(buffer.array(), 0, buffer.position())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun shape(file: File): Shape = RandomAccessFile(file, "r").use { input ->
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun ushort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        require(tag() == "RIFF") { "personal voice file is not RIFF" }
        uint()
        require(tag() == "WAVE") { "personal voice file is not WAV" }
        var format = 0; var channels = 0; var rate = 0; var bits = 0; var data = -1L
        while (input.filePointer + 8 <= input.length()) {
            val chunk = tag(); val size = uint(); val start = input.filePointer
            require(size <= input.length() - start) { "truncated personal voice WAV chunk" }
            when (chunk) {
                "fmt " -> {
                    require(size >= 16 && format == 0) { "invalid personal voice WAV format" }
                    format = ushort(); channels = ushort(); rate = uint().toInt(); uint(); ushort(); bits = ushort()
                }
                "data" -> { require(data == -1L) { "duplicate personal voice WAV data" }; data = size }
            }
            input.seek(start + size + (size and 1L))
        }
        require(format > 0 && channels in 1..2 && bits in listOf(24, 32) && data >= 0) { "invalid personal voice WAV header" }
        val stride = channels * (bits / 8)
        require(data % stride == 0L) { "partial frame in personal voice WAV" }
        Shape(rate, channels, bits, data / stride, format)
    }
}
