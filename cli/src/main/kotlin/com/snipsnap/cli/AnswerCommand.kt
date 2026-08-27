package com.snipsnap.cli

import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Answer
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap answer <kit-dir>` — chop a break, get the B-side: an S5
 * bassline in the kit's detected key, playing a counter-pattern in the
 * groove's gaps with the groove's own feel. The result lives beside the
 * kit (`answer.json` + the rendered bass note) and `project` picks it up
 * as a keys track with its clip — one `.xpj` where the break plays with
 * a bassline that fits.
 */
object AnswerCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--seed"), boolean = emptySet())
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("answer wants a kit: snipsnap answer <kit-dir> [--seed N]")
        if (opts.positional.size > 1) throw CliError("answer takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val kit = KitStore.load(kitDir)
        val groove = GrooveStore.load(kitDir).firstOrNull() ?: KitPreview.defaultPattern(kit)
        val seed = opts.int("--seed") ?: 1

        val derived = try {
            Answer.derive(kit, groove, seed)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "couldn't derive an answer")
        }

        val sampleFile = "${derived.one.sampleStem}.wav"
        com.snipsnap.audio.WavWriter.write(File(kitDir, sampleFile), derived.one.sample)
        AnswerStore.save(kitDir, AnswerStore.StoredAnswer(seed, derived.name, sampleFile, derived.clip))

        out.println(
            "the answer: ${derived.clip.notes.size} bass notes in ${derived.key.label}, " +
                "rooted at ${derived.one.rootName}",
        )
        out.println("  playing the gaps of \"${groove.name}\" - never on the kit's strong hits, leaning its way")
        out.println("  reroll: --seed N; hear it: snipsnap project ${kitDir.path} (the session lands both)")
        return 0
    }
}
