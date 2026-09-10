package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.PadFromAnything
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap pad <wav> | <kit-dir> <pad>` — PAD FROM ANYTHING: one hit
 * becomes a pad that holds while you hold it and plays in every note.
 * Stretched far (the clear stretch for a note, the wash for a hit), a
 * seamless loop cut from the wash, written as a keygroup instrument with
 * loop points and a slow release, both generations. `--depth` is how far
 * (×8..×100, giving where a minute or the loop demand it), `--bloom` how
 * long the arrival takes (0..1). A pitched source is rooted where it
 * sounds; an unpitched one lands as a drone at C3.
 */
object PadCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--depth", "--bloom", "--seed", "--out", "--name"),
            boolean = setOf("--overwrite"),
        )
        val depth = opts["--depth"]?.let {
            it.toFloatOrNull()?.takeIf { d -> d in PadFromAnything.DEPTH_MIN..PadFromAnything.DEPTH_MAX }
                ?: throw CliError("--depth wants ${PadFromAnything.DEPTH_MIN.toInt()}..${PadFromAnything.DEPTH_MAX.toInt()}, got '$it'")
        } ?: PadFromAnything.DEPTH_DEFAULT
        val bloom = opts["--bloom"]?.let {
            it.toFloatOrNull()?.takeIf { b -> b in 0f..1f } ?: throw CliError("--bloom wants 0..1, got '$it'")
        } ?: 0.3f
        val seed = opts["--seed"]?.let { it.toLongOrNull() ?: throw CliError("--seed wants a number, got '$it'") } ?: 7L

        val (source, sourceLabel, defaultName) = resolveSource(opts)
        val name = opts["--name"] ?: defaultName
        if (!Names.isMpcSafe(name)) throw CliError("instrument name isn't MPC-safe: '$name'")
        val cardDir = File(opts["--out"] ?: "snipsnap-out", "card")

        val result = try {
            PadFromAnything.export(name, source, cardDir, PadFromAnything.Spec(depth, bloom, seed), opts.has("--overwrite"))
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "can't make a pad of that")
        }
        out.println(
            if (result.pitched) {
                "pad from $sourceLabel: %s (%.1f Hz), the clear stretch x%.1f - rooted there, playable 0..127".format(java.util.Locale.ROOT, 
                    result.rootName, result.detectedHz, result.depthUsed,
                )
            } else {
                "pad from $sourceLabel: no note in it, the wash x%.1f - a drone at %s, playable 0..127".format(java.util.Locale.ROOT, 
                    result.depthUsed, result.rootName,
                )
            },
        )
        out.println(
            "  loop: %.1fs head, %.1fs body, seam baked - hold the key and it sings forever".format(java.util.Locale.ROOT, 
                result.loopStartFrame.toFloat() / result.sample.sampleRate,
                (result.sample.frameCount - result.loopStartFrame).toFloat() / result.sample.sampleRate,
            ),
        )
        out.println("instrument: ${File(cardDir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        return 0
    }

    /** The source: a WAV, or one pad of a kit. Returns (snip, label, default instrument name). */
    private fun resolveSource(opts: Options): Triple<Snip, String, String> {
        val first = opts.positional.getOrNull(0)
            ?: throw CliError("pad wants a source: snipsnap pad <wav> | <kit-dir> <pad> [--depth N] [--bloom 0..1]")
        val target = File(first)
        return when {
            File(target, "kit.json").isFile -> {
                val padRef = opts.positional.getOrNull(1)
                    ?: throw CliError("which pad? pad <kit-dir> <pad> (like A03)")
                if (opts.positional.size > 2) throw CliError("pad takes one source")
                val slot = parsePad(padRef)
                val kit = KitStore.load(target)
                val pad = kit.pads.firstOrNull { it.slot == slot }
                    ?: throw CliError("no pad on slot $padRef in ${kit.name}")
                Triple(
                    WavReader.read(File(target, pad.sampleFile)),
                    "${kit.name}:${padRef.uppercase()}",
                    Names.sanitizeStem("${pad.displayName} Pad"),
                )
            }
            target.isFile -> {
                if (opts.positional.size > 1) throw CliError("pad takes one source")
                Triple(WavReader.read(target), target.name, Names.sanitizeStem(target.nameWithoutExtension) + " Pad")
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
