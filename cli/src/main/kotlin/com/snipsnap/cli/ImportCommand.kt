package com.snipsnap.cli

import com.snipsnap.kit.XpnImporter
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap import <file.xpn>` — the receive half of one-file kit
 * sharing: an archive becomes a kit folder, ready to edit or re-export.
 */
object ImportCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--overwrite"))
        val input = opts.positional.firstOrNull()
            ?: throw CliError("import wants an archive: snipsnap import <file.xpn>")
        if (opts.positional.size > 1) {
            throw CliError("import takes one archive, got ${opts.positional.size}")
        }
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        val result = XpnImporter.import(file, File(opts["--out"] ?: "snipsnap-out"), opts.has("--overwrite"))
        out.println("imported '${result.programEntry}' from ${file.name}")
        out.println("kit folder: ${result.directory.path} (${result.kit.pads.size} pads)")
        out.println("(edit it, or re-export: snipsnap export \"${result.directory.path}\" --export ...)")
        return 0
    }
}
