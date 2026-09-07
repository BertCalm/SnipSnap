package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.Desample
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap desample <wav> | <kit-dir> <pad>` — DE-SAMPLE: the nearest
 * THUMP patch to a captured hit. A `.wav` prints the patch (and `--out
 * patch.json` writes it); a kit pad is replaced by the patch's own
 * render with the patch as its recipe, bin-backed (`--undo`). A far
 * match is named far and refused for a pad unless `--force`.
 */
object DesampleCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--undo", "--force"))
        val first = opts.positional.getOrNull(0)
            ?: throw CliError("desample wants a wav or a kit pad: snipsnap desample <hit.wav> | <kit-dir> A02 [--force] [--undo]")
        val target = File(first)
        if (target.isFile) {
            if (opts.positional.size > 1) throw CliError("a wav takes no pad")
            val match = Desample.nearest(WavReader.read(target), name = target.nameWithoutExtension)
            out.println(
                "%s: nearest patch is %s at distance %.2f%s".format(
                    Locale.ROOT, target.name, match.patch.voice.name.lowercase(), match.distance,
                    if (match.far) " - far: no patch is really near" else "",
                ),
            )
            out.println("  macros: " + match.patch.macros.entries.joinToString(" ") { (k, v) -> "%s=%.2f".format(Locale.ROOT, k, v) })
            opts["--out"]?.let { path ->
                File(path).writeText(match.patch.toJsonText())
                out.println("  patch written to $path")
            }
            return 0
        }
        if (!File(target, "kit.json").isFile) throw CliError("not a wav or a kit folder (no kit.json): $first")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        val slot = parsePad(padArg)
        val model = KitBuilderModel.open(target)
        if (opts.has("--undo")) {
            model.untreatPad(slot)
            model.save()
            out.println("pad $padArg restored - the capture came back out of the bin")
            return 0
        }
        if (model.kit.pad(slot) == null) throw CliError("no pad on $padArg")
        val match = try {
            model.desamplePad(slot, evenIfFar = opts.has("--force"))
        } catch (e: KitBuilderModel.Far) {
            throw CliError("pad $padArg: ${e.message} (--force to take it anyway)")
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "de-sample refused")
        }
        model.save()
        out.println(
            "pad $padArg is a %s patch now (distance %.2f%s) - the capture waits in the bin (undo: --undo)".format(
                Locale.ROOT, match.patch.voice.name.lowercase(), match.distance, if (match.far) ", far" else "",
            ),
        )
        out.println("  macros: " + match.patch.macros.entries.joinToString(" ") { (k, v) -> "%s=%.2f".format(Locale.ROOT, k, v) })
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
