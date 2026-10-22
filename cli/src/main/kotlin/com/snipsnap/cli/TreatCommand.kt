package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.PedalBoard
import com.snipsnap.synth.PedalSide
import com.snipsnap.synth.Pedals
import com.snipsnap.synth.Treatments
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap treat <kit-dir> <pad> <treatment>` — the FX rack pointed at
 * one pad. `--undo` pops the last treatment back out of the bin.
 * `--pedals <key[@amount][:side],...>` is the pedal library's board over the
 * same pad: every pick with its own AMT and side of the amp, rendered from
 * the audio on disk, every velocity layer included (the sheet's door,
 * `pedalPad`, not `treatPad`). A side left out is the pedal's own.
 */
object TreatCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--amount", "--pedals"), boolean = setOf("--undo"))
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

        opts["--pedals"]?.let { list ->
            if (opts.positional.size > 2) throw CliError("--pedals takes the picks itself; no character name beside it")
            if (opts["--amount"] != null) throw CliError("--pedals carries each pick's amount (key@0.7); --amount is for one character")
            val board = parsePedals(list)
            val pad = model.pedalPad(slot, board)
            model.save()
            out.println(
                "pad $padArg pedals: " + board.picks.joinToString(", ") { p ->
                    "${p.key} at %.2f %s".format(java.util.Locale.ROOT, p.amount, p.side.id)
                } + " - original in the bin, recipe recorded (undo: --undo)",
            )
            out.println("  ${pad.sampleFile} re-rendered in place; re-export to hear it on the card")
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

    /**
     * `wahed@0.7:pre,driven,slapback@0.5:post` → a [PedalBoard]: a key from the
     * library, an optional amount after `@` (1 without), an optional side after
     * `:` (the pedal's own without). Refused in the library's and the board's
     * own words (an unknown pedal, a side it cannot sit on, a seventh pick).
     */
    internal fun parsePedals(list: String): PedalBoard {
        val picks = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (picks.isEmpty()) throw CliError("--pedals wants at least one pick, e.g. --pedals wahed@0.7:pre,driven")
        return try {
            PedalBoard(
                picks.map { spec ->
                    val key = spec.substringBefore('@').substringBefore(':')
                    val amountText = spec.substringAfter('@', "").substringBefore(':')
                    val sideText = spec.substringAfter(':', "")
                    val amount = if (amountText.isEmpty()) 1f else amountText.toFloatOrNull()
                        ?: throw CliError("'$spec': the amount after @ wants a number 0..1")
                    val entry = Pedals.byKey(key)
                    val side = if (sideText.isEmpty()) entry.side else PedalSide.fromId(sideText)
                    PedalBoard.Pick(key, amount, side)
                },
            )
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "bad --pedals list")
        }
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
