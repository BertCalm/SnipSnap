package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.synth.ConfluencePersonalVoice
import com.snipsnap.synth.ConfluenceRegion
import com.snipsnap.synth.ConfluenceSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Immutable, local personal recordings and their qualified selected source. The untreated take is
 * retained as float WAV before any cleanup, leveling, fading, or doctor's treatment. A separate
 * PCM24 source is qualified only after writing and decoding the exact bytes that future renders use.
 * Personal ownership metadata is deliberately separate from the curated factory bank's CC BY grant.
 */
object ConfluencePersonalLibrary {
    const val DIR = "confluence-personal-voices"
    const val KIT_DIR = ".confluence-personal"
    const val FORMAT = "snipsnap-confluence-personal-voice"
    const val SCHEMA = 1
    const val PREPARATION_VERSION = 1
    const val MANIFEST_FILE = "voice.json"
    const val RAW_FILE = "raw.wav"
    const val SOURCE_FILE = "source.wav"
    const val MAX_RAW_BYTES = 24L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 4L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 128L * 1024
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_LIBRARY_ENTRIES = 128
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val MAX_NAME_CHARS = 80
    private val ID = Regex("[0-9a-f]{32}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val FILES = setOf(MANIFEST_FILE, RAW_FILE, SOURCE_FILE)

    enum class Origin { RECORDING, IMPORT }

    /** Lightweight shelf rows; opening one independently validates its audio and measured root. */
    data class Summary(
        val id: String,
        val name: String,
        val origin: Origin,
        val referenceMidi: Float,
        val rootMidi: Int,
        val seconds: Float,
        val createdAtMillis: Long,
    )

    class StoredVoice internal constructor(
        val summary: Summary,
        val directory: File,
        val manifestText: String,
        val selectionStartFrame: Int,
        val selectionEndFrame: Int,
        val assessment: ConfluencePersonalVoice.Assessment,
        val prepared: ConfluencePersonalVoice.PreparedVoice,
        val source: ConfluenceSource,
        val region: ConfluenceRegion,
        private val audio: Snip,
    ) {
        val id: String get() = summary.id
        val name: String get() = summary.name
        val origin: Origin get() = summary.origin
        val referenceMidi: Float get() = summary.referenceMidi
        val rootMidi: Int get() = summary.rootMidi
        val seconds: Float get() = summary.seconds
        val preparationVersion: Int get() = PREPARATION_VERSION
        val manifestFile: File get() = File(directory, MANIFEST_FILE)
        val rawFile: File get() = File(directory, RAW_FILE)
        val sourceFile: File get() = File(directory, SOURCE_FILE)
        val manifestSha256: String get() = sha256(manifestText.toByteArray(Charsets.UTF_8))
        val sourceFileSha256: String get() = sha256(sourceFile)
        val sourceSnip: Snip get() = audio.copy(samples = audio.samples.copyOf())

        /** Decode the preserved take anew, checking its persisted binding before exposing a copy. */
        val rawSnip: Snip get() {
            val metadata = parseManifest(manifestText, id)
            return readRaw(checkedFile(directory, RAW_FILE), metadata.getValue("raw").obj())
        }

        /** The unlevelled take at the frame rate used by selectionStart/EndFrame. */
        val canonicalRawSnip: Snip get() = canonical(rawSnip)
    }

    /**
     * Selection coordinates refer to the untreated take after mono/44.1 kHz format conversion.
     * No input is normalized, faded, silence-trimmed, or repaired. [referenceMidi] is an optional
     * confirmation of the independently measured selected-source pitch, never a manual override.
     */
    fun save(
        libraryDir: File,
        name: String,
        take: Snip,
        selectionStartFrame: Int,
        selectionEndFrame: Int,
        referenceMidi: Float? = null,
        origin: Origin = Origin.RECORDING,
    ): StoredVoice {
        val title = checkedName(name)
        validateTake(take) // Bound the caller's buffer before making our immutable snapshot.
        val untouched = take.copy(samples = take.samples.copyOf())
        validateTake(untouched)
        val converted = canonical(untouched)
        validateSelection(converted, selectionStartFrame, selectionEndFrame)
        checkSelectedClipping(untouched, selectionStartFrame, selectionEndFrame)
        val selected = slice(converted, selectionStartFrame, selectionEndFrame)
        val beforeQuantization = ConfluencePersonalVoice.assess(selected)
        require(beforeQuantization.usable) { beforeQuantization.message }
        val root = rootDirectory(libraryDir, create = true)
        require(entryDirectories(root).size < MAX_LIBRARY_ENTRIES) { "The personal voice library is full." }
        val id = UUID.randomUUID().toString().replace("-", "")
        val target = child(root, id)
        val stage = Files.createTempDirectory(root.toPath(), ".pending-").toFile()
        try {
            val raw = File(stage, RAW_FILE)
            AtomicFile.write(raw) { writeFloatWav(it, untouched) }
            val sourceFile = File(stage, SOURCE_FILE)
            AtomicFile.write(sourceFile) { WavWriter.write(it, selected, WavWriter.BitDepth.PCM_24) }
            // Writer quantization is part of the persisted source identity. Never hash the input
            // float buffer and then attach that hash to a different, decoded WAV representation.
            val persisted = readSource(sourceFile)
            val assessment = ConfluencePersonalVoice.assess(persisted)
            require(assessment.usable) { assessment.message }
            val confirmed = ConfluencePersonalVoice.prepare(persisted, referenceMidi = referenceMidi)
            // A near-match confirms the measured pitch; it does not silently retune a saved source.
            val prepared = if (confirmed.referenceMidi == assessment.referenceMidi) confirmed else ConfluencePersonalVoice.prepare(persisted)
            val source = ConfluenceSource.fromSnip(persisted)
            val region = ConfluenceRegion(source.hash, 0, source.frameCount, referenceMidi = prepared.referenceMidi)
            val manifest = obj(
                "format" to str(FORMAT), "schema" to num(SCHEMA), "id" to str(id), "name" to str(title),
                "createdAtMillis" to num(System.currentTimeMillis()), "origin" to str(origin.name),
                "preparationVersion" to num(PREPARATION_VERSION), "analysisVersion" to num(assessment.analysisVersion),
                "referenceMidi" to num(prepared.referenceMidi), "rootMidi" to num(prepared.rootMidi),
                "referenceHz" to num(prepared.referenceHz),
                "raw" to asset(raw, untouched, "IEEE_FLOAT_32", 32),
                "source" to JsonValue.Obj(asset(sourceFile, persisted, "PCM_24", 24).entries + ("pcmHash" to str(source.hash))),
                "selection" to obj("startFrame" to num(selectionStartFrame), "endFrame" to num(selectionEndFrame), "sampleRate" to num(RATE)),
                "region" to regionJson(region), "assessment" to assessment.toJsonValue(),
                "rawClippedSamples" to num(ConfluencePersonalVoice.clippedSampleCount(untouched)),
                "provenance" to obj(
                    "kind" to str(if (origin == Origin.RECORDING) "personal-recording" else "personal-import"),
                    "license" to JsonValue.Null, "factorySource" to JsonValue.Bool(false),
                    "rawPreserved" to JsonValue.Bool(true),
                    "modifications" to str("Raw take retained as exact float32 PCM. Selected source cropped in canonical frames, downmixed/resampled to mono 44.1 kHz where needed, and encoded as PCM24. No cleanup, normalization, fades, denoising, or pitch correction."),
                ),
            )
            AtomicFile.writeText(File(stage, MANIFEST_FILE), Json.write(manifest) + "\n")
            loadEntry(stage, id) // All closure checks succeed before the entry becomes visible.
            publish(stage, target)
            return loadEntry(target, id)
        } finally {
            stage.deleteRecursively()
        }
    }

    fun list(libraryDir: File): List<Summary> {
        require(!Files.isSymbolicLink(libraryDir.toPath())) { "The personal voice folder cannot be a symbolic link." }
        if (!libraryDir.exists()) return emptyList()
        val root = rootDirectory(libraryDir, create = false)
        return entryDirectories(root).map { directory ->
            readFailure { summary(parseManifest(readManifest(directory), directory.name)) }
        }.sortedWith(compareByDescending<Summary> { it.createdAtMillis }.thenBy { it.id })
    }

    fun load(libraryDir: File, id: String): StoredVoice =
        loadEntry(child(rootDirectory(libraryDir, create = false), checkedId(id)), id)

    /** Copy a complete immutable closure, not a cache URI. Existing, changed IDs are never replaced. */
    fun copyToKit(voice: StoredVoice, kitDir: File): StoredVoice {
        val checked = loadEntry(voice.directory, voice.id)
        val kit = rootDirectory(kitDir, create = false)
        val parent = rootDirectory(child(kit, KIT_DIR), create = true)
        val target = child(parent, checked.id)
        if (target.exists()) {
            val existing = loadEntry(target, checked.id)
            require(existing.manifestText == checked.manifestText && FILES.all {
                sha256(checkedFile(existing.directory, it)) == sha256(checkedFile(checked.directory, it))
            }) { "The kit already contains a different version of that personal voice." }
            return existing
        }
        val stage = Files.createTempDirectory(parent.toPath(), ".pending-").toFile()
        try {
            for (name in FILES) AtomicFile.write(File(stage, name)) { output -> checkedFile(checked.directory, name).inputStream().use { it.copyTo(output) } }
            loadEntry(stage, checked.id)
            publish(stage, target)
            return loadEntry(target, checked.id)
        } finally {
            stage.deleteRecursively()
        }
    }

    fun loadFromKit(kitDir: File, id: String): StoredVoice {
        val kit = rootDirectory(kitDir, create = false)
        return load(child(kit, KIT_DIR), id)
    }

    private fun loadEntry(directory: File, expectedId: String): StoredVoice = readFailure {
        loadEntryChecked(directory, expectedId)
    }

    private fun loadEntryChecked(directory: File, expectedId: String): StoredVoice {
        checkedId(expectedId)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "The personal voice source folder is missing or unsafe." }
        val files = directory.listFiles() ?: throw IOException("The personal voice source folder could not be read.")
        require(files.map { it.name }.toSet() == FILES) { "The personal voice source bundle is incomplete or contains unexpected files." }
        val text = readManifest(directory)
        val metadata = parseManifest(text, expectedId)
        val raw = readRaw(checkedFile(directory, RAW_FILE), metadata.getValue("raw").obj())
        val sourceFile = checkedFile(directory, SOURCE_FILE)
        val sourceMeta = metadata.getValue("source").obj()
        verifyAsset(sourceFile, sourceMeta, SOURCE_FILE, MAX_SOURCE_BYTES)
        require(sourceMeta.getValue("encoding").str() == "PCM_24" && sourceMeta.getValue("bitDepth").int() == 24) { "The personal voice source encoding changed." }
        val sourceAudio = readSource(sourceFile)
        verifyShape(sourceAudio, sourceMeta)
        val source = ConfluenceSource.fromSnip(sourceAudio)
        require(sourceMeta.getValue("pcmHash").str() == source.hash) { "The personal voice source PCM changed. Restore its original source bundle." }
        val selection = metadata.getValue("selection").obj()
        require(selection.getValue("sampleRate").int() == RATE) { "The saved voice selection uses an unsupported frame rate." }
        val start = selection.getValue("startFrame").int()
        val end = selection.getValue("endFrame").int()
        val canonicalRaw = canonical(raw)
        validateSelection(canonicalRaw, start, end)
        checkSelectedClipping(raw, start, end)
        val expectedBytes = ByteArrayOutputStream().also { WavWriter.write(it, slice(canonicalRaw, start, end), WavWriter.BitDepth.PCM_24) }.toByteArray()
        require(sha256(expectedBytes) == sourceMeta.getValue("fileSha256").str()) { "The personal voice crop no longer matches its preserved take." }
        require(metadata.getValue("rawClippedSamples").int() == ConfluencePersonalVoice.clippedSampleCount(raw)) { "The preserved take's quality metadata changed." }
        val assessment = ConfluencePersonalVoice.assess(sourceAudio)
        require(assessment.usable) { assessment.message }
        val prepared = ConfluencePersonalVoice.prepare(sourceAudio)
        require(metadata.getValue("analysisVersion").int() == assessment.analysisVersion &&
            metadata.getValue("assessment") == assessment.toJsonValue()) { "The personal voice assessment changed. Reassess the preserved recording." }
        require(metadata.getValue("referenceMidi").num().toFloat() == prepared.referenceMidi &&
            metadata.getValue("rootMidi").int() == prepared.rootMidi &&
            metadata.getValue("referenceHz").num().toFloat() == prepared.referenceHz) { "The personal voice's saved pitch no longer matches its selected source." }
        val region = ConfluenceRegion(source.hash, 0, source.frameCount, referenceMidi = prepared.referenceMidi)
        require(metadata.getValue("region") == regionJson(region)) { "The personal voice's saved region no longer matches its selected source." }
        val info = summary(metadata)
        require(info.seconds == sourceAudio.durationSeconds) { "The saved personal voice duration changed." }
        require(files.sumOf { it.length() } <= MAX_ENTRY_BYTES) { "The personal voice source bundle exceeds its size budget." }
        return StoredVoice(info, directory, text, start, end, assessment, prepared, source, region, sourceAudio)
    }

