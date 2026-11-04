package com.snipsnap.kit

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Exact curated-bank dependencies. This transports sources, not cultural or DSP eligibility. */
object ConfluenceBankFiles {
    const val DIRECTORY = ".confluence-banks"
    const val ENGINE = "CONFLUENCE_SOURCE_BANK"
    const val RENDERED_ENGINE = "CONFLUENCE_SOURCE_BANK_RENDERED"
    const val MAX_BANKS = 4
    const val MAX_BANK_BYTES = 64L * 1024 * 1024
    const val MAX_KIT_BYTES = 128L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024L * 1024
    const val MAX_AUDIO_BYTES = 4L * 1024 * 1024
    const val MAX_TEXT_BYTES = 256L * 1024
    internal const val MAX_ASSETS = 80
    private val HASH = Regex("[0-9a-f]{64}")
    private val ID = Regex("[a-z][a-z0-9-]{1,63}")
    private const val RESERVE = ConfluencePersonalFiles.KIT_METADATA_RESERVE_BYTES

    fun bankDirectory(kitDir: File, manifestSha256: String): File {
        require(HASH.matches(manifestSha256)) { "invalid Confluence bank manifest fingerprint" }
        return child(kitDir, "$DIRECTORY/$manifestSha256/bank.json").parentFile
    }

    fun requireImportable(kit: Kit, sourceDir: File) { requireTogether(listOf(kit to sourceDir)) }

    /** Admission performs no writes, and includes already referenced personal source dependencies. */
    fun requireCapacity(kit: Kit, kitDir: File, newManifestSha256: String, newBankBytes: Long) {
        require(HASH.matches(newManifestSha256)) { "invalid incoming bank fingerprint" }
        require(newBankBytes in 1..MAX_BANK_BYTES) { "incoming Confluence bank exceeds its byte budget" }
        val existing = fingerprints(kit)
        require((existing + newManifestSha256).distinct().size <= MAX_BANKS) { "Confluence source capacity exceeds $MAX_BANKS banks" }
        val current = files(kit, kitDir).sumOf { it.second.length() } + ConfluencePersonalFiles.files(kit, kitDir).sumOf { it.second.length() }
        val additional = if (newManifestSha256 in existing) 0L else newBankBytes
        require(current <= MAX_KIT_BYTES - RESERVE - additional) { "Confluence dependencies exceed the native backup byte budget including kit metadata reserve" }
    }

    internal fun fingerprints(kit: Kit): List<String> = kit.pads.filter { it.source["engine"] == ENGINE }
        .map { requireNotNull(it.source["bankManifestSha256"]) { "selected source pad has no bank manifest fingerprint" } }
        .distinct().sorted().also { hashes ->
            require(hashes.size <= MAX_BANKS && hashes.all { HASH.matches(it) }) { "invalid or excessive selected-bank fingerprints" }
        }

    internal fun allowedAsset(path: String): Boolean = path == "SOURCE-ATTRIBUTION.txt" ||
        Regex("voices/[A-Za-z0-9][A-Za-z0-9_.-]*\\.wav").matches(path) ||
        Regex("licenses/[A-Za-z0-9][A-Za-z0-9_.-]*\\.txt").matches(path)

    internal fun allowedPath(path: String): Boolean {
        val parts = path.split('/', limit = 3)
        return parts.size == 3 && parts[0] == DIRECTORY && HASH.matches(parts[1]) &&
            (parts[2] == "bank.json" || allowedAsset(parts[2]))
    }

    internal fun limit(path: String): Long = when {
        path.endsWith("/bank.json") -> MAX_MANIFEST_BYTES
        path.substringAfter('/', "").contains("/voices/") -> MAX_AUDIO_BYTES
        else -> MAX_TEXT_BYTES
    }

