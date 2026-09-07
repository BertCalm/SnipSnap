package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.JCard
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap jcard <kit-dir>` — the kit's cassette insert: front, spine
 * and back panels in one fold-ready PNG, drawn from what the kit already
 * tracks (art, key, tempo, classes, sources, groove, mileage).
 */
object JCardCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out", "--width"), boolean = emptySet())
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("jcard wants a kit: snipsnap jcard <kit-dir> [--out DIR]")
        if (opts.positional.size > 1) throw CliError("jcard takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val width = opts.int("--width") ?: JCard.DEFAULT_WIDTH
        if (width !in 300..2400) throw CliError("--width is 300..2400, got $width")

        val kit = KitStore.load(kitDir)
        // Under a labeled crate, the spine wears the catalog number.
        val catalog = com.snipsnap.shell.Label.forKit(kitDir)
        val outDir = File(opts["--out"] ?: "snipsnap-out").apply { mkdirs() }
        val file = File(outDir, "${kit.name} J-Card.png")
        file.writeBytes(JCard.png(kit, kitDir, width = width, catalog = catalog))
        out.println("j-card: ${file.path}" + (catalog?.let { " ($it)" } ?: ""))
        out.println("  front, spine, back - print, cut at the amber fold lines, slide into the case")
        return 0
    }
}
