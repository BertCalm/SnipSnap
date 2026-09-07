package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap art <kit-dir>` — the cover-art prototyping loop: regenerate
 * the kit's tile in one command, eyeball, adjust, repeat. With no
 * `--style` it renders every style side by side, which is the fastest way
 * to compare directions.
 */
object ArtCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--style", "--scheme", "--seed", "--size", "--out"),
            boolean = emptySet(),
        )
        val dirArg = opts.positional.firstOrNull()
            ?: throw CliError("art wants a kit folder: snipsnap art <kit-dir> [--style ${styleIds()}]")
        if (opts.positional.size > 1) {
            throw CliError("art takes one kit folder, got ${opts.positional.size}")
        }
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) {
            throw CliError("not a kit folder (no kit.json): $dirArg")
        }

        val styles = opts["--style"]?.let {
            listOf(
                KitArt.Style.byId(it)
                    ?: throw CliError("unknown style '$it' - styles: ${styleIds()}"),
            )
        } ?: KitArt.Style.entries.toList()
        val scheme = opts["--scheme"]?.let { parseScheme(it) } ?: Schemes.DEFAULT
        val seed = opts.int("--seed") ?: 0
        val size = opts.int("--size") ?: KitArt.DEFAULT_SIZE
        if (size !in 64..2048) throw CliError("--size wants 64..2048, got $size")
        val outDir = File(opts["--out"] ?: ".")
        outDir.mkdirs()

        val kit = KitStore.load(kitDir)
        out.println("kit: ${kit.name} (${kit.pads.size} pads) - scheme ${scheme.id.displayName}, seed $seed, ${size}px")
        for (style in styles) {
            val file = File(outDir, "${kit.name}_${style.id}.png")
            file.writeBytes(KitArt.png(kit, kitDir, style, scheme, seed, size))
            out.println("  %-9s %s  (%s)".format(style.id, file.path, style.blurb))
        }
        if (styles.size > 1) {
            out.println()
            out.println("narrow it down: --style NAME, --scheme ${schemeIds()}, --seed N reshuffles rings")
        }
        return 0
    }

    private fun styleIds() = KitArt.Style.entries.joinToString(",") { it.id }

    private fun schemeIds() = Schemes.ALL.joinToString(",") { schemeWord(it) }

    /** `displayName` lowercased with spaces hyphenated, for the `--scheme` flag. */
    private fun schemeWord(s: Scheme) = s.id.displayName.lowercase().replace(' ', '-')

    private fun parseScheme(word: String): Scheme =
        Schemes.ALL.firstOrNull { schemeWord(it) == word.lowercase() }
            ?: throw CliError("unknown scheme '$word' - schemes: ${schemeIds()}")
}
