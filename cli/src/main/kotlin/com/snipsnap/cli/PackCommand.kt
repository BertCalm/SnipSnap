package com.snipsnap.cli

import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.PackBuilder
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap pack <kit-dir>... --title NAME` — N kits under one expansion
 * tile, the commercial-pack shape: a catalog of programs, per-kit
 * previews, one cover. Turns chop-all's output into a shippable product.
 */
object PackCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--title", "--out", "--art"),
            boolean = setOf("--xpn", "--overwrite", "--no-art", "--no-previews"),
        )
        if (opts.positional.isEmpty()) {
            throw CliError("pack wants kit folders: snipsnap pack <kit-dir>... --title NAME [--xpn]")
        }
        val title = opts["--title"]
            ?: throw CliError("a pack needs a name: --title \"Crate Vol 1\"")
        if (!com.snipsnap.kit.Names.isMpcSafe(title)) {
            throw CliError("pack title isn't MPC-safe: '$title'")
        }
        if (opts.has("--no-art") && opts["--art"] != null) {
            throw CliError("--art and --no-art contradict each other")
        }
        val artStyle = Exports.parseArtStyle(opts["--art"])
        val kitDirs = opts.positional.map { path ->
            File(path).also {
                if (!File(it, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $path")
            }
        }

        val meta = ExpansionMeta(
            title = title,
            identifier = "app.snipsnap." +
                title.filter { it.isLetterOrDigit() }.lowercase().ifBlank { "pack" },
            description = "Made with SnipSnap.",
        )
        // The pack tile: the first kit's sound wearing the pack's name.
        val artwork = if (opts.has("--no-art")) {
            null
        } else {
            com.snipsnap.shell.KitArt.png(
                KitStore.load(kitDirs.first()), kitDirs.first(), artStyle, label = title,
            )
        }

        val result = try {
            PackBuilder.build(
                kitDirs, File(opts["--out"] ?: "snipsnap-out", "card"), meta,
                artworkPng = artwork,
                withPreviews = !opts.has("--no-previews"),
                asXpn = opts.has("--xpn"),
                overwrite = opts.has("--overwrite"),
            )
        } catch (e: java.io.IOException) {
            throw CliError(e.message ?: "pack refused")
        }

        out.println("pack: $title - ${result.packed.size} kit(s) under one tile")
        result.packed.forEach { out.println("  + $it") }
        result.skipped.forEach { (name, why) -> out.println("  ! skipped '$name' - $why") }
        out.println("  expansion: ${result.directory.path}")
        result.xpn?.let { out.println("  archive:   ${it.path} (one file, shareable)") }
        return 0
    }
}
