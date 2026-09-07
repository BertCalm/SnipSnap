package com.snipsnap.cli

import com.snipsnap.audio.Eternal
import com.snipsnap.shell.Keyed
import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap eternal <kit-dir> <pad>` — the attack kept bit for bit, the
 * tail slowed toward a frozen instant: `--tail` seconds in all past the
 * knee (default 8), `--knee` ms of untouched attack (default 30),
 * `--seed` the phases, `--undo` the earlier audio back out of the bin.
 */
object EternalCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--tail", "--knee", "--seed"), boolean = setOf("--undo"))
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("eternal wants a kit and a pad: snipsnap eternal <kit-dir> A02 [--tail 8] [--knee 30]")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        if (opts.positional.size > 2) throw CliError("eternal takes a kit and a pad, nothing more")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            model.untreatPad(slot)
            model.save()
            out.println("pad $padArg restored - the earlier audio came back out of the bin")
            return 0
        }
        val tail = opts["--tail"]?.let {
            it.toFloatOrNull()?.takeIf { t -> t in Eternal.TAIL_MIN_SEC..Eternal.TAIL_MAX_SEC }
                ?: throw CliError("--tail wants ${Eternal.TAIL_MIN_SEC}..${Eternal.TAIL_MAX_SEC.toInt()} seconds, got '$it'")
        } ?: Eternal.TAIL_DEFAULT_SEC
        val knee = opts["--knee"]?.let {
            it.toFloatOrNull()?.let { ms -> ms / 1000f }?.takeIf { k -> k in Eternal.KNEE_MIN_SEC..Eternal.KNEE_MAX_SEC }
                ?: throw CliError("--knee wants ${(Eternal.KNEE_MIN_SEC * 1000).toInt()}..${(Eternal.KNEE_MAX_SEC * 1000).toInt()} ms, got '$it'")
        } ?: Eternal.KNEE_DEFAULT_SEC
        val seed = opts["--seed"]?.let { it.toLongOrNull() ?: throw CliError("--seed wants a whole number, got '$it'") } ?: 0L
        if (model.kit.pad(slot) == null) throw CliError("no pad on $padArg")

        val done = try {
            model.keyedPad(slot, "eternal", 1f, seed, Keyed.Dials(tail = tail, knee = knee))
        } catch (e: KitBuilderModel.Unpitched) {
            throw CliError("pad $padArg refused: ${e.message}")
        }
        model.save()
        out.println(
            "pad $padArg made eternal: the first %.0f ms kept bit for bit, the tail slowed into %.1f s - original in the bin, recipe recorded (undo: --undo)".format(
                Locale.ROOT, knee * 1000f, tail,
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
