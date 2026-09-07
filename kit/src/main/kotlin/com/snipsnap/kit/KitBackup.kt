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

    fun backup(kitsRoot: File, outFile: File, overwrite: Boolean = false): BackupResult {
        val kitDirs = KitStore.list(kitsRoot)
        require(kitDirs.isNotEmpty()) { "no kits under $kitsRoot" }
        if (outFile.exists() && !overwrite) {
            throw IOException("destination already exists: $outFile (pass overwrite=true to replace same-named files)")
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

    fun restore(backupFile: File, destRoot: File, overwrite: Boolean = false): List<XpnImporter.ImportResult> {
        require(backupFile.isFile) { "no such file: $backupFile" }
        val temp = java.nio.file.Files.createTempDirectory("kitrestore").toFile()
        try {
            val results = mutableListOf<XpnImporter.ImportResult>()
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
                    require(names.add(e.name)) { "$backupFile holds '${e.name}' twice - refused" }
                    require(names.size <= MAX_KITS) { "$backupFile declares more than $MAX_KITS kits - refused" }
                }
                require(names.isNotEmpty()) { "no .xpn kits inside $backupFile - not a SnipSnap backup?" }
                for (name in names.sorted()) {
                    val entry = zip.getEntry(name) ?: throw IllegalArgumentException("$backupFile lost '$name' between listing and reading")
                    val xpn = File(temp, File(entry.name).name)
                    zip.getInputStream(entry).use { src ->
                        xpn.outputStream().use {
                            com.snipsnap.mpc3.LimitedRead.copy(src, it, what = "backup entry ${entry.name}")
                        }
                    }
                    results += XpnImporter.import(xpn, destRoot, overwrite)
                }
            }
            return results
        } finally {
            temp.deleteRecursively()
        }
    }
}
