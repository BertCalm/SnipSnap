package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.mpc3.Mpc3Song
import com.snipsnap.shell.Arranger
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap arrange <kit-dir>` — songs, not loops. The Arranger's
 * structure grammar lays the kit's own variations into sections, and
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

        // Sequence names carry the order: flipping 01.. IS the arrangement.
        val sectionClips = plan.sections.mapIndexed { i, s ->
            s.clip.copy(name = "%02d %s".format(java.util.Locale.ROOT, i + 1, s.name))
        }

        val destRoot = File(opts["--out"] ?: "snipsnap-out", "card")
        val overwrite = opts.has("--overwrite")
        val dataDir = File(destRoot, Mpc3ProjectWriter.projectDataDirName(plan.name))
        val xpjDest = File(destRoot, "${plan.name}.xpj")
        if ((xpjDest.exists() || dataDir.exists()) && !overwrite) {
            throw CliError("destination already exists: $xpjDest (pass --overwrite to replace it)")
        }
        dataDir.deleteRecursively()
        val program = Mpc3Exporter.stageTrack(kit, kitDir, dataDir)
        val writer = Mpc3ProjectWriter()
        val tracks = listOf(Mpc3ProjectTrack.Drum(program, clips = sectionClips))
        val song = Mpc3Song(plan.name)
        val tempo = kit.tempoBpm
        val xpj = if (tempo != null) {
            writer.writeTo(destRoot, plan.name, tracks, tempo, song = song)
        } else {
            writer.writeTo(destRoot, plan.name, tracks, song = song)
        }

        out.println(
            "arrangement: \"${plan.name}\" (seed $seed) - ${plan.totalBars} bars in " +
                "${plan.sections.size} sections",
        )
        plan.sections.forEachIndexed { i, s ->
            val loops = if (s.repeats > 1) "  (${s.clip.bars}-bar pattern x${s.repeats})" else ""
            out.println("  %02d  %-10s %2d bars$loops".format(java.util.Locale.ROOT, i + 1, s.name, s.bars))
        }
        out.println("project: ${xpj.path}")
        out.println("  flip sequences 01..%02d in order on the hardware - that's the song".format(java.util.Locale.ROOT, plan.sections.size))

        if (opts.has("--mixdown")) {
            val mix = Arranger.mixdown(kit, kitDir, plan)
            val wav = File(destRoot, "${plan.name}.wav")
            if (wav.exists() && !overwrite) {
                throw CliError("destination already exists: $wav (pass --overwrite to replace it)")
            }
            com.snipsnap.audio.WavWriter.write(wav, mix.snip)
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
