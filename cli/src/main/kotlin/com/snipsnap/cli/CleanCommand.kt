package com.snipsnap.cli

import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap clean <wav-or-kit-dir>` — the Capture Doctor's house call:
 * measured hum removal, click and dropout repair, the noise floor's
 * gentle gate — each treatment gated by its own detector, so a clean
 * capture is left byte-identical and told so.
 *
 * A WAV gets a cleaned twin beside it (`--in-place` overwrites); a kit
 * gets every plain pad through the treatment door — bin-backed, recipe
 * stamped, layered and chained pads skipped by name — and `--undo`
 * pulls every cleaned pad back out byte-identical. `--dry` reports
 * without touching anything, `doctor`-style.
 */
object CleanCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--out"),
            boolean = setOf("--dry", "--in-place", "--undo", "--overwrite", "--denoise", "--deroom", "--declip", "--deverb"),
        )
        val input = opts.positional.getOrNull(0)
            ?: throw CliError("clean wants a capture or a kit: snipsnap clean <wav-or-kit-dir> [--dry]")
        if (opts.positional.size > 1) throw CliError("clean takes one target")
        val target = File(input)
        return when {
            File(target, "kit.json").isFile -> cleanKit(target, opts, out)
            target.isFile -> cleanWav(target, opts, out)
            else -> throw CliError("no such capture or kit: $input")
        }
    }

    private fun cleanWav(file: File, opts: Options, out: PrintStream): Int {
        if (opts.has("--undo")) throw CliError("--undo is for kits - a WAV's cleaned twin sits beside the original")
        if (opts.has("--deroom")) throw CliError("--deroom is for kits - the knee reads one hit at a time, not a whole capture")
        val report = try {
            CaptureDoctor.clean(
                WavReader.read(file),
                denoise = opts.has("--denoise"),
                declip = opts.has("--declip"),
                deverb = opts.has("--deverb"),
            )
        } catch (e: IllegalArgumentException) {
            throw CliError("${file.name}: ${e.message}")
        }
        out.println("${file.name}: ${report.summary()}")
        if (!report.touched || opts.has("--dry")) {
            if (report.touched) out.println("(--dry: nothing written)")
            return 0
        }
        val dest = if (opts.has("--in-place")) {
            file
        } else {
            val dir = opts["--out"]?.let { File(it).apply { mkdirs() } } ?: file.parentFile ?: File(".")
            File(dir, "${file.nameWithoutExtension} Clean.wav").also {
                if (it.exists() && !opts.has("--overwrite")) {
                    throw CliError("destination already exists: $it (pass --overwrite to replace it)")
                }
            }
        }
        WavWriter.write(dest, report.snip)
        out.println("-> ${dest.path}")
        return 0
    }

    private fun cleanKit(kitDir: File, opts: Options, out: PrintStream): Int {
        val model = KitBuilderModel.open(kitDir)

        if (opts.has("--undo")) {
            var restored = 0
            for (pad in model.kit.pads) {
                if (pad.recipe?.entries?.containsKey("clean") == true) {
                    model.untreatPad(pad.slot)
                    restored++
                }
            }
            if (restored == 0) throw CliError("nothing to undo - no pad carries a clean recipe")
            model.save()
            out.println("$restored pad(s) restored - the pre-clean audio came back out of the bin")
            return 0
        }

        var treated = 0
        var alreadyClean = 0
        val skipped = mutableListOf<String>()
        for (pad in model.kit.pads.sortedBy { it.slot }) {
            val label = "%c%02d".format('A' + (pad.slot - 1) / 16, (pad.slot - 1) % 16 + 1)
            if (pad.velocityLayers.isNotEmpty()) {
                skipped += "$label (velocity-layered)"
                continue
            }
            if (pad.chain != null) {
                skipped += "$label (a chain - `robin --undo` first)"
                continue
            }
            val report = try {
                CaptureDoctor.clean(
                    WavReader.read(File(kitDir, pad.sampleFile)),
                    denoise = opts.has("--denoise"),
                    declip = opts.has("--declip"),
                    deverb = opts.has("--deverb"),
                )
            } catch (e: IllegalArgumentException) {
                skipped += "$label (${e.message})"
                continue
            }
            // The tail knee rides --deroom, one-shots only: a LOOP's
            // tail is content, not a room to be shown out.
            val trim = if (opts.has("--deroom") && pad.drumClass != DrumClass.LOOP) {
                CaptureDoctor.trimRoomTail(report.snip)
            } else {
                null
            }
            if (opts.has("--deroom") && pad.drumClass == DrumClass.LOOP) {
                out.println("  $label ${pad.displayName}: LOOP - the knee leaves it alone, a texture's tail is content")
            }
            if (!report.touched && trim == null) {
                alreadyClean++
                continue
            }
            val trimNote = trim?.let { "; room tail faded from %.0f ms".format(it.kneeSec * 1000) } ?: ""
            out.println("  $label ${pad.displayName}: ${report.summary()}$trimNote")
            if (!opts.has("--dry")) {
                val recipe = JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "clean" to JsonValue.Obj(
                            linkedMapOf<String, JsonValue>().also { r ->
                                report.hum?.let { r["humHz"] = JsonValue.Num(it.hz.toDouble()) }
                                if (report.clicks > 0) r["clicks"] = JsonValue.Num(report.clicks.toDouble())
                                if (report.dropouts > 0) r["dropouts"] = JsonValue.Num(report.dropouts.toDouble())
                                if (report.gated) r["floorDb"] = JsonValue.Num(report.floorDb!!.toDouble())
                                if (report.denoised) r["denoised"] = JsonValue.Bool(true)
                                report.clip?.let { r["declipCeiling"] = JsonValue.Num(it.ceiling.toDouble()) }
                                if (report.deverbed) r["deverbed"] = JsonValue.Bool(true)
                                trim?.let { r["deroomKneeMs"] = JsonValue.Num(it.kneeSec * 1000.0) }
                            },
                        ),
                    ),
                )
                val cleaned = trim?.snip ?: report.snip
                model.replaceAudio(pad.slot, recipe) { cleaned }
            }
            treated++
        }
        if (!opts.has("--dry") && treated > 0) model.save()

        out.println(
            "clean: $treated pad(s) treated, $alreadyClean already clean" +
                (if (skipped.isNotEmpty()) ", ${skipped.size} skipped" else "") +
                (if (opts.has("--dry")) " (--dry: nothing written)" else ""),
        )
        skipped.forEach { out.println("  ! $it") }
        if (treated > 0 && !opts.has("--dry")) {
            out.println("originals in the bin; recipes stamped (undo: clean ${kitDir.path} --undo)")
        }
        return 0
    }
}
