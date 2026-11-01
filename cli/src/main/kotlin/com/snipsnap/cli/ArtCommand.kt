package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.ArtSidecar
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Mark
import com.snipsnap.shell.Palettes
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap art <kit-dir>` — the cover-art prototyping loop: regenerate
 * the kit's tile in one command, eyeball, adjust, repeat. With no
 * `--style` it renders every style side by side, which is the fastest way
 * to compare directions. The kit's own `art.json` sets the knobs; each of
 * `--palette` (or `--scheme`, through its palette), `--seed-pad` and
 * `--mark` overrides one, and `--seed` is the rings dice.
 */
object ArtCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--style", "--scheme", "--palette", "--seed-pad", "--mark", "--seed", "--size", "--out"),
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
        // The kit's own knobs first; every flag overrides one of them.
        val saved = ArtSidecar.read(kitDir)
        val palette = opts["--palette"]?.let {
            Palettes.byId(it) ?: throw CliError("unknown palette '$it' - palettes: ${Palettes.ALL.joinToString(",") { p -> p.id }}")
        } ?: opts["--scheme"]?.let { Palettes.forScheme(parseScheme(it)) }
            ?: saved?.palette ?: Palettes.VENT
        val mark = opts["--mark"]?.let {
            Mark.Variant.byId(it.lowercase()) ?: throw CliError("unknown mark '$it' - marks: ${Mark.Variant.entries.joinToString(",") { v -> v.id }}")
        } ?: saved?.mark ?: Mark.Variant.STACKED
        val seedPad = if (opts["--seed-pad"] != null) opts.int("--seed-pad") else saved?.seedSlot
        if (seedPad != null && seedPad !in 1..128) throw CliError("--seed-pad wants a pad 1..128, got $seedPad")
        val seed = opts.int("--seed") ?: 0
        val size = opts.int("--size") ?: KitArt.DEFAULT_SIZE
        if (size !in 64..2048) throw CliError("--size wants 64..2048, got $size")
        val outDir = File(opts["--out"] ?: ".")
        outDir.mkdirs()

        val kit = KitStore.load(kitDir)
        val seedLabel = seedPad?.let { String.format(java.util.Locale.ROOT, "A%02d", it) } ?: "auto"
        out.println(
            "kit: ${kit.name} (${kit.pads.size} pads) - palette ${palette.id}, seed pad $seedLabel, mark ${mark.id}, rings seed $seed, ${size}px",
        )
        for (style in styles) {
            val file = File(outDir, "${kit.name}_${style.id}.png")
            val knobs = ArtKnobs(style, palette, seedPad, mark)
            file.writeBytes(KitArt.png(kit, kitDir, knobs, size, ringsSeed = seed))
            out.println("  %-9s %s  (%s)".format(style.id, file.path, style.blurb))
        }
        if (styles.size > 1) {
            out.println()
            out.println(
                "narrow it down: --style NAME, --palette ${Palettes.ALL.joinToString(",") { it.id }}, " +
                    "--seed-pad N, --mark symbol|lockup|stacked, --seed N reshuffles rings",
            )
        }
        return 0
    }

    private fun styleIds() = KitArt.Style.entries.joinToString(",") { it.id }

    private fun schemeIds() = Schemes.ALL.joinToString(",") { schemeWord(it) }

    /** `displayName` lowercased with spaces hyphenated, for the `--scheme` flag. */
    private fun schemeWord(s: Scheme) = s.id.displayName.lowercase().replace(' ', '-')

    /** Internal, not private: `snipsnap mark` reads the same `--scheme` words. */
    internal fun parseScheme(word: String): Scheme =
        Schemes.ALL.firstOrNull { schemeWord(it) == word.lowercase() }
            ?: throw CliError("unknown scheme '$word' - schemes: ${schemeIds()}")
}
