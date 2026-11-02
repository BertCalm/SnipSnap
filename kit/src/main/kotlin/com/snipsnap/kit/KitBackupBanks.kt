package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A bank-only backup sidecar. Personal sources keep their existing, separately versioned format. */
internal object KitBackupBanks {
    const val PREFIX = ".snipsnap-banks/"
    const val MAX_ARCHIVE_BYTES = ConfluenceBankFiles.MAX_KIT_BYTES + 4L * 1024 * 1024
    private const val INDEX = "banks.json"
    private const val MAX_INDEX_BYTES = 256L * 1024
    private const val MAX_FILES = ConfluenceBankFiles.MAX_BANKS * (ConfluenceBankFiles.MAX_ASSETS + 1)
    private const val FORMAT = "snipsnap-native-selected-banks"

    fun entryName(xpnName: String): String = "$PREFIX$xpnName.zip"

    fun write(kit: Kit, kitDir: File, xpn: File, output: File): File? {
        if (ConfluenceBankFiles.fingerprints(kit).isEmpty()) return null
        ConfluenceBankFiles.requireImportable(kit, kitDir)
        val files = ConfluenceBankFiles.files(kit, kitDir)
        val index = JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str(FORMAT), "schema" to JsonValue.Num(1.0),
            "xpnSha256" to JsonValue.Str(XpnSourceMetadata.sha256(xpn)),
            "files" to JsonValue.Arr(files.map { (path, file) -> JsonValue.Obj(linkedMapOf(
                "path" to JsonValue.Str(path), "bytes" to JsonValue.Num(file.length().toDouble()),
                "sha256" to JsonValue.Str(XpnSourceMetadata.sha256(file)),
            )) }),
            "pads" to JsonValue.Arr(kit.pads.filter { it.source["engine"] == ConfluenceBankFiles.ENGINE }.sortedBy { it.slot }.map { pad ->
                JsonValue.Obj(linkedMapOf("slot" to JsonValue.Num(pad.slot.toDouble()),
                    "sourceSha256" to JsonValue.Str(sourceHash(pad)),
                    "sampleHashes" to JsonValue.Arr(samples(pad).map { JsonValue.Str(XpnSourceMetadata.sha256(SafePath.child(kitDir, it))) })))
            }),
        ))
        val bytes = (Json.write(index) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_BYTES) { "selected-bank backup index exceeds its byte budget" }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun put(name: String, body: () -> Unit) {
                zip.putNextEntry(ZipEntry(name).also { it.time = 1_577_836_800_000L }); body(); zip.closeEntry()
            }
            put(INDEX) { zip.write(bytes) }
            for ((path, file) in files) put(path) { file.inputStream().use { it.copyTo(zip) } }
        }
        require(output.length() <= MAX_ARCHIVE_BYTES) { "selected-bank archive exceeds its byte budget" }
        return output
    }

    /** Everything lands inside XpnImporter's staging directory before a destination can change. */
    fun restore(archive: File, xpn: File, kit: Kit, staging: File, budget: XpnImporter.WriteBudget): Kit {
        try {
            ZipFile(archive).use { zip ->
                val entries = LinkedHashMap<String, ZipEntry>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    require(!entry.isDirectory && (entry.name == INDEX || ConfluenceBankFiles.allowedPath(entry.name))) { "unsafe selected-bank backup path: ${entry.name}" }
                    require(entries.put(entry.name, entry) == null) { "duplicate selected-bank backup path: ${entry.name}" }
                    require(entries.size <= MAX_FILES + 1) { "too many selected-bank backup entries" }
                }
                val indexEntry = requireNotNull(entries[INDEX]) { "selected-bank backup index is missing" }
                val indexBytes = java.io.ByteArrayOutputStream().also { out -> zip.getInputStream(indexEntry).use {
                    KitBackupNative.copyBudgeted(it, out, MAX_INDEX_BYTES, "selected-bank backup index", budget)
                } }.toByteArray()
                val index = Json.parse(indexBytes.toString(Charsets.UTF_8)).obj()
                require(index.keys == setOf("format", "schema", "xpnSha256", "files", "pads") &&
                    index.getValue("format").str() == FORMAT && index.getValue("schema").int() == 1) { "unsupported selected-bank backup index" }
                require(index.getValue("xpnSha256").str() == XpnSourceMetadata.sha256(xpn)) { "selected-bank backup differs from its rendered XPN" }
                val files = index.getValue("files").arr()
                require(files.size in 4..MAX_FILES) { "invalid selected-bank backup file count" }
                val paths = HashSet<String>()
                var total = 0L
                for (value in files) {
                    val file = value.obj()
                    require(file.keys == setOf("path", "bytes", "sha256")) { "invalid selected-bank backup file fields" }
                    val path = file.getValue("path").str()
                    val length = file.getValue("bytes").long()
                    val hash = file.getValue("sha256").str()
                    require(ConfluenceBankFiles.allowedPath(path) && paths.add(path)) { "unsafe or duplicate selected-bank source path: $path" }
                    require(length in 1..ConfluenceBankFiles.limit(path) && hash.matches(Regex("[0-9a-f]{64}"))) { "selected-bank backup file exceeds its bound" }
                    total += length
                    require(total <= ConfluenceBankFiles.MAX_KIT_BYTES - ConfluencePersonalFiles.KIT_METADATA_RESERVE_BYTES) { "selected-bank backup closure exceeds its byte budget" }
                    val entry = requireNotNull(entries[path]) { "selected-bank backup asset is missing: $path" }
                    val destination = ConfluenceBankFiles.child(staging, path)
                    require(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "cannot stage selected-bank source" }
                    zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                        KitBackupNative.copyBudgeted(input, output, length, "selected-bank source $path", budget)
                    } }
                    require(destination.length() == length && XpnSourceMetadata.sha256(destination) == hash) { "selected-bank backup checksum differs: $path" }
                }
                require(entries.keys == paths + INDEX) { "selected-bank backup contains unlisted assets" }
                val expected = ConfluenceBankFiles.files(kit, staging).map { it.first }.toSet()
                require(expected.isNotEmpty() && paths == expected) { "selected-bank backup does not close its pad source references" }
                val pads = index.getValue("pads").arr()
                val bankPads = kit.pads.filter { it.source["engine"] == ConfluenceBankFiles.ENGINE }.associateBy { it.slot }
                require(pads.size == bankPads.size) { "selected-bank pad binding count differs" }
                val seen = HashSet<Int>()
                for (value in pads) {
                    val binding = value.obj()
                    require(binding.keys == setOf("slot", "sourceSha256", "sampleHashes")) { "invalid selected-bank pad binding" }
                    val slot = binding.getValue("slot").int()
                    val pad = requireNotNull(bankPads[slot]) { "unknown selected-bank pad binding" }
                    require(seen.add(slot) && binding.getValue("sourceSha256").str() == sourceHash(pad) &&
                        binding.getValue("sampleHashes").arr().map { it.str() } == samples(pad).map { XpnSourceMetadata.sha256(SafePath.child(staging, it)) }) {
                        "selected-bank rendered pad/source binding differs at pad $slot"
                    }
                }
                return kit
            }
        } catch (e: JsonException) { throw IllegalArgumentException("unreadable selected-bank backup index: ${e.message}", e) }
        catch (e: NoSuchElementException) { throw IllegalArgumentException("incomplete selected-bank backup index: ${e.message}", e) }
    }

    private fun samples(pad: KitPad) = if (pad.velocityLayers.isEmpty()) listOf(pad.sampleFile) else pad.velocityLayers.map { it.sampleFile }
    private fun sourceHash(pad: KitPad): String {
        val bytes = Json.write(JsonValue.Obj(pad.source.toSortedMap().mapValues { JsonValue.Str(it.value) })).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
