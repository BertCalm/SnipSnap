package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Mutate
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap drift <kit-dir> <pad>` — DRIFT TOWARD THE CRATE, one knob:
 * the crate's roulette finds the neighbour and morph blends `--amount`
 * of the way toward it. `--seed` picks the neighbour, `--root` the crate
 * (default: the kit's parent folder), `--undo` the sound back.
 */
object DriftCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--amount", "--seed", "--root"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("drift wants a kit and a pad: snipsnap drift <kit-dir> A02 [--amount 0..1] [--seed N] [--root DIR]")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        if (opts.positional.size > 2) throw CliError("drift takes a kit and a pad, nothing more")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            Mutate.undo(model, slot)
            model.save()
            out.println("pad $padArg restored - the pre-drift sound came back out of the bin")
            return 0
        }
        val amount = opts["--amount"]?.let {
            it.toFloatOrNull()?.takeIf { a -> a in 0f..1f } ?: throw CliError("--amount wants 0..1, got '$it'")
        } ?: 0.5f
        val seed = opts.int("--seed") ?: 0
        val root = File(opts["--root"] ?: kitDir.absoluteFile.parent ?: ".")
        if (!root.isDirectory) throw CliError("no such crate root: ${root.path}")
        if (model.kit.pad(slot) == null) throw CliError("no pad on $padArg")

        val drifted = try {
            Mutate.drift(model, slot, root, seed, amount)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "the crate refused")
        }
        model.save()
        out.println(
            "pad $padArg drifted %.0f%% toward %s (distance %.2f, seed %d) - the morph's recipe records the spin (undo: --undo)".format(
                Locale.ROOT, amount * 100f, drifted.pick.label, drifted.pick.distance, seed,
            ),
        )
        out.println("  ${drifted.outcome.pad.sampleFile} re-rendered in place; re-export to hear it on the card")
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
