package com.snipsnap.cli

import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.SongExport
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap arrange <kit-dir>` — songs, not loops. The Arranger's
 * structure grammar lays the kit's own variations into sections (the
 * user's own program among them, when the kit has one), and
 * the plan lands as **switchable sequences in an `.xpj`**, numbered in
 * section order — flip 01 upward on the hardware and that's the song.
 * The song slot wears the arrangement's name (steps stay bench-blocked,
 * GG3.2). `--seed N` rerolls the grammar's choices deterministically.
 */
object ArrangeCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--seed", "--out"),
            boolean = setOf("--overwrite", "--mixdown"),
        )
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("arrange wants a kit: snipsnap arrange <kit-dir> [--seed N] [--out DIR]")
        if (opts.positional.size > 1) throw CliError("arrange takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val seed = opts.int("--seed") ?: 0

        val kit = KitStore.load(kitDir)
        val plan = Arranger.arrange(kit, kitDir, seed)
        val written = try {
            SongExport.write(
                kit, kitDir, plan,
                destRoot = File(opts["--out"] ?: "snipsnap-out", "card"),
                overwrite = opts.has("--overwrite"),
                mixdown = opts.has("--mixdown"),
            )
        } catch (e: DestinationExists) {
            throw CliError("destination already exists: ${e.path} (pass --overwrite to replace it)")
        }

        out.println(
            "arrangement: \"${plan.name}\" (seed $seed) - ${plan.totalBars} bars in " +
                "${plan.sections.size} sections",
        )
        plan.sections.forEachIndexed { i, s ->
            val loops = if (s.repeats > 1) "  (${s.clip.bars}-bar pattern x${s.repeats})" else ""
            out.println("  %02d  %-10s %2d bars$loops".format(java.util.Locale.ROOT, i + 1, s.name, s.bars))
        }
        out.println("project: ${written.project.path}")
        out.println("  flip sequences 01..%02d in order on the hardware - that's the song".format(java.util.Locale.ROOT, plan.sections.size))

        written.mixdown?.let { (wav, mix) ->
            out.println(
                "mixdown: ${wav.path} (%.1fs - pull-up into the turn, tape stop on the outro%s)".format(java.util.Locale.ROOT, 
                    mix.snip.durationSeconds,
                    if (com.snipsnap.kit.AnswerStore.load(kitDir) != null) ", the Answer under the body" else "",
                ),
            )
        }
        return 0
    }
}
