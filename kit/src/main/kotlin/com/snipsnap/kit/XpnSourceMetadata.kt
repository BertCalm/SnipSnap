package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.LimitedRead
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Optional SnipSnap provenance, bound to one program and its exact sample entries. Never extracts files. */
internal object XpnSourceMetadata {
    const val MAX_BYTES: Long = 2L * 1024 * 1024
    const val MAX_NOTICE_BYTES: Long = 1024L * 1024
    private const val FORMAT = "snipsnap-xpn-source"
    private const val MAX_FIELDS = 128
    private const val MAX_KEY_CHARS = 256
    private const val MAX_VALUE_CHARS = 1_048_576

    data class Sample(val name: String, val entry: String, val sha256: String)
    data class Pad(val slot: Int, val samples: List<Sample>, val source: Map<String, String>)
    data class Metadata(val pads: List<Pad>) {
        /** Reject stale or cross-program provenance rather than attaching it to a different sound. */
        fun validate(samplesBySlot: Map<Int, List<String>>) {
            require(pads.map { it.slot }.toSet() == samplesBySlot.keys) { "source metadata pad slots differ from the program" }
            for (pad in pads) {
                require(pad.samples.map { it.name } == samplesBySlot.getValue(pad.slot)) {
                    "source metadata sample binding differs at pad ${pad.slot}"
                }
            }
            for (group in pads.flatMap { it.samples }.groupBy { it.name.lowercase() }.values) {
                require(group.distinct().size == 1) { "source metadata has conflicting sample bindings" }
            }
        }
    }

    fun entryName(programEntry: String): String = "$programEntry.snipsnap-source.json"

