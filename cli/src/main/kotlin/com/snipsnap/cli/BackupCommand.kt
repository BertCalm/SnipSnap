package com.snipsnap.cli

import com.snipsnap.kit.KitBackup
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap backup <kits-root>` / `snipsnap restore <backup.zip>` —
 * every kit as an `.xpn` inside one archive, and back again.
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
        val result = KitBackup.backup(root, outFile, opts.has("--overwrite"))
        out.println("backed up ${result.packed.size} kit(s) into ${result.file.path}")
        result.packed.forEach { out.println("  + $it") }
        result.skipped.forEach { (name, why) -> out.println("  ! skipped $name - $why") }
        return 0
    }

    fun restore(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--overwrite"))
        val fileArg = opts.positional.firstOrNull()
            ?: throw CliError("restore wants a backup: snipsnap restore <backup.zip>")
        if (opts.positional.size > 1) throw CliError("restore takes one backup file")
        val file = File(fileArg)
        if (!file.isFile) throw CliError("no such file: $fileArg")

        val results = KitBackup.restore(file, File(opts["--out"] ?: "snipsnap-out"), opts.has("--overwrite"))
        out.println("restored ${results.size} kit(s):")
        results.forEach { out.println("  + ${it.kit.name} -> ${it.directory.path}") }
        return 0
    }
}
