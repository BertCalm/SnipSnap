package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.Treatments
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap treat <kit-dir> <pad> <treatment>` — the FX rack pointed at
 * one pad. `--undo` pops the last treatment back out of the bin.
 */
object TreatCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--amount"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError(
                "treat wants a kit, a pad and a character: " +
                    "snipsnap treat <kit-dir> A02 ${Treatments.names.first()} [--amount 0.6]",
            )
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            if (opts.positional.size > 2) throw CliError("--undo takes no treatment name")
            model.untreatPad(slot)
            model.save()
            out.println("pad $padArg restored - the earlier audio came back out of the bin")
            return 0
        }

        val treatment = opts.positional.getOrNull(2)
            ?: throw CliError("which character? one of: ${Treatments.names.joinToString(", ")}")
        val amount = opts["--amount"]?.let {
            it.toFloatOrNull() ?: throw CliError("--amount wants a number, got '$it'")
        } ?: 1f

        val pad = model.treatPad(slot, treatment, amount)
        model.save()
        out.println(
            "pad $padArg ${treatment}%s - original in the bin, recipe recorded (undo: --undo)".format(
                if (amount < 1f) " at %.2f".format(java.util.Locale.ROOT, amount) else "",
            ),
        )
        out.println("  ${pad.sampleFile} re-rendered in place; re-export to hear it on the card")
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