    /** Nothing is added to archives whose pads have no source metadata. */
    fun render(kit: Kit, kitDir: File, programEntry: String, programBytes: ByteArray, samplesPrefix: String): ByteArray? {
        if (kit.pads.all { it.source.isEmpty() }) return null
        var sourceChars = 0L
        for (pad in kit.pads) {
            validateSource(pad.source)
            sourceChars += pad.source.entries.sumOf { it.key.length.toLong() + it.value.length }
            require(sourceChars <= MAX_BYTES) { "XPN source metadata exceeds $MAX_BYTES characters" }
        }
        val pads = kit.pads.sortedBy { it.slot }.map { pad ->
            val files = if (pad.velocityLayers.isEmpty()) listOf(pad.sampleFile) else pad.velocityLayers.map { it.sampleFile }
            JsonValue.Obj(linkedMapOf(
                "slot" to JsonValue.Num(pad.slot.toDouble()),
                "samples" to JsonValue.Arr(files.map { file ->
                    val stem = Names.sanitizeStem(file.substringBeforeLast('.'))
                    JsonValue.Obj(linkedMapOf(
                        "name" to JsonValue.Str(stem),
                        "entry" to JsonValue.Str("$samplesPrefix/$stem.wav"),
                        "sha256" to JsonValue.Str(sha256(File(kitDir, file))),
                    ))
                }),
                "source" to JsonValue.Obj(pad.source.toSortedMap().mapValues { JsonValue.Str(it.value) }),
            ))
        }
        val bytes = (Json.write(JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str(FORMAT),
            "version" to JsonValue.Num(1.0),
            "programEntry" to JsonValue.Str(programEntry),
            "programSha256" to JsonValue.Str(sha256(programBytes)),
            "pads" to JsonValue.Arr(pads),
        ))) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "XPN source metadata exceeds $MAX_BYTES bytes" }
        return bytes
    }

    fun read(zip: ZipFile, entries: List<ZipEntry>, programEntry: String, programBytes: ByteArray, budget: XpnImporter.WriteBudget): Metadata? {
        val entry = singleEntry(entries, entryName(programEntry)) ?: return null
        val bytes = zip.getInputStream(entry).use { readBudgeted(it, MAX_BYTES, "source metadata for $programEntry", budget) }
        try {
            val root = Json.parse(bytes.toString(Charsets.UTF_8)).obj()
            require(root.keys == setOf("format", "version", "programEntry", "programSha256", "pads")) { "invalid source metadata fields" }
            require(root.getValue("format").str() == FORMAT && root.getValue("version").int() == 1) { "unsupported source metadata format" }
            require(root.getValue("programEntry").str() == programEntry && root.getValue("programSha256").str() == sha256(programBytes)) {
                "source metadata does not match program $programEntry"
            }
            val rawPads = root.getValue("pads").arr()
            require(rawPads.size in 1..128) { "source metadata must contain 1..128 pads" }
            val pads = rawPads.map { value ->
                val p = value.obj()
                require(p.keys == setOf("slot", "samples", "source")) { "invalid source metadata pad fields" }
                val slot = p.getValue("slot").int()
                require(slot in 1..128) { "source metadata pad slot out of range" }
                val rawSamples = p.getValue("samples").arr()
                require(rawSamples.size in 1..4) { "source metadata pad needs 1..4 sample bindings" }
                val samples = rawSamples.map { sample ->
                    val s = sample.obj()
                    require(s.keys == setOf("name", "entry", "sha256")) { "invalid source metadata sample fields" }
                    val name = s.getValue("name").str()
                    val path = s.getValue("entry").str()
                    val hash = s.getValue("sha256").str()
                    require(name.isNotBlank() && name.length <= 256 && '/' !in name && '\\' !in name) { "invalid source metadata sample name" }
                    val programStem = File(programEntry).nameWithoutExtension
                    require(safeEntry(path) && path == "Samples/$programStem/$name.wav") { "invalid source metadata sample entry" }
                    require(hash.matches(Regex("[0-9a-f]{64}"))) { "invalid source metadata sample hash" }
                    Sample(name, path, hash)
                }
                val source = p.getValue("source").obj().mapValues { it.value.str() }
                validateSource(source)
                Pad(slot, samples, source)
            }
            require(pads.map { it.slot }.distinct().size == pads.size) { "duplicate source metadata pad slot" }
            return Metadata(pads)
        } catch (e: JsonException) {
            throw IllegalArgumentException("invalid source metadata for $programEntry: ${e.message}", e)
        }
    }

    /** Older native exports have a complete text notice but no machine-readable pad bindings. */
    fun legacyNotice(zip: ZipFile, entries: List<ZipEntry>, programEntry: String, kitName: String, padCount: Int, budget: XpnImporter.WriteBudget): String? {
        if (programEntry != "Programs/$kitName.xpm") return null
        val noticeName = "${Names.sanitizeStem(kitName)}-source-attribution.txt"
        val candidates = listOf(noticeName, "Samples/$kitName/$noticeName")
        val notices = candidates.mapNotNull { name ->
            singleEntry(entries, name)?.let { entry ->
                // The coarse notice follows every retained pad. Bound the
                // aggregate before repeating it in the saved kit metadata.
                val limit = minOf(MAX_NOTICE_BYTES, MAX_BYTES / padCount.coerceAtLeast(1))
                val header = "SOURCE ATTRIBUTION\n".toByteArray(Charsets.UTF_8)
                val text = zip.getInputStream(entry).buffered(header.size).use { input ->
                    // A foreign pack can use the same filename. Only the
                    // native header opts into source-budget accounting.
                    input.mark(header.size)
                    val prefix = ByteArray(header.size)
                    var prefixSize = 0
                    while (prefixSize < prefix.size) {
                        val count = input.read(prefix, prefixSize, prefix.size - prefixSize)
                        if (count < 0) break
                        prefixSize += count
                    }
                    input.reset()
                    if (prefixSize != header.size || !prefix.contentEquals(header)) return@use null
                    readBudgeted(input, limit, "source attribution for $programEntry", budget).toString(Charsets.UTF_8)
                } ?: return@let null
                if (name == noticeName) {
                    val matchingPrograms = entries.filter { candidate ->
                        candidate.name.startsWith("Programs/") && candidate.name.endsWith(".xpm") &&
                            candidate.name.removePrefix("Programs/").none { it == '/' || it == '\\' } &&
                            "${Names.sanitizeStem(File(candidate.name).nameWithoutExtension)}-source-attribution.txt" == noticeName
                    }
                    require(matchingPrograms.size == 1) { "ambiguous source attribution notice for $programEntry" }
                }
                require(text.startsWith("SOURCE ATTRIBUTION\n$kitName\n\n")) { "source attribution notice does not match $programEntry" }
                text
            }
        }.distinct()
        require(notices.size <= 1) { "conflicting source attribution notices for $programEntry" }
        return notices.singleOrNull()?.also { notice ->
            // A legacy aggregate notice is copied to every pad. Charge the
            // escaped kit.json payload, not just its single compressed ZIP
            // entry; its first plain-text copy was charged while reading.
            val payloadBytes = Json.write(JsonValue.Str(notice)).toByteArray(Charsets.UTF_8).size.toLong() * padCount
            val additional = (payloadBytes - notice.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0L)
            if (additional > budget.remaining) {
                budget.spend(budget.remaining)
                throw LimitedRead.TooLargeException("source attribution for $programEntry exceeds the remaining import budget")
            }
            budget.spend(additional)
        }
    }

    /** Debit each accepted chunk immediately, including attempts later refused by parsing or the entry cap. */
    private fun readBudgeted(input: InputStream, limit: Long, what: String, budget: XpnImporter.WriteBudget): ByteArray {
        val allowed = minOf(limit, budget.remaining)
        if (allowed == 0L) throw LimitedRead.TooLargeException("$what exceeds the remaining import budget")
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total < allowed) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), allowed - total).toInt())
            if (count < 0) return output.toByteArray()
            budget.spend(count.toLong())
            total += count
            output.write(buffer, 0, count)
        }
        // One lookahead distinguishes an entry exactly at its limit from
        // an oversized one. A failed attempt cannot replenish its budget.
        if (input.read() >= 0) {
            budget.spend(1)
            throw LimitedRead.TooLargeException("$what exceeds its source limit or the remaining import budget")
        }
        return output.toByteArray()
    }

    fun singleEntry(entries: List<ZipEntry>, name: String): ZipEntry? {
        val matches = entries.filter { it.name == name }
        require(matches.size <= 1) { "duplicate archive entry: $name" }
        return matches.singleOrNull()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safeEntry(path: String): Boolean = path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') &&
        '\\' !in path && ':' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun validateSource(source: Map<String, String>) {
        require(source.size <= MAX_FIELDS) { "too many source metadata fields" }
        require(source.all { (key, value) -> key.isNotBlank() && key.length <= MAX_KEY_CHARS && value.length <= MAX_VALUE_CHARS }) {
            "source metadata field exceeds its limit"
        }
    }
}
