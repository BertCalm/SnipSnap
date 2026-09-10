package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.Eras
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap era <kit-dir> <era>` — the Time Machine: the whole kit (or a
 * `--pads` subset) rendered through the specific math of a specific
 * machine. Originals go to the bin, so `--undo` brings the present back.
 */
object EraCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--amount", "--pads"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError(
                "era wants a kit and a machine: snipsnap era <kit-dir> " +
                    "${Eras.names.first()} [--amount 0.7] [--pads A01,A02]",
            )
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val model = KitBuilderModel.open(kitDir)
        val slots = opts["--pads"]?.let { list ->
            list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parsePad)
                .ifEmpty { throw CliError("--pads wants pads, comma-separated: A01,B03") }
        }

        if (opts.has("--undo")) {
            if (opts.positional.size > 1) throw CliError("--undo takes no era name")
            val targets = slots ?: model.kit.pads.map { it.slot }
            var restored = 0
            for (slot in targets) {
                try {
                    model.unEraPad(slot)
                    restored++
                } catch (e: IllegalArgumentException) {
                    // A pad with nothing binned just stays as it is.
                }
            }
            if (restored == 0) throw CliError("nothing to restore - the bin holds no earlier takes")
            model.save()
            out.println("$restored pad(s) restored - the present came back out of the bin")
            return 0
        }

        val era = opts.positional.getOrNull(1)
            ?: throw CliError("which machine? one of: ${Eras.names.joinToString(", ")}")
        if (era !in Eras.names) {
            throw CliError("unknown era '$era' - try one of: ${Eras.names.joinToString(", ")}")
        }
        val amount = opts["--amount"]?.let {
            it.toFloatOrNull()?.takeIf { a -> a in 0f..1f }
                ?: throw CliError("--amount wants a number in 0..1, got '$it'")
        } ?: 1f

        val aged = model.eraKit(era, amount, slots)
        model.save()
        out.println(
            "$aged pad(s) through the $era%s - originals in the bin, recipes recorded (undo: --undo)".format(
                if (amount < 1f) " at %.2f".format(java.util.Locale.ROOT, amount) else "",
            ),
        )
        out.println("  re-export to hear the era on the card")
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
