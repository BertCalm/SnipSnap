package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Robin
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap robin <kit-dir> <pad> [--takes N] [--seed S]` — round robin
 * for a pad that has one take: seeded subtle variants rendered into a
 * chain WAV, the MPC 3 cycling a take per hit (Slice Motion), the MPC 2
 * playing take one — the untouched original, deliberately first.
 * `--undo` pulls that single take back out of the bin byte-identical.
 */
object RobinCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--takes", "--seed", "--zones"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError(
                "robin wants a kit and a pad: snipsnap robin <kit-dir> A01 [--takes 3] [--seed 7]",
            )
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            Robin.undo(model, slot)
            model.save()
            out.println("pad $padArg is a single take again - the original came back out of the bin")
            return 0
        }

        val takes = opts.int("--takes") ?: Robin.DEFAULT_TAKES
        val seed = opts.int("--seed") ?: 0
        val zones = opts.int("--zones")
        val pad = Robin.apply(model, slot, takes = takes, seed = seed, zones = zones)
        model.save()
        if (zones != null) {
            out.println(
                "pad $padArg is a velocity x round-robin grid: $zones zones x $takes takes " +
                    "(seed $seed) - soft hits play quieter, darker takes",
            )
            out.println("  ${pad.sampleFile} is the graded chain, soft to hard; the preview picks the")
            out.println("  zone by velocity and cycles takes within it. the MPC 3 metadata does the")
            out.println("  same (Slice Motion); the MPC 2 velocity-switches the zones' anchor takes.")
            out.println("  undo: --undo")
        } else {
            out.println(
                "pad $padArg is a round-robin chain of $takes takes (seed $seed) - " +
                    "take one untouched, the rest subtle seeded variants",
            )
            out.println("  ${pad.sampleFile} is now the chain; the preview alternates takes per hit,")
            out.println("  and the MPC 3 metadata cycles them (Slice Motion). until chains carry their")
            out.println("  slice map, hardware of either generation plays take one. undo: --undo")
        }
        return 0
    }

    /** "A03" → 3, "B01" → 17, "7" → 7. */
    private fun parsePad(arg: String): Int {
        arg.toIntOrNull()?.let { return it }
        val m = Regex("^([A-Ha-h])(\\d{1,2})$").matchEntire(arg.trim())
            ?: throw CliError("can't read pad '$arg' - try A03, B01, or a slot number")
        val bank = m.groupValues[1].uppercase()[0] - 'A'
        val n = m.groupValues[2].toInt()
        if (n !in 1..16) throw CliError("pad number in a bank is 1..16, got $n")
        return bank * 16 + n
    }
}
