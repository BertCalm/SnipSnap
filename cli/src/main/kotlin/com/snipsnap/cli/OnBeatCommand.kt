package com.snipsnap.cli

import com.snipsnap.shell.Keyed
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.OnBeat
import com.snipsnap.synth.Wobble
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap onbeat <kit-dir> <pad>` — the tempo delay: the hit comes back
 * on a division of the kit's bar (`--rate 3/16`, the dotted eighth, by
 * default; any of the grid's, too) at the kit's tempo (`--bpm` sets it
 * first), each repeat quieter and darker, the trail gone within a bar.
 * `--amount` is the send, `--undo` pops the earlier audio back out of the bin.
 */
object OnBeatCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--bpm", "--rate", "--amount"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("onbeat wants a kit and a pad: snipsnap onbeat <kit-dir> A02 [--rate 3/16] [--bpm 92] [--amount 0..1]")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        if (opts.positional.size > 2) throw CliError("onbeat takes a kit and a pad, nothing more")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            model.untreatPad(slot)
            model.save()
            out.println("pad $padArg restored - the earlier audio came back out of the bin")
            return 0
        }
        val amount = opts["--amount"]?.let {
            it.toFloatOrNull()?.takeIf { a -> a in 0f..1f } ?: throw CliError("--amount wants 0..1, got '$it'")
        } ?: 1f
        val division = opts["--rate"]?.let {
            it.takeIf { d -> d in OnBeat.DIVISIONS }
                ?: throw CliError("--rate wants a note division, one of: ${OnBeat.DIVISIONS.joinToString(", ")} - got '$it'")
        } ?: OnBeat.DEFAULT_DIVISION
        opts["--bpm"]?.let {
            val bpm = it.toFloatOrNull()?.takeIf { b -> b in Wobble.MIN_BPM..Wobble.MAX_BPM }
                ?: throw CliError("--bpm wants ${Wobble.MIN_BPM.toInt()}..${Wobble.MAX_BPM.toInt()}, got '$it'")
            model.setTempo(bpm)
        }
        if (model.kit.pad(slot) == null) throw CliError("no pad on $padArg")

        val done = model.keyedPad(slot, "echoed", amount, dials = Keyed.Dials(echoDivision = division))
        model.save()
        val period = OnBeat.periodSec(model.keyedContext().bpm, division)
        out.println(
            "pad $padArg echoed at %s (%.0f ms a repeat)%s - original in the bin, recipe recorded (undo: --undo)".format(
                Locale.ROOT,
                model.lastKeyLabel.lowercase(),
                period * 1000f,
                if (amount < 1f) " at %.2f".format(Locale.ROOT, amount) else "",
            ),
        )
        out.println("  ${done.sampleFile} re-rendered in place; re-export to hear it on the card")
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
