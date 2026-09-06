package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.TextureKits
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap sculpt <wav> | <kit-dir> <pad>` — the Sculptor's verb:
 * hits become matter. Modes, not knobs — **cloud** (dense grains
 * hovering just past the attack: a hit becomes weather), **scrub**
 * (the read position crawls the whole source: the break as a slow
 * landscape), **swarm** (a cloud detuned across ±7 semitones: the
 * thickener). Four seeded takes land as a "<Name> Sculpt" texture kit,
 * every pad LOOP by declaration, provenance and a regenerable recipe
 * on each — the same seed always grows the same texture.
 */
object SculptCommand {

    const val TAKES = TextureKits.TAKES
    const val DEFAULT_SECONDS = 8f
    const val MAX_SECONDS = 60f

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--mode", "--seconds", "--seed", "--out", "--name"),
            boolean = setOf("--overwrite"),
        )
        val mode = (opts["--mode"] ?: "cloud").lowercase()
        try {
            TextureKits.sculptParams(mode)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "unknown mode '$mode'")
        }
        val seconds = opts["--seconds"]?.let {
            it.toFloatOrNull()?.takeIf { s -> s >= 1f && s <= MAX_SECONDS }
                ?: throw CliError("--seconds wants 1..${MAX_SECONDS.toInt()}, got '$it'")
        } ?: DEFAULT_SECONDS
        val seed = opts["--seed"]?.let {
            it.toLongOrNull() ?: throw CliError("--seed wants a number, got '$it'")
        } ?: 7L

        val (source, sourceLabel, defaultName) = resolveSource(opts)
        val name = opts["--name"] ?: defaultName
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val outRoot = File(opts["--out"] ?: "snipsnap-out")
        val kitDir = File(outRoot, name)
        if (kitDir.exists()) {
            if (!opts.has("--overwrite")) {
                throw CliError("output already exists: $kitDir (pass --overwrite to replace it)")
            }
            kitDir.deleteRecursively()
        }
        outRoot.mkdirs()

        out.println("sculpting $mode from $sourceLabel - $TAKES takes of %.0fs".format(seconds))
        TextureKits.render(name, kitDir, source, sourceLabel, TextureKits.Spec.Sculpt(mode, seconds, seed)) { out.println(it) }
        out.println("-> ${kitDir.path} ($TAKES LOOP pads, provenance stamped; same seed, same texture)")
        return 0
    }

    /** The source: a WAV, or one pad of a kit. Returns (snip, label, default kit name). */
    private fun resolveSource(opts: Options): Triple<Snip, String, String> {
        val first = opts.positional.getOrNull(0)
            ?: throw CliError("sculpt wants a source: snipsnap sculpt <wav> | <kit-dir> <pad> [--mode cloud|scrub|swarm]")
        val target = File(first)
        return when {
            File(target, "kit.json").isFile -> {
                val padRef = opts.positional.getOrNull(1)
                    ?: throw CliError("which pad? sculpt <kit-dir> <pad> (like A03)")
                if (opts.positional.size > 2) throw CliError("sculpt takes one source")
                val slot = parsePad(padRef)
                val kit = KitStore.load(target)
                val pad = kit.pads.firstOrNull { it.slot == slot }
                    ?: throw CliError("no pad on slot $padRef in ${kit.name}")
                Triple(
                    WavReader.read(File(target, pad.sampleFile)),
                    "${kit.name}:${padRef.uppercase()}",
                    Names.sanitizeStem("${pad.displayName} Sculpt"),
                )
            }
            target.isFile -> {
                if (opts.positional.size > 1) throw CliError("sculpt takes one source")
                Triple(
                    WavReader.read(target),
                    target.name,
                    Names.sanitizeStem(target.nameWithoutExtension) + " Sculpt",
                )
            }
            else -> throw CliError("no such source: $first")
        }
    }

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
