package com.snipsnap.cli

import com.snipsnap.kit.KitBackup
import com.snipsnap.shell.UserPresets
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap backup <kits-root>` / `snipsnap restore <backup.zip>` —
 * every kit as an `.xpn` inside one archive, and back again. The
 * player's presets file at the root rides along when there is one, and
 * comes home through the same merge the phone's shelf uses.
 */
object BackupCommand {

    fun backup(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--overwrite"))
        val rootArg = opts.positional.firstOrNull()
            ?: throw CliError("backup wants a folder of kits: snipsnap backup <kits-root>")
        if (opts.positional.size > 1) throw CliError("backup takes one folder")
        val root = File(rootArg)
        if (!root.isDirectory) throw CliError("no such folder: $rootArg")

        val outFile = File(opts["--out"] ?: "SnipSnap_Backup.zip")
        val result = KitBackup.backup(root, outFile, opts.has("--overwrite"), extras = mapOf(UserPresets.FILE_NAME to UserPresets.file(root)))
        out.println("backed up ${result.packed.size} kit(s) into ${result.file.path}")
        result.packed.forEach { out.println("  + $it") }
        result.skipped.forEach { (name, why) -> out.println("  ! skipped $name - $why") }
        if (result.extras.isNotEmpty()) out.println("  + ${UserPresets.FILE_NAME} (${UserPresets.read(root).size} preset(s))")
        return 0
    }

    fun restore(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--overwrite"))
        val fileArg = opts.positional.firstOrNull()
            ?: throw CliError("restore wants a backup: snipsnap restore <backup.zip>")
        if (opts.positional.size > 1) throw CliError("restore takes one backup file")
        val file = File(fileArg)
        if (!file.isFile) throw CliError("no such file: $fileArg")

        val dest = File(opts["--out"] ?: "snipsnap-out")
        val result = KitBackup.restore(file, dest, opts.has("--overwrite"))
        out.println("restored ${result.kits.size} kit(s):")
        result.kits.forEach { out.println("  + ${it.kit.name} -> ${it.directory.path}") }
        result.skipped.forEach { (name, why) -> out.println("  ! skipped $name - $why") }
        // After the kits, and never in their way: a presets entry that is
        // not one, or weighs too much, is skipped and named - the phone's
        // own rule for a backup coming home.
        try {
            KitBackup.extra(file, UserPresets.FILE_NAME)?.let { bytes ->
                val merged = UserPresets.merge(dest, bytes.toString(Charsets.UTF_8), System.currentTimeMillis())
                out.println("restored ${merged.landed.size} preset(s) into ${UserPresets.file(dest).path}" + if (merged.identical > 0) " (${merged.identical} already there)" else "")
            }
        } catch (e: Exception) {
            out.println("  ! skipped ${UserPresets.FILE_NAME} - ${e.message ?: "refused"}")
        }
        return 0
    }
}
