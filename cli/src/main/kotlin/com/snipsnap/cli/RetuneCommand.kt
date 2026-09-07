package com.snipsnap.cli

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Retune
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap retune <kit-dir> <pad>` — the spectral retune: every
 * partial of one pad talked into the kit's key (`--key` overrides it;
 * with neither, onto the nearest semitone), phases by PGHI. `--amount`
 * is how far toward the note; `--undo` pops the earlier audio back out
 * of the bin. A drum is refused as unpitched, in words.
 */
object RetuneCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--key", "--amount", "--seed"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("retune wants a kit and a pad: snipsnap retune <kit-dir> A02 [--key Am] [--amount 0..1]")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        if (opts.positional.size > 2) throw CliError("retune takes a kit and a pad, nothing more")
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
        val seed = opts["--seed"]?.let { it.toLongOrNull() ?: throw CliError("--seed wants a whole number, got '$it'") } ?: 0L
        opts["--key"]?.let { spec ->
            val key = try {
                KeySpec.parse(spec)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't read key '$spec'")
            }
            model.setKey(key)
        }
        val key = model.retuneKey()
        val pad = model.kit.pad(slot) ?: throw CliError("no pad on $padArg")
        val analysis = Retune.analyze(WavReader.read(File(kitDir, pad.sampleFile)), key)
        analysis.refusal?.let { throw CliError("pad $padArg is not a note: $it") }

        val tuned = try {
            model.retunePad(slot, amount, seed)
        } catch (e: KitBuilderModel.Unpitched) {
            throw CliError("pad $padArg is not a note: ${e.message}")
        }
        model.save()
        val where = if (model.kit.key == null) "the nearest semitones" else model.kit.key!!.label
        out.println(
            "pad $padArg retuned into $where%s - original in the bin, recipe recorded (undo: --undo)".format(
                Locale.ROOT, if (amount < 1f) " at %.2f".format(Locale.ROOT, amount) else "",
            ),
        )
        for (p in analysis.partials) out.println("  " + Retune.describe(p))
        out.println("  ${tuned.sampleFile} re-rendered in place; re-export to hear it on the card")
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
