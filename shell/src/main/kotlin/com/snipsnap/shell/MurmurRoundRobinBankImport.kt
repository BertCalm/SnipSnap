package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

/** SAF supplies bytes, never filesystem paths. Only a complete saved finite RR bank is admitted.
 * Blocking IO: caller owns the input stream; this function owns and removes only its private stage.
 * ZIP Unix link attributes are not materialized: every extracted payload is a newly created regular
 * file, then the existing shelf verifies the complete canonical bank/recipe/WAV/hash closure.
 */
object MurmurRoundRobinBankImport {
    const val MAX_INPUT_BYTES = MurmurRoundRobinShelf.MAX_BANK_BYTES + 1024 * 1024
    const val REQUIRED_FREE_BYTES = 2 * MurmurRoundRobinShelf.MAX_BANK_BYTES +
        MurmurRoundRobinShelf.MIN_REMAINING_FREE_BYTES + 1024 * 1024
    internal data class Limits(val raw: Long = MAX_INPUT_BYTES,
                               val expanded: Long = MurmurRoundRobinShelf.MAX_BANK_BYTES,
                               val files: Int = 128, val entries: Int = 160) {
        init { require(raw in 1..MAX_INPUT_BYTES && expanded in 1..MurmurRoundRobinShelf.MAX_BANK_BYTES &&
            files in 1..128 && entries in 1..160) }
    }
    data class Imported(val saved: MurmurRoundRobinShelf.SavedBank, val stagingCleaned: Boolean)
    fun importZip(input: InputStream, cache: File, shelf: File,
                  cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Imported =
        importUsing(input, cache, shelf, cancelled, progress, Limits())

    internal fun importUsing(input: InputStream, cache: File, shelf: File,
                             cancelled: () -> Boolean, progress: (Int, Int) -> Unit, limits: Limits, cleanup: (File) -> Boolean = { it.deleteRecursively() }): Imported {
        stop(cancelled)
        require(cache.absoluteFile == cache.canonicalFile && Files.isDirectory(cache.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Import staging must be a real private directory"
        }
        // Both extracted source and admission copy coexist until the atomic shelf move.
        require(cache.usableSpace >= REQUIRED_FREE_BYTES) { "Not enough space to import this RR bank" }
        val stage = Files.createTempDirectory(cache.toPath(), ".murmur-rr-import-").toFile()
        val saved = try {
            unpack(input, stage, cancelled, limits)
            stop(cancelled)
            val manifest = File(stage, MurmurRoundRobinInstrumentBank.MANIFEST)
            require(manifest.isFile && manifest.length() in 1..65536) { "A complete RR bank manifest is required" }
            val obj = Json.parse(manifest.readText()).obj()
            val name = (obj["name"] as? JsonValue.Str)?.value ?: error("RR bank name is missing")
            require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH - 3 && !name.startsWith(".")) {
                "Invalid RR bank name"
            }
            stop(cancelled)
            // Reuses full source/hash/PCM validation, collision refusal and one atomic no-replace move.
            // Once admitSaved returns, a late cancellation must not undo or misreport that success.
            MurmurRoundRobinShelf.admitSaved(name, stage, shelf, cancelled, progress)
        } catch (failure: Throwable) {
            try { check(cleanup(stage)) { "Could not remove private RR import staging" } }
            catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
        // Publication already committed. Cleanup failure is a warning, never a false import failure.
        val cleaned = try { cleanup(stage) } catch (_: Exception) { false }
        return Imported(saved, cleaned)
    }

    private fun unpack(input: InputStream, root: File, cancelled: () -> Boolean, limits: Limits) {
        var rawBytes = 0L
        val bounded = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stop(cancelled)
                val count = input.read(b, off, minOf(len.toLong(), limits.raw - rawBytes + 1).toInt())
                if (count > 0) { rawBytes += count; require(rawBytes <= limits.raw) { "RR ZIP input exceeds its byte limit" } }
                return count
            }
            override fun close() {} // The SAF adapter/caller owns its stream even on refusal.
        }
        var total = 0L; var files = 0; var entries = 0
        val names = mutableSetOf<String>()
        ZipInputStream(bounded).use { zip ->
            while (true) {
                stop(cancelled)
                val entry = zip.nextEntry ?: break
                require(++entries <= limits.entries) { "RR ZIP contains too many entries" }
                val name = entry.name.removeSuffix("/")
                val parts = name.split('/')
                require(name.length in 1..256 && parts.size <= 4 && parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it || it.any { c -> c.code < 32 || c.code == 127 } }) {
                    "RR ZIP contains an unsafe path"
                }
                require(names.add(name)) { "RR ZIP contains duplicate entries" }
                require(parts.first() in listOf("take-1", "take-2", "take-3", "take-4") ||
                    (parts.size == 1 && name == MurmurRoundRobinInstrumentBank.MANIFEST && !entry.isDirectory)) {
                    "RR ZIP must contain the bank manifest and four take folders at its root"
                }
                val target = File(root, name)
                require(target.toPath().normalize().startsWith(root.toPath()))
                if (entry.isDirectory) {
                    require(zip.read() == -1) { "RR ZIP directory entries must not contain data" }
                    require(!target.exists() || target.isDirectory) { "RR ZIP path collision" }
                    require(target.isDirectory || target.mkdirs())
                } else {
                    require(++files <= limits.files && (parts.size > 1 || name == MurmurRoundRobinInstrumentBank.MANIFEST))
                    require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "RR ZIP path collision" }
                    require(target.parentFile.isDirectory || target.parentFile.mkdirs())
                    val max = if (name == MurmurRoundRobinInstrumentBank.MANIFEST) 65536L
                        else if (name.endsWith(".wav")) limits.expanded else minOf(limits.expanded, 8L * 1024 * 1024)
                    var size = 0L
                    Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            stop(cancelled)
                            val n = zip.read(buffer, 0, minOf(buffer.size.toLong(), max - size + 1, limits.expanded - total + 1).toInt())
                            if (n < 0) break
                            size += n; total += n
                            require(size <= max && total <= limits.expanded) { "RR ZIP expanded data exceeds its byte limit" }
                            output.write(buffer, 0, n)
                        }
                    }
                    require(size > 0) { "RR bank files must not be empty" }
                }
                zip.closeEntry()
            }
        }
        require(files > 0) { "RR ZIP is empty or unreadable" }
    }
    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("RR bank import cancelled")
    }
}
