package com.snipsnap.cli

import com.snipsnap.shell.Lineage
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap lineage <kit-dir> [--root <crate>] [--png]` — the kit's
 * family tree, walked from the provenance every verb already stamps:
 * resample generations, merge parents, dig songs and timestamps, chop
 * files, imports. `--root` names the crate the parents live in
 * (default: the kit's own parent folder); `--png` also renders the
 * tree as a card beside the kit.
 */
object LineageCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--root", "--out"), boolean = setOf("--png"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("lineage wants a kit: snipsnap lineage <kit-dir> [--root <crate>] [--png]")
        if (opts.positional.size > 1) throw CliError("lineage takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val root = opts["--root"]?.let {
            File(it).also { r -> if (!r.isDirectory) throw CliError("no such crate root: $it") }
        }

        val tree = Lineage.trace(kitDir, root)
        out.print(Lineage.render(tree))

        if (opts.has("--png")) {
            val dest = File(opts["--out"]?.let(::File) ?: kitDir, "${kitDir.name}-lineage.png")
            Lineage.renderPng(tree, dest)
            out.println()
            out.println("card: ${dest.path}")
        }
        return 0
    }
}