    private fun canonical(take: Snip): Snip {
        val mono = if (take.channels == 1) take.copy(samples = take.samples.copyOf()) else Cleanup.toMono(take)
        return if (mono.sampleRate == RATE) mono else Resampler.resample(mono, RATE)
    }

    private fun validateTake(take: Snip) {
        require(take.channels in 1..2 && take.sampleRate in 8_000..192_000) { "Record or import mono/stereo audio between 8 and 192 kHz." }
        require(take.frameCount > 0 && take.frameCount.toLong() <= take.sampleRate.toLong() * 30) { "Keep the untreated voice take within 30 seconds." }
        require(take.samples.size.toLong() * 4 + 44 <= MAX_RAW_BYTES) { "The untreated voice take exceeds its 24 MiB budget." }
        require(take.samples.all { it.isFinite() }) { "The untreated voice take contains nonfinite audio." }
    }

    private fun validateSelection(take: Snip, start: Int, end: Int) {
        require(start >= 0 && end > start && end <= take.frameCount) { "Choose a complete voice passage inside the preserved take." }
    }

    private fun slice(take: Snip, start: Int, end: Int): Snip =
        Snip(take.samples.copyOfRange(start * take.channels, end * take.channels), take.channels, take.sampleRate)

    private fun checkSelectedClipping(raw: Snip, start: Int, end: Int) {
        val first = floor(start.toDouble() * raw.sampleRate / RATE).toInt().coerceIn(0, raw.frameCount)
        val last = ceil(end.toDouble() * raw.sampleRate / RATE).toInt().coerceIn(first, raw.frameCount)
        require(ConfluencePersonalVoice.clippedSampleCount(slice(raw, first, last)) == 0) { "The selected original recording clips. Choose an undistorted passage or record again." }
    }

