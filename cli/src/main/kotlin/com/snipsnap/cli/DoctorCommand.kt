package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.MixDoctor
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap doctor <kit-dir>` — preflight's musical sibling: it checks
 * the *sound*, not the format. Findings are measurements with pad names
 * and numbers; `--fix` applies only the safe subset (low carve, level
 * trim, mute-group, DC high-pass), bin-backed like every treatment.
 * Exit 0 when healthy, 1 while findings remain — a check you can script.
 */
object DoctorCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = emptySet(), boolean = setOf("--fix"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("doctor wants a kit: snipsnap doctor <kit-dir> [--fix]")
        if (opts.positional.size > 1) throw CliError("doctor takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val kit = KitStore.load(kitDir)
        val findings = MixDoctor.examine(kit, kitDir)
        if (findings.isEmpty()) {
            out.println("${kit.name}: healthy - nothing the doctor would touch")
            return 0
        }

        out.println("${kit.name}: ${findings.size} finding(s)")
        findings.forEach {
            out.println("  [${if (it.fixable) "fixable" else "advice "}] ${it.message}")
        }

        if (!opts.has("--fix")) {
            out.println("(--fix applies the fixable ones - carves, trims, and groups are bin-backed or reversible edits)")
            return 1
        }

        val model = KitBuilderModel.open(kitDir)
        val fixed = MixDoctor.fix(model)
        model.save()
        fixed.forEach { out.println("  fixed: ${it.message}") }

        val remaining = MixDoctor.examine(model.kit, kitDir)
        return if (remaining.isEmpty()) {
            out.println("${model.name}: healthy now")
            0
        } else {
            out.println("${remaining.size} finding(s) remain:")
            remaining.forEach { out.println("  [${if (it.fixable) "fixable" else "advice "}] ${it.message}") }
            1
        }
    }
}
