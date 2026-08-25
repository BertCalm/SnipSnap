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
            valued = setOf("--export", "--out", "--art"),
            boolean = setOf("--overwrite", "--preview", "--no-art"),
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
        val preview = if (opts.has("--preview")) {
            com.snipsnap.kit.KitPreview.render(kit, kitDir).also {
                out.println("preview: rendered the kit playing its own beat (%.1fs)".format(it.durationSeconds))
            }
        } else {
            null
        }
        val artwork = Exports.renderArtwork(kit, kitDir, formats, artStyle, opts.has("--no-art"), out)
        Exports.write(
            kit, kitDir, cardDir, formats, opts.has("--overwrite"), out,
            preview = preview, artworkPng = artwork,
        )
        return 0
    }
}
