package com.snipsnap.cli

import com.snipsnap.mpc3.MpcDiff
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap diff <a> <b>` — the corpus guard as a bench tool: a structured
 * key-path diff of two MPC files, either generation (or bare JSON). This
 * is the whole "why won't this file load" workflow: point it at our export
 * and the firmware's own save, read the deltas.
 *
 * Exit 0 when the files agree, 1 when they differ — scriptable, like any
 * honest diff.
 */
object DiffCommand {

    /** Past this many value lines the listing stops counting out loud. */
    const val MAX_LISTED = 200

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = emptySet(), boolean = setOf("--values"))
        if (opts.positional.size != 2) {
            throw CliError("diff wants two files: snipsnap diff <a> <b> [--values]")
        }
        val (a, b) = opts.positional.map {
            File(it).also { f -> if (!f.isFile) throw CliError("no such file: $it") }
        }

        out.println("A: ${a.name}  (${MpcDiff.formatLabel(a)})")
        out.println("B: ${b.name}  (${MpcDiff.formatLabel(b)})")
        val result = MpcDiff.diff(a, b)
        if (result.identical) {
            out.println("same structure, same values")
            return 0
        }

        for ((label, paths) in listOf("A" to result.onlyInA, "B" to result.onlyInB)) {
            if (paths.isEmpty()) continue
            out.println()
            out.println("only in $label (${paths.size}):")
            paths.sorted().forEach { out.println("  $it") }
        }

        if (result.valueDiffs.isNotEmpty()) {
            out.println()
            if (opts.has("--values")) {
                out.println("values differ (${result.valueDiffs.size}):")
                val sorted = result.valueDiffs.sortedBy { it.path }
                sorted.take(MAX_LISTED).forEach {
                    out.println("  ${it.path}: ${it.a} -> ${it.b}")
                }
                if (sorted.size > MAX_LISTED) {
                    out.println("  ... and ${sorted.size - MAX_LISTED} more")
                }
            } else {
                out.println("values differ at ${result.valueDiffs.size} paths (--values lists them)")
            }
        }
        return 1
    }
}