    internal fun child(root: File, path: String): File {
        require(allowedPath(path)) { "unsafe selected-bank source path: $path" }
        require(!Files.isSymbolicLink(root.toPath())) { "selected bank cannot follow a symbolic link" }
        var file = root
        for (part in path.split('/')) {
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "selected bank cannot follow a symbolic link: $path" }
        }
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "selected-bank path escapes its kit" }
        return file
    }

    internal data class Descriptor(val id: String, val version: Int, val fingerprint: String,
        val voiceIds: Set<String>, val files: List<Pair<String, File>>)

    internal fun descriptor(root: File, fingerprint: String): Descriptor {
        try {
            val dir = bankDirectory(root, fingerprint)
            val manifest = File(dir, "bank.json")
            require(manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES) { "missing or oversized selected-bank manifest" }
            require(XpnSourceMetadata.sha256(manifest) == fingerprint) { "selected-bank manifest fingerprint differs" }
            val bank = Json.parse(manifest.readText(Charsets.UTF_8)).obj()
            require(bank.getValue("format").str() == "snipsnap-confluence-selected-bank" && bank.getValue("schema").int() == 1) { "unsupported selected-bank manifest" }
            val id = bank.getValue("id").str()
            val version = bank.getValue("version").int()
            require(ID.matches(id) && version > 0) { "invalid selected-bank identity" }
            val assets = bank.getValue("assets").arr()
            require(assets.size in 3..MAX_ASSETS) { "selected bank declares too many or too few assets" }
            val files = linkedMapOf("bank.json" to manifest)
            val kinds = LinkedHashMap<String, String>()
            var total = manifest.length()
            for (value in assets) {
                val asset = value.obj()
                val path = asset.getValue("path").str()
                require(allowedAsset(path) && path !in files) { "unsafe or duplicate selected-bank asset: $path" }
                val kind = asset.getValue("kind").str()
                require(kind == when { path.startsWith("voices/") -> "audio"; path.startsWith("licenses/") -> "license"; else -> "notice" }) { "selected-bank asset kind disagrees with its path" }
                val fullPath = "$DIRECTORY/$fingerprint/$path"
                val file = child(root, fullPath)
                val bytes = asset.getValue("bytes").long()
                val hash = asset.getValue("sha256").str()
                require(bytes in 1..limit(fullPath) && HASH.matches(hash) && file.isFile && file.length() == bytes) { "missing or oversized selected-bank asset: $path" }
                total += bytes
                require(total <= MAX_BANK_BYTES) { "selected-bank source closure exceeds its byte budget" }
                require(XpnSourceMetadata.sha256(file) == hash) { "selected-bank asset checksum differs: $path" }
                files[path] = file; kinds[path] = kind
            }
            require(kinds["SOURCE-ATTRIBUTION.txt"] == "notice") { "selected bank has no portable source attribution notice" }
            val licenses = bank.getValue("licenses").arr()
            require(licenses.size in 1..8 && licenses.all { kinds[it.obj().getValue("path").str()] == "license" }) { "selected bank license payload is missing" }
            val voices = bank.getValue("voices").arr()
            require(voices.size in 1..64) { "selected bank voice count exceeds its bound" }
            val voiceIds = HashSet<String>()
            val audioPaths = HashSet<String>()
            for (value in voices) {
                val voice = value.obj()
                val voiceId = voice.getValue("id").str()
                val path = voice.getValue("assetPath").str()
                require(ID.matches(voiceId) && voiceIds.add(voiceId) && kinds[path] == "audio" && audioPaths.add(path)) { "invalid selected-bank voice binding" }
                val file = files.getValue(path)
                val audio = WavInfo.read(file)
                require(audio.sampleRate == WavWriter.MPC_SAMPLE_RATE && audio.channels == 1 && audio.bitsPerSample == 24 &&
                    audio.frameCount == voice.getValue("frames").long() && audio.frameCount in
                    (WavWriter.MPC_SAMPLE_RATE / 4).toLong()..(WavWriter.MPC_SAMPLE_RATE * 30).toLong()) { "selected-bank voice is not bounded canonical mono PCM24" }
                require(voice.getValue("fileSha256").str() == XpnSourceMetadata.sha256(file) &&
                    voice.getValue("canonicalPcmSha256").str() == ConfluencePersonalFiles.canonicalHash(file)) { "selected-bank voice fingerprint differs from its audio" }
            }
            require(audioPaths == kinds.filterValues { it == "audio" }.keys) { "selected-bank assets contain unbound voices" }
            return Descriptor(id, version, fingerprint, voiceIds, files.map { (path, file) -> "$DIRECTORY/$fingerprint/$path" to file })
        } catch (e: JsonException) { throw IllegalArgumentException("unreadable selected-bank manifest: ${e.message}", e) }
        catch (e: NoSuchElementException) { throw IllegalArgumentException("incomplete selected-bank manifest: ${e.message}", e) }
        catch (e: IOException) { throw IllegalArgumentException("unreadable selected-bank asset: ${e.message}", e) }
    }

    internal fun files(kit: Kit, root: File): List<Pair<String, File>> = fingerprints(kit).flatMap { fingerprint ->
        val bank = descriptor(root, fingerprint)
        for (pad in kit.pads.filter { it.source["engine"] == ENGINE && it.source["bankManifestSha256"] == fingerprint }) {
            require(pad.source["bankId"] == bank.id && pad.source["bankVersion"] == bank.version.toString()) { "selected-bank identity differs at pad ${pad.slot}" }
            val ids = pad.source["usedVoiceIds"]?.split(',').orEmpty()
            val hashes = pad.source["sourceHashes"]?.split(',').orEmpty()
            require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in bank.voiceIds } &&
                hashes.isNotEmpty() && hashes.all { HASH.matches(it) } && !pad.source["bankRecipe"].isNullOrBlank() &&
                pad.source.getValue("bankRecipe").length <= 100_000) { "selected-bank source recipe binding differs at pad ${pad.slot}" }
        }
        bank.files
    }.also { files -> require(files.sumOf { it.second.length() } <= MAX_KIT_BYTES - RESERVE) { "selected-bank dependencies exceed the kit byte budget" } }

    internal fun requireTogether(sources: List<Pair<Kit, File>>) {
        val banks = LinkedHashMap<String, Pair<String, File>>()
        val personal = LinkedHashMap<String, File>()
        for ((kit, root) in sources) {
            for ((path, file) in files(kit, root)) {
                banks.putIfAbsent(path, path to file)?.let { require(XpnSourceMetadata.sha256(it.second) == XpnSourceMetadata.sha256(file)) { "conflicting selected-bank source fingerprints" } }
            }
            for ((path, file) in ConfluencePersonalFiles.files(kit, root)) personal.putIfAbsent(path, file)
        }
        require(banks.keys.map { it.split('/')[1] }.distinct().size <= MAX_BANKS) { "selected-bank copy exceeds $MAX_BANKS banks" }
        require(banks.values.sumOf { it.second.length() } + personal.values.sumOf { it.length() } <= MAX_KIT_BYTES - RESERVE) { "combined Confluence dependencies exceed the kit byte budget including metadata reserve" }
    }

    internal fun physicalFiles(root: File): List<Pair<String, File>> {
        val container = File(root, DIRECTORY)
        if (!container.exists()) return emptyList()
        require(container.isDirectory && !Files.isSymbolicLink(container.toPath())) { "unsafe selected-bank container" }
        val result = ArrayList<Pair<String, File>>()
        Files.newDirectoryStream(container.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                require(++count <= MAX_BANKS) { "too many existing selected banks" }
                val fingerprint = entry.fileName.toString()
                require(HASH.matches(fingerprint)) { "unsafe selected-bank destination" }
                result += descriptor(root, fingerprint).files
            }
        }
        require(result.sumOf { it.second.length() } <= MAX_KIT_BYTES - RESERVE) { "selected-bank destination exceeds its byte budget" }
        return result
    }

    private fun personalPhysicalBytes(root: File): Long {
        val container = File(root, ConfluencePersonalFiles.DIRECTORY)
        if (!container.exists()) return 0
        require(container.isDirectory && !Files.isSymbolicLink(container.toPath())) { "unsafe personal source container" }
        var bytes = 0L
        Files.newDirectoryStream(container.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                require(++count <= ConfluencePersonalFiles.MAX_VOICES) { "too many existing personal voices" }
                val id = entry.fileName.toString()
                ConfluencePersonalFiles.validateVoice(root, id)
                bytes += listOf("voice.json", "raw.wav", "source.wav").sumOf { ConfluencePersonalFiles.child(root, "${ConfluencePersonalFiles.DIRECTORY}/$id/$it").length() }
            }
        }
        return bytes
    }

    fun copyReferenced(kit: Kit, sourceDir: File, targetDir: File) {
        val incoming = files(kit, sourceDir)
        if (incoming.isEmpty()) return
        val existing = physicalFiles(targetDir).toMap()
        val all = existing.toMutableMap()
        for ((path, file) in incoming) {
            all.putIfAbsent(path, file)?.let { require(XpnSourceMetadata.sha256(it) == XpnSourceMetadata.sha256(file)) { "conflicting selected-bank destination files" } }
            child(targetDir, path)
        }
        require(all.keys.map { it.split('/')[1] }.distinct().size <= MAX_BANKS) { "selected-bank copy exceeds $MAX_BANKS banks" }
        require(all.values.sumOf { it.length() } + personalPhysicalBytes(targetDir) <= MAX_KIT_BYTES - RESERVE) { "combined Confluence copy exceeds its byte budget" }
        require(targetDir.isDirectory || targetDir.mkdirs()) { "cannot create selected-bank target" }
        for ((fingerprint, grouped) in incoming.groupBy { it.first.split('/')[1] }) {
            if (grouped.first().first in existing) continue
            val staging = Files.createTempDirectory(targetDir.toPath(), ".selected-bank-copy-").toFile()
            try {
                for ((path, source) in grouped) {
                    val target = child(staging, path)
                    require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "cannot stage selected-bank asset" }
                    source.copyTo(target)
                }
                descriptor(staging, fingerprint)
                val destination = bankDirectory(targetDir, fingerprint)
                require(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "cannot create selected-bank container" }
                require(!destination.exists() && bankDirectory(staging, fingerprint).renameTo(destination)) { "cannot publish selected-bank source closure" }
            } finally { staging.deleteRecursively() }
        }
    }
}
