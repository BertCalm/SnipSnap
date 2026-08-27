package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap shape <kit-dir> <pad>` — pad shape as metadata: attack,
 * decay, filter cutoff and resonance land in the exported programs'
 * own fields and the *hardware* renders them. The audio on disk never
 * changes, and undo is `--reset` (back to the format's own defaults).
 */
object ShapeCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--attack", "--decay", "--cutoff", "--res"),
            boolean = setOf("--reset"),
        )
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("shape wants a kit and a pad: snipsnap shape <kit-dir> A02 --decay 0.3")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? snipsnap shape <kit-dir> A02 --decay 0.3")
        if (opts.positional.size > 2) throw CliError("shape takes one kit folder and one pad")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        fun value(name: String): Float? = opts[name]?.let {
            it.toFloatOrNull()?.takeIf { v -> v in 0f..1f }
                ?: throw CliError("$name wants a number in 0..1, got '$it'")
        }
        val attack = value("--attack")
        val decay = value("--decay")
        val cutoff = value("--cutoff")
        val resonance = value("--res")
        val reset = opts.has("--reset")
        if (reset && listOf(attack, decay, cutoff, resonance).any { it != null }) {
            throw CliError("--reset stands alone - it clears the whole shape")
        }
        if (!reset && listOf(attack, decay, cutoff, resonance).all { it == null }) {
            throw CliError("say what to shape: --attack, --decay, --cutoff, --res (or --reset)")
        }

        val slot = parsePad(padArg)
        val model = KitBuilderModel.open(kitDir)
        model.pad(slot) ?: throw CliError("no pad on $padArg")
        val shaped = model.update(slot) { p ->
            if (reset) {
                p.copy(attack = null, decay = null, cutoff = null, resonance = null)
            } else {
                p.copy(
                    attack = attack ?: p.attack,
                    decay = decay ?: p.decay,
                    cutoff = cutoff ?: p.cutoff,
                    resonance = resonance ?: p.resonance,
                )
            }
        }
        model.save()

        fun show(name: String, v: Float?): String = v?.let { "%s %.2f".format(name, it) } ?: ""
        val line = listOf(
            show("attack", shaped.attack), show("decay", shaped.decay),
            show("cutoff", shaped.cutoff), show("res", shaped.resonance),
        ).filter { it.isNotEmpty() }
        if (line.isEmpty()) {
            out.println("$padArg: back to the format's own defaults - unshaped")
        } else {
            out.println("$padArg: ${line.joinToString(", ")}")
        }
        out.println("  metadata only - the hardware renders it, the audio on disk is untouched")
        out.println("  re-export to put the shape on the card; the preview approximates it now")
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
