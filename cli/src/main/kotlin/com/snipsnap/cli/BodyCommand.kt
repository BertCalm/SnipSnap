package com.snipsnap.cli

import com.snipsnap.audio.Body
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap body <kit-dir> <pad>` — a bank of tuned resonators struck
 * by the pad, tuned to the kit's key (`--key` sets it first; with none,
 * the hit's own note, or C). `--decay` is how long the body rings (T60,
 * seconds), `--amount` dry to body, `--undo` pops the earlier audio back
 * out of the bin.
 */
object BodyCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--key", "--amount", "--decay"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("body wants a kit and a pad: snipsnap body <kit-dir> A02 [--key Am] [--decay 0.6] [--amount 0..1]")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        if (opts.positional.size > 2) throw CliError("body takes a kit and a pad, nothing more")
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
        val decay = opts["--decay"]?.let {
            it.toFloatOrNull()?.takeIf { d -> d in Body.DECAY_MIN..Body.DECAY_MAX }
                ?: throw CliError("--decay wants ${Body.DECAY_MIN}..${Body.DECAY_MAX} seconds, got '$it'")
        } ?: Body.DECAY_DEFAULT
        opts["--key"]?.let { spec ->
            val key = try {
                KeySpec.parse(spec)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't read key '$spec'")
            }
            model.setKey(key)
        }
        val pad = model.kit.pad(slot) ?: throw CliError("no pad on $padArg")
        val key = model.kit.key
        val root = Body.rootFor(WavReader.read(File(kitDir, pad.sampleFile)), key)
        val modes = Body.modes(root, key?.scale ?: Scale.CHROMATIC)

        val done = model.keyedPad(slot, "bodied", amount, decay = decay)
        model.save()
        out.println(
            "pad $padArg given a body in %s, ringing %.2f s%s - original in the bin, recipe recorded (undo: --undo)".format(
                Locale.ROOT,
                model.lastKeyLabel.lowercase(),
                decay,
                if (amount < 1f) " at %.2f".format(Locale.ROOT, amount) else "",
            ),
        )
        out.println("  modes: ${modes.joinToString(" ") { it.name }}")
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
