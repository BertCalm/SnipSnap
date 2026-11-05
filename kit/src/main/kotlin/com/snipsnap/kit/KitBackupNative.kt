package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.LimitedRead
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Native backup-only source closure. Ordinary XPN/MPC exports deliberately do not include it. */
internal object KitBackupNative {
    const val PREFIX = ".snipsnap-native/"
    private const val INDEX = "native.json"
    private const val FORMAT = "snipsnap-native-personal-sources"
    private const val MAX_INDEX_BYTES = 128L * 1024
    const val MAX_ARCHIVE_BYTES = ConfluencePersonalFiles.MAX_KIT_BYTES + 4L * 1024 * 1024

    fun entryName(xpnName: String): String = "$PREFIX$xpnName.zip"

    fun write(kit: Kit, kitDir: File, xpn: File, output: File): File? {
        if (ConfluencePersonalFiles.referencedIds(kit).isEmpty()) return null
        val files = listOf(KitStore.FILE_NAME to ConfluencePersonalFiles.child(kitDir, KitStore.FILE_NAME)) +
            ConfluencePersonalFiles.files(kit, kitDir)
        require(files.all { (path, file) -> file.isFile && file.length() in 1..ConfluencePersonalFiles.limit(path) }) { "native backup file exceeds its source limit" }
        require(files.sumOf { it.second.length() } <= ConfluencePersonalFiles.MAX_KIT_BYTES) { "native personal source closure exceeds the per-kit byte budget" }
        val index = JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str(FORMAT), "schema" to JsonValue.Num(1.0),
            "xpnSha256" to JsonValue.Str(XpnSourceMetadata.sha256(xpn)),
            "files" to JsonValue.Arr(files.map { (path, file) -> JsonValue.Obj(linkedMapOf(
                "path" to JsonValue.Str(path), "bytes" to JsonValue.Num(file.length().toDouble()),
                "sha256" to JsonValue.Str(XpnSourceMetadata.sha256(file)),
            )) }),
            "pads" to JsonValue.Arr(kit.pads.sortedBy { it.slot }.map { pad -> JsonValue.Obj(linkedMapOf(
                "slot" to JsonValue.Num(pad.slot.toDouble()),
                "sampleHashes" to JsonValue.Arr(sampleFiles(pad).map { JsonValue.Str(XpnSourceMetadata.sha256(SafePath.child(kitDir, it))) }),
            )) }),
        ))
        val indexBytes = (Json.write(index) + "\n").toByteArray(Charsets.UTF_8)
        require(indexBytes.size <= MAX_INDEX_BYTES) { "native backup index exceeds its byte limit" }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun put(name: String, copy: (OutputStream) -> Unit) {
                zip.putNextEntry(ZipEntry(name).also { it.time = 1_577_836_800_000L })
                copy(zip); zip.closeEntry()
            }
            put(INDEX) { it.write(indexBytes) }
            for ((path, file) in files) put(path) { out -> file.inputStream().use { it.copyTo(out) } }
        }
        require(output.length() <= MAX_ARCHIVE_BYTES) { "native source archive exceeds its byte limit" }
        return output
    }

    /** Runs inside XpnImporter's staging directory, before an existing kit can be replaced. */
    fun restore(archive: File, xpn: File, imported: Kit, staging: File, budget: XpnImporter.WriteBudget): Kit {
        try {
            ZipFile(archive).use { zip ->
                val entries = LinkedHashMap<String, ZipEntry>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    require(!entry.isDirectory && (entry.name == INDEX || ConfluencePersonalFiles.allowedPath(entry.name))) { "unsafe native backup path: ${entry.name}" }
                    require(entries.put(entry.name, entry) == null) { "duplicate native backup path: ${entry.name}" }
                    require(entries.size <= 2 + ConfluencePersonalFiles.MAX_VOICES * 3) { "too many native source entries" }
                }
                val indexEntry = requireNotNull(entries[INDEX]) { "native source archive has no index" }
                val indexBytes = zip.getInputStream(indexEntry).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    copyBudgeted(input, out, MAX_INDEX_BYTES, "native source index", budget)
                    out.toByteArray()
                }
                val index = Json.parse(indexBytes.toString(Charsets.UTF_8)).obj()
                require(index.keys == setOf("format", "schema", "xpnSha256", "files", "pads")) { "invalid native source index fields" }
                require(index.getValue("format").str() == FORMAT && index.getValue("schema").int() == 1) { "unsupported native source index" }
                require(index.getValue("xpnSha256").str() == XpnSourceMetadata.sha256(xpn)) { "native source archive does not match its rendered XPN" }
                val listed = index.getValue("files").arr()
                require(listed.size in 4..(1 + ConfluencePersonalFiles.MAX_VOICES * 3)) { "invalid native source file count" }
                val paths = LinkedHashSet<String>()
                var total = 0L
                // Read the kit's own metadata into a temporary name: it cannot replace the imported
                // sidecar until every sample/source dependency has been checked.
                var savedText: String? = null
                for (value in listed) {
                    val file = value.obj()
                    require(file.keys == setOf("path", "bytes", "sha256")) { "invalid native source file fields" }
                    val path = file.getValue("path").str()
                    require(ConfluencePersonalFiles.allowedPath(path) && paths.add(path)) { "unsafe or duplicate native source path: $path" }
                    val length = file.getValue("bytes").long()
                    val hash = file.getValue("sha256").str()
                    require(length in 1..ConfluencePersonalFiles.limit(path) && hash.matches(Regex("[0-9a-f]{64}"))) { "native source file exceeds its limit or has an invalid hash: $path" }
                    total += length
                    require(total <= ConfluencePersonalFiles.MAX_KIT_BYTES) { "native source closure exceeds its per-kit byte budget" }
                    val entry = requireNotNull(entries[path]) { "native source file is missing: $path" }
                    val destination = if (path == KitStore.FILE_NAME) File(staging, ".native-kit.json") else ConfluencePersonalFiles.child(staging, path)
                    require(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "cannot create native source directory" }
                    zip.getInputStream(entry).use { input -> destination.outputStream().use { out -> copyBudgeted(input, out, length, "native source $path", budget) } }
                    require(destination.length() == length && XpnSourceMetadata.sha256(destination) == hash) { "native source checksum or length differs: $path" }
                    if (path == KitStore.FILE_NAME) {
                        savedText = destination.readText(Charsets.UTF_8)
                        require(destination.delete()) { "cannot remove temporary native kit metadata" }
                    }
                }
                require(entries.keys == paths + INDEX) { "native source archive contains unlisted files" }
                val saved = KitStore.fromJsonText(requireNotNull(savedText) { "native source archive has no kit metadata" })
                require(saved.name == imported.name && saved.pads.map { it.slot }.toSet() == imported.pads.map { it.slot }.toSet()) { "native source kit differs from the rendered program" }
                val ids = ConfluencePersonalFiles.referencedIds(saved)
                require(ids.isNotEmpty()) { "native source kit has no personal voice references" }
                val expectedPaths = setOf(KitStore.FILE_NAME) + ids.flatMap { id -> listOf("voice.json", "raw.wav", "source.wav").map { "${ConfluencePersonalFiles.DIRECTORY}/$id/$it" } }
                require(paths == expectedPaths) { "native source files do not close the kit's personal voice references" }
                ConfluencePersonalFiles.files(saved, staging)
                val bindings = index.getValue("pads").arr()
                require(bindings.size == saved.pads.size) { "native sample binding count differs" }
                val seen = HashSet<Int>()
                for (value in bindings) {
                    val binding = value.obj()
                    require(binding.keys == setOf("slot", "sampleHashes")) { "invalid native sample binding fields" }
                    val slot = binding.getValue("slot").int()
                    require(seen.add(slot)) { "duplicate native sample binding" }
                    val pad = requireNotNull(imported.pad(slot)) { "native sample binding has an unknown slot" }
                    val history = requireNotNull(saved.pad(slot))
                    val expected = binding.getValue("sampleHashes").arr().map { it.str() }
                    require(expected.size == sampleFiles(history).size && expected == sampleFiles(pad).map { XpnSourceMetadata.sha256(SafePath.child(staging, it)) }) {
                        "native rendered sample binding differs at pad $slot"
                    }
                }
                // The source map contains the personal recipe and canonical dependency identity.
                // Other native recipes can require assets outside this closure; importing those
                // wrappers (or replaying already-printed treatments) would falsely revive history.
                // Keep XPN playback/settings/shape authoritative and restore only personal state.
                return imported.copy(pads = imported.pads.map { pad ->
                    val history = requireNotNull(saved.pad(pad.slot))
                    if (history.source.containsKey(ConfluencePersonalFiles.SOURCE_ID_KEY) || pad.source.containsKey(ConfluencePersonalFiles.SOURCE_ID_KEY)) {
                        require(history.source == pad.source) { "native personal metadata differs from its rendered XPN at pad ${pad.slot}" }
                        pad.copy(source = history.source)
                    } else pad
                })
            }
        } catch (e: JsonException) {
            throw IllegalArgumentException("unreadable native source archive: ${e.message}", e)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("incomplete native source archive: ${e.message}", e)
        }
    }

    private fun sampleFiles(pad: KitPad): List<String> = if (pad.velocityLayers.isEmpty()) listOf(pad.sampleFile) else pad.velocityLayers.map { it.sampleFile }

    /** Charge accepted chunks immediately; a failed parse/copy cannot replenish the shared budget. */
    fun copyBudgeted(input: InputStream, output: OutputStream, limit: Long, label: String, budget: XpnImporter.WriteBudget) {
        val allowed = minOf(limit, budget.remaining)
        var read = 0L
        val buffer = ByteArray(8192)
        while (read < allowed) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), allowed - read).toInt())
            if (count < 0) return
            budget.spend(count.toLong()); read += count
            output.write(buffer, 0, count)
        }
        if (input.read() >= 0) {
            budget.spend(1)
            throw LimitedRead.TooLargeException("$label exceeds its source limit or the shared backup write budget")
        }
    }
}
