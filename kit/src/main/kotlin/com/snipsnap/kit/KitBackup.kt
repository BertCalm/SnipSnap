package com.snipsnap.kit

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Everything on one file: every kit under a root packed as its own
 * `.xpn` inside a single archive, restored back through [XpnImporter].
 * Retention insurance, and the "new phone" story.
 *
 * A kit preflight refuses to pack isn't silently lost — it's skipped
 * **and named**, with the reason, in the result. Backups never pretend.
 */
object KitBackup {

    /**
     * The most `.xpn` entries a backup may hold before [restore] refuses
     * it: a shelf has tens of kits, and a backup shared in from a stranger
     * may declare anything.
     */
    const val MAX_KITS = 10_000

    data class BackupResult(
        val file: File,
        /** Kit names that made it in. */
        val packed: List<String>,
        /** Kit name → the blocking reason, for kits that didn't. */
        val skipped: Map<String, String>,
    )

    /**
     * What [restore] produced. Mirrors [BackupResult]'s shape on the way
     * back in: kits that landed, and - keyed by the backup entry's `.xpn`
     * name, already enforced unique by [restore]'s own duplicate check -
     * the reason for any that didn't.
     */
    data class RestoreResult(
        val kits: List<XpnImporter.ImportResult>,
        /** Backup entry name (the `.xpn`) → the reason it didn't restore. */
        val skipped: Map<String, String>,
    )

    fun backup(kitsRoot: File, outFile: File, overwrite: Boolean = false): BackupResult {
        val kitDirs = KitStore.list(kitsRoot)
        require(kitDirs.isNotEmpty()) { "no kits under $kitsRoot" }
        if (outFile.exists() && !overwrite) {
            throw DestinationExists(outFile)
        }
        outFile.parentFile?.mkdirs()

        val packed = mutableListOf<String>()
        val skipped = linkedMapOf<String, String>()
        val temp = java.nio.file.Files.createTempDirectory("kitbackup").toFile()
        try {
            ZipOutputStream(outFile.outputStream()).use { zip ->
                for (dir in kitDirs) {
                    val kit = KitStore.load(dir)
                    val findings = Preflight.check(kit, dir)
                    if (findings.blocked()) {
                        skipped[kit.name] = findings.first { it.severity == Severity.FAIL }.message
                        continue
                    }
                    val xpn = File(temp, "${kit.name}.xpn")
                    XpnPackager.write(kit, dir, xpn, Exporters.defaultMeta(kit), overwrite = true)
                    zip.putNextEntry(ZipEntry("${kit.name}.xpn"))
                    xpn.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    packed += kit.name
                }
            }
        } finally {
            temp.deleteRecursively()
        }
        require(packed.isNotEmpty()) {
            "every kit was blocked by preflight: " + skipped.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }
        return BackupResult(outFile, packed, skipped)
    }

