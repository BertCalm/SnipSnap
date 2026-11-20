package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap kerf <kit-dir> <pad> | --all`: KERF, a pad replaced by its synthetic twin
 * (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md). The pad's audio is measured and played back
 * from the numbers, with the pads either side of it bleeding in and the cut points a little off (`--bleed`, `--slop`).
 * Bin-backed: `--undo` gives the capture back and refuses a pad the twin is no longer on top of
 * (`KitBuilderModel.unkerfPad`). `--all` does every pad that can take it and names the ones it left.
 * `--recut` moves an existing twin's BLEED and SLOP from its saved numbers, with no new measure
 * (`KitBuilderModel.recutKerfPad`), as the pad sheet's sliders do.
 * To start from a loop, `snipsnap chop` it into a kit first: slot order is then the loop's order, which is what the
 * neighbours are read from.
 */
object KerfCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--bleed", "--slop"), boolean = setOf("--undo", "--all", "--recut"))
        val first = opts.positional.getOrNull(0)
            ?: throw CliError("kerf wants a kit and a pad: snipsnap kerf <kit-dir> A02 | --all [--bleed 1.0] [--slop 0.5] [--undo] [--recut]")
        val target = File(first)
        if (!File(target, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $first")
        val bleed = amount(opts["--bleed"], "--bleed", 1.0, 4.0); val slop = amount(opts["--slop"], "--slop", 0.5, 1.0)
        val model = KitBuilderModel.open(target)
        try {
            if (opts.has("--all")) {
                if (opts.positional.size > 1) throw CliError("--all takes no pad")
                if (opts.has("--undo")) {
                    val twins = model.kit.pads.filter { KitBuilderModel.KERF_STAMP in it.source }.map { it.slot }
                    if (twins.isEmpty()) throw CliError("no pad in this kit is a KERF twin")
                    twins.forEach { model.unkerfPad(it) }
                    model.save()
                    out.println("${twins.size} pads restored - the captures came back out of the bin")
                    return 0
                }
                val kit = model.kerfKit(bleed, slop)
                model.save()
                out.println("${kit.made.size} pads are KERF twins now (bleed %.2f, slop %.2f) - the captures wait in the bin (undo: --all --undo)".format(Locale.ROOT, bleed, slop))
                kit.made.forEach { out.println("  pad ${it.slot}: ${it.drumClass.name.lowercase()}") }
                kit.left.forEach { (slot, why) -> out.println("  pad $slot left alone: $why") }
                kit.already.forEach { out.println("  pad $it left alone: a twin already") }
                return 0
            }
            val padArg = opts.positional.getOrNull(1) ?: throw CliError("which pad? (A01..A16, B01.., a slot number, or --all)")
            val slot = parsePad(padArg)
            if (model.kit.pad(slot) == null) throw CliError("no pad on $padArg")
            if (opts.has("--recut")) {
                val twin = model.kit.pad(slot)!!
                val was = com.snipsnap.shell.Breed.recipeOf(twin)?.kerf ?: throw CliError("pad $padArg is not a KERF twin - nothing to re-cut")
                val pad = model.recutKerfPad(slot, opts["--bleed"]?.let { bleed } ?: was.bleed, opts["--slop"]?.let { slop } ?: was.slop)
                model.save()
                val now = com.snipsnap.shell.Breed.recipeOf(pad)!!.kerf!!
                out.println("pad $padArg re-cut from its own numbers (bleed %.2f, slop %.2f) - the capture still waits in the bin".format(Locale.ROOT, now.bleed, now.slop))
                return 0
            }
            if (opts.has("--undo")) {
                model.unkerfPad(slot)
                model.save()
                out.println("pad $padArg restored - the take before the twin came back out of the bin")
                return 0
            }
            val pad = model.kerfPad(slot, bleed, slop)
            model.save()
            out.println("pad $padArg is a KERF twin now, a %s (bleed %.2f, slop %.2f) - the capture waits in the bin (undo: --undo)".format(Locale.ROOT, pad.drumClass.name.lowercase(), bleed, slop))
            return 0
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "kerf refused")
        }
    }

    private fun amount(text: String?, name: String, default: Double, max: Double): Double {
        val v = text?.let { it.toDoubleOrNull() ?: throw CliError("$name wants a number, got '$it'") } ?: return default
        if (!v.isFinite() || v < 0.0 || v > max) throw CliError("$name is 0..${max.toInt()}, got $text")
        return v
    }

    /** "A03" -> 3, "B01" -> 17, "7" -> 7. */
    private fun parsePad(arg: String): Int {
        arg.toIntOrNull()?.let { return it }
        val m = Regex("^([A-Ha-h])(\\d{1,2})$").matchEntire(arg.trim())
            ?: throw CliError("can't read pad '$arg' - try A03, B01, or a slot number")
        val n = m.groupValues[2].toInt()
        if (n !in 1..16) throw CliError("pad number in a bank is 1..16, got $n")
        return (m.groupValues[1].uppercase()[0] - 'A') * 16 + n
    }
}