    private fun readRaw(file: File, metadata: Map<String, JsonValue>): Snip {
        verifyAsset(file, metadata, RAW_FILE, MAX_RAW_BYTES)
        require(metadata.getValue("encoding").str() == "IEEE_FLOAT_32" && metadata.getValue("bitDepth").int() == 32) { "The preserved take's encoding changed." }
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" && String(bytes, 12, 4, Charsets.US_ASCII) == "fmt " &&
            header.getInt(16) == 16 && header.getShort(20).toInt() == 3 && header.getShort(34).toInt() == 32 &&
            String(bytes, 36, 4, Charsets.US_ASCII) == "data" && header.getInt(40) == bytes.size - 44 && header.getInt(4) == bytes.size - 8) {
            "The preserved take is not its original bounded float WAV."
        }
        val channels = header.getShort(22).toInt()
        val rate = header.getInt(24)
        require(channels in 1..2 && rate in 8_000..192_000 && (bytes.size - 44) % (4 * channels) == 0 &&
            header.getInt(28) == rate * channels * 4 && header.getShort(32).toInt() == channels * 4) { "The preserved take has an invalid PCM shape." }
        val samples = FloatArray((bytes.size - 44) / 4) { header.getFloat(44 + it * 4) }
        val snip = Snip(samples, channels, rate)
        validateTake(snip)
        verifyShape(snip, metadata)
        return snip
    }

    private fun readSource(file: File): Snip {
        require(file.length() in 44..MAX_SOURCE_BYTES) { "The saved voice source is missing or exceeds its size budget." }
        val audio = WavReader.read(file)
        require(audio.channels == 1 && audio.sampleRate == RATE) { "The saved voice source is not mono 44.1 kHz." }
        return audio
    }

    private fun verifyAsset(file: File, metadata: Map<String, JsonValue>, name: String, limit: Long) {
        require(metadata.getValue("path").str() == name) { "The personal voice contains an unsafe source path." }
        val bytes = metadata.getValue("bytes").long()
        val hash = metadata.getValue("fileSha256").str()
        require(HASH.matches(hash) && bytes in 44..limit && file.length() == bytes && sha256(file) == hash) { "The personal voice asset $name is missing, changed, or too large." }
    }

    private fun verifyShape(audio: Snip, metadata: Map<String, JsonValue>) {
        require(metadata.getValue("sampleRate").int() == audio.sampleRate && metadata.getValue("channels").int() == audio.channels &&
            metadata.getValue("frames").int() == audio.frameCount) { "The personal voice audio no longer matches its saved shape." }
    }

    private fun parseManifest(text: String, expectedId: String): Map<String, JsonValue> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "The personal voice manifest exceeds its size budget." }
        val metadata = try { Json.parse(text).obj() } catch (e: RuntimeException) {
            throw IllegalArgumentException("The personal voice manifest cannot be read. Restore its original source bundle.", e)
        }
        require(metadata.getValue("format").str() == FORMAT && metadata.getValue("schema").int() == SCHEMA) { "This is not a supported personal voice source bundle." }
        require(metadata.getValue("id").str() == checkedId(expectedId)) { "The personal voice identity changed." }
        require(metadata.getValue("preparationVersion").int() == PREPARATION_VERSION) { "This personal voice uses an unsupported preparation version." }
        checkedName(metadata.getValue("name").str())
        Origin.valueOf(metadata.getValue("origin").str())
        val provenance = metadata.getValue("provenance").obj()
        require(provenance.getValue("license") == JsonValue.Null && !provenance.getValue("factorySource").bool() && provenance.getValue("rawPreserved").bool()) {
            "The personal recording's ownership metadata changed."
        }
        return metadata
    }

    private fun summary(metadata: Map<String, JsonValue>): Summary = readFailure { summaryChecked(metadata) }

    private fun summaryChecked(metadata: Map<String, JsonValue>): Summary {
        val source = metadata.getValue("source").obj()
        val reference = metadata.getValue("referenceMidi").num().toFloat()
        val root = metadata.getValue("rootMidi").int()
        val frames = source.getValue("frames").int()
        val created = metadata.getValue("createdAtMillis").long()
        require(reference.isFinite() && reference in 0f..127f && root in 0..127 && frames in 1..RATE * 30 && created >= 0) { "The saved personal voice metadata is invalid." }
        return Summary(metadata.getValue("id").str(), metadata.getValue("name").str(), Origin.valueOf(metadata.getValue("origin").str()), reference, root, frames.toFloat() / RATE, created)
    }

    private fun readManifest(directory: File): String {
        val file = checkedFile(directory, MANIFEST_FILE)
        require(file.length() in 1..MAX_MANIFEST_BYTES) { "The personal voice manifest is missing or too large." }
        return file.readText(Charsets.UTF_8)
    }

    private fun rootDirectory(directory: File, create: Boolean): File {
        require(!Files.isSymbolicLink(directory.toPath())) { "The personal voice folder cannot be a symbolic link." }
        if (create) require(directory.isDirectory || directory.mkdirs()) { "The personal voice folder could not be created." }
        require(directory.isDirectory) { "The personal voice folder is missing." }
        return directory.canonicalFile
    }

    private fun child(root: File, name: String): File = File(root, name).also {
        require(it.canonicalFile.parentFile == root.canonicalFile && !Files.isSymbolicLink(it.toPath())) { "The personal voice path escapes its source folder." }
    }

    private fun checkedFile(directory: File, name: String): File = child(directory, name).also {
        require(it.isFile) { "The personal voice source file $name is missing." }
    }

    private fun checkedId(id: String): String = id.also { require(ID.matches(it)) { "The personal voice ID is invalid." } }
    private fun checkedName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME_CHARS && it.none { c -> c.code < 32 || c.code == 127 }) { "Name this voice with 1–$MAX_NAME_CHARS visible characters." }
    }

    private fun entryDirectories(root: File): List<File> {
        val entries = (root.listFiles() ?: throw IOException("The personal voice library could not be read.")).filter { ID.matches(it.name) }
        require(entries.size <= MAX_LIBRARY_ENTRIES) { "The personal voice library exceeds its bounded entry budget." }
        return entries.map { child(root, it.name).also { entry -> require(entry.isDirectory) { "A saved personal voice is not a folder." } } }
    }

    private fun publish(stage: File, target: File) = synchronized(this) {
        require(!target.exists()) { "A personal voice already uses that immutable identity." }
        require(entryDirectories(target.parentFile).size < MAX_LIBRARY_ENTRIES) { "The personal voice folder exceeds its entry budget." }
        try { Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (e: java.nio.file.AtomicMoveNotSupportedException) { Files.move(stage.toPath(), target.toPath()) }
    }

    private fun asset(file: File, audio: Snip, encoding: String, depth: Int): JsonValue.Obj = obj(
        "path" to str(file.name), "fileSha256" to str(sha256(file)), "bytes" to num(file.length()),
        "frames" to num(audio.frameCount), "sampleRate" to num(audio.sampleRate), "channels" to num(audio.channels),
        "encoding" to str(encoding), "bitDepth" to num(depth),
    )

    private fun regionJson(region: ConfluenceRegion): JsonValue.Obj = obj(
        "assetHash" to str(region.assetHash), "startFrame" to num(region.startFrame), "endFrame" to num(region.endFrame),
        "mode" to str(region.mode.name), "referenceMidi" to num(requireNotNull(region.referenceMidi)),
        "sustainStartFrame" to JsonValue.Null, "sustainEndFrame" to JsonValue.Null,
    )

    private fun writeFloatWav(output: OutputStream, audio: Snip) {
        val dataBytes = audio.samples.size * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16).putShort(3).putShort(audio.channels.toShort()).putInt(audio.sampleRate)
        header.putInt(audio.sampleRate * audio.channels * 4).putShort((audio.channels * 4).toShort()).putShort(32)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        output.write(header.array())
        val block = ByteBuffer.allocate(16_384).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audio.samples) {
            if (block.remaining() < 4) { output.write(block.array(), 0, block.position()); block.clear() }
            block.putFloat(sample)
        }
        if (block.position() > 0) output.write(block.array(), 0, block.position())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(16_384)
            while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun obj(vararg values: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*values))
    private fun str(value: String): JsonValue.Str = JsonValue.Str(value)
    private fun num(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble())

    private inline fun <T> readFailure(block: () -> T): T = try { block() }
    catch (e: IllegalArgumentException) { throw e }
    catch (e: Exception) { throw IllegalArgumentException("The personal voice source bundle cannot be read. Restore its original files.", e) }
}