    /**
     * Restores every kit from [backupFile] into [destRoot] through
     * [XpnImporter]. An entry that fails to parse or import (a corrupt
     * `.xpn`, one the writer and reader disagree about) is skipped **and
     * named**, with the reason, in [RestoreResult.skipped] — the same
     * shape [backup]'s own preflight refusals already take — so one bad
     * kit never costs every OTHER kit in the backup. Only when *every*
     * entry fails this way does [restore] itself throw, naming all of
     * them: a restore that landed nothing is a refusal, not a silent
     * no-op an exit-code-only caller would miss.
     */
    fun restore(
        backupFile: File,
        destRoot: File,
        overwrite: Boolean = false,
        // One budget for the whole restore, not per kit: MAX_KITS alone
        // bounds entry count, not bytes, and each kit's own sample writes
        // are only capped individually inside XpnImporter - a backup
        // declaring many large kits would otherwise multiply that ceiling
        // by however many it holds. Shared across both the .xpn blobs
        // pulled out of this ZIP below and the samples each one unpacks.
        // Overridable for tests, the same way ShelfImport.unzipSafely's
        // maxBytes is - real callers keep the default.
        budget: XpnImporter.WriteBudget = XpnImporter.WriteBudget(XpnImporter.DEFAULT_BUDGET_BYTES),
    ): RestoreResult {
        require(backupFile.isFile) { "no such file: $backupFile" }
        val temp = java.nio.file.Files.createTempDirectory("kitrestore").toFile()
        try {
            val results = mutableListOf<XpnImporter.ImportResult>()
            val skipped = linkedMapOf<String, String>()
            ZipFile(backupFile).use { zip ->
                // One lazy pass keeping only the .xpn names, never every entry
                // the archive declares: a backup may come from a stranger.
                val names = LinkedHashSet<String>()
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val e = all.nextElement()
                    if (e.isDirectory || !e.name.endsWith(".xpn", ignoreCase = true)) continue
                    // A name twice is a crafted archive, not a backup: which of
                    // the two would be the kit? Refuse rather than guess.
                    require(names.add(e.name)) { "${backupFile.name} holds '${e.name}' twice - refused" }
                    require(names.size <= MAX_KITS) { "${backupFile.name} declares more than $MAX_KITS kits - refused" }
                }
                require(names.isNotEmpty()) { "no .xpn kits inside ${backupFile.name} - not a SnipSnap backup?" }
                for (name in names.sorted()) {
                    val entry = zip.getEntry(name) ?: throw IllegalArgumentException("${backupFile.name} lost '$name' between listing and reading")
                    val xpn = File(temp, File(entry.name).name)
                    try {
                        zip.getInputStream(entry).use { src ->
                            xpn.outputStream().use {
                                com.snipsnap.mpc3.LimitedRead.copy(src, it, limit = budget.remaining, what = "backup entry ${entry.name}")
                            }
                        }
                    } catch (e: com.snipsnap.mpc3.LimitedRead.TooLargeException) {
                        // copy() throws before writing the chunk that would
                        // overrun, but earlier chunks already landed - this
                        // still aborts the whole restore on the first such
                        // throw (charging the partial write first, so it
                        // isn't left behind). Unlike an unreadable entry
                        // below, this is deliberate, not a gap to close:
                        // the budget is shared across the whole restore and
                        // spend only shrinks it, so skipping this entry and
                        // continuing would just let every later entry blow
                        // the same already-exhausted budget too.
                        val partial = xpn.length()
                        xpn.delete()
                        budget.spend(partial)
                        throw com.snipsnap.mpc3.LimitedRead.TooLargeException(
                            "'${backupFile.name}' writes past ${budget.max / (1024 * 1024)} MB at '${entry.name}' - refused",
                        )
                    }
                    budget.spend(xpn.length())
                    try {
                        results += XpnImporter.import(xpn, destRoot, overwrite, budget)
                    } catch (e: com.snipsnap.mpc3.LimitedRead.TooLargeException) {
                        // Same reasoning as the extraction catch above - a
                        // blown shared budget is a whole-restore condition,
                        // not this one entry's problem, so it is not caught
                        // by the broader catch below even though
                        // TooLargeException is an IllegalArgumentException.
                        throw e
                    } catch (e: IllegalArgumentException) {
                        // Mirrors XpnImporter.importAll's own per-program
                        // degrade: one entry that fails to parse (a corrupt
                        // .xpn, a keygroup program, a program the SafeXml
                        // bug or any other reader-vs-writer disagreement
                        // made unreadable) must not cost every OTHER kit in
                        // the backup - the same "skipped and named" shape
                        // backup() already gives a blocked kit, not the
                        // all-or-nothing this used to be.
                        skipped[name] = e.message ?: "could not be restored"
                    } catch (e: java.util.zip.ZipException) {
                        // A .xpn that isn't a readable zip at all - not an
                        // IllegalArgumentException, but exactly as much "one
                        // bad entry" as a program that fails to parse.
                        skipped[name] = e.message ?: "not a readable .xpn"
                    }
                }
            }
            // Same shape as backup()'s own "every kit was blocked" guard:
            // every entry skipping is not a restore that quietly did
            // nothing, it's a refusal - a caller that only checks an exit
            // code (BackupCommand, a script) must see one.
            require(results.isNotEmpty()) {
                "every kit in '${backupFile.name}' failed to restore: " + skipped.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            }
            return RestoreResult(results, skipped)
        } finally {
            temp.deleteRecursively()
        }
    }
}
