package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap remix <kit-dir>` — EVIL TWINS: bank B becomes seeded FX
 * re-treatments of bank A. Reroll with a different `--seed`.
 */
object RemixCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--seed"), boolean = emptySet())
        val dirArg = opts.positional.firstOrNull()
            ?: throw CliError("remix wants a kit folder: snipsnap remix <kit-dir> [--seed N]")
        if (opts.positional.size > 1) throw CliError("remix takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val seed = opts.int("--seed") ?: 1
        val model = KitBuilderModel.open(kitDir)
        val slots = model.remixBankB(seed)
        model.save()

        out.println(
            "bank B: ${slots.size} evil twins (seed $seed) on " +
                slots.joinToString(" ") { PadNoteMap.labelForPad(it) },
        )
        out.println("(reroll: snipsnap remix \"$dirArg\" --seed ${seed + 1}; then re-export)")
        return 0
    }
}
