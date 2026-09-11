package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap export <kit-dir>` — the export fan-out over a kit folder that
 * already exists (one this CLI chopped, or one the app synced off a phone;
 * the folder-with-kit.json shape is the product's working format).
 */
object ExportCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--export", "--out", "--art", "--wear"),
            boolean = setOf("--overwrite", "--preview", "--no-art", "--no-wear"),
        )
        if (opts.has("--no-art") && opts["--art"] != null) {
            throw CliError("--art and --no-art contradict each other")
        }
        val artStyle = Exports.parseArtStyle(opts["--art"])
        val dirArg = opts.positional.firstOrNull()
            ?: throw CliError("export wants a kit folder: snipsnap export <kit-dir> --export xtd")
        if (opts.positional.size > 1) {
            throw CliError("export takes one kit folder, got ${opts.positional.size}")
        }
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) {
            throw CliError("not a kit folder (no kit.json): $dirArg")
        }

        val kit = KitStore.load(kitDir)
        out.println("kit: ${kit.name} (${kit.pads.size} pads)")

        Preflight.check(kit, kitDir).filter { it.severity != Severity.OK }.forEach {
            out.println("  [${it.severity}] ${it.message}")
        }

        val formats = Exports.parseFormats(
            opts["--export"] ?: throw CliError("say which formats: --export ${Exports.FORMATS.joinToString(",")}"),
        )
        val cardDir = File(opts["--out"] ?: "snipsnap-out", "card")

        // The tape's wear: earned from the ledger, or a deliberate --wear
        // override (which may push past the earned ceiling); --no-wear
        // renders the pristine kit regardless.
        val wearOverride = opts["--wear"]?.let {
            it.toFloatOrNull()?.takeIf { v ->
                v > 0f && v <= com.snipsnap.synth.TapeWear.MAX_OVERRIDE_W
            } ?: throw CliError(
                "--wear wants a number in (0, ${com.snipsnap.synth.TapeWear.MAX_OVERRIDE_W}], got '$it'",
            )
        }
        if (opts.has("--no-wear") && wearOverride != null) {
            throw CliError("--wear and --no-wear contradict each other")
        }
        val wearW = if (opts.has("--no-wear")) null else wearOverride ?: com.snipsnap.shell.Wear.earnedW(kit)

        val preview = if (opts.has("--preview")) {
            com.snipsnap.shell.Wear.render(kit, com.snipsnap.kit.KitPreview.render(kit, kitDir), wearW).also {
                out.println("preview: rendered the kit playing its own beat (%.1fs)".format(java.util.Locale.ROOT, it.durationSeconds))
            }
        } else {
            null
        }
        val artwork = Exports.renderArtwork(kit, kitDir, formats, artStyle, opts.has("--no-art"), out)
        val wornStage = wearW?.let {
            java.nio.file.Files.createTempDirectory("snipsnap-worn").toFile()
        }
        try {
            val srcDir = wornStage?.let { com.snipsnap.shell.Wear.stageWorn(kit, kitDir, it, wearW) } ?: kitDir
            if (wornStage != null) {
                out.println(
                    "wear: samples rendered at %.1f%% worn - the kit's own files stay pristine (--no-wear skips)"
                        .format(java.util.Locale.ROOT, wearW * 100),
                )
            }
            Exports.write(
                kit, srcDir, cardDir, formats, opts.has("--overwrite"), out,
                preview = preview, artworkPng = artwork,
            )
        } finally {
            wornStage?.deleteRecursively()
        }
        return 0
    }
}
