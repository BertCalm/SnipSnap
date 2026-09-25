package com.snipsnap.cli

import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import com.snipsnap.synth.ResinVoice
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap synth <ENGINE> <VOICE> [--preset N | --all] --out <dir>` — the
 * audition path. Renders factory presets straight to WAV so a sound can be
 * *heard* before it is judged; the engines' presets were authored without
 * one, which is the failure `docs/superpowers/specs/2026-09-18-synth-depth-design.md`
 * exists to correct.
 *
 * `--instrument [--attack S] [--release S]` (RESIN only) renders the preset
 * held down instead: a keys instrument that sounds while a key is held.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--attack", "--release"),
            boolean = setOf("--all", "--instrument"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val presets = Presets.forVoice(engine, voice)
        if (presets.isEmpty()) throw CliError("no such engine/voice: $engine $voice")

        val instrument = opts.has("--instrument")
        if (!instrument && (opts["--attack"] != null || opts["--release"] != null)) {
            throw CliError("--attack and --release shape a held instrument - add --instrument")
        }
        if (instrument) {
            if (engine != "RESIN") throw CliError("only RESIN can hold a note yet - $engine renders one-shots; try snipsnap synth RESIN BRASS --instrument")
            if (opts.has("--all")) throw CliError("--instrument makes one instrument per call - pick a --preset, not --all")
        }

        val dirArg = opts["--out"] ?: throw CliError("--out wants a folder to write the wavs into")
        val dir = File(dirArg)
        if (!dir.isDirectory && !dir.mkdirs()) throw CliError("could not make the output folder: $dirArg")

        val chosen: List<Patch> = when {
            opts.has("--all") -> presets
            opts["--preset"] != null -> {
                val n = opts["--preset"]!!.toIntOrNull()
                    ?: throw CliError("--preset wants a number, got '${opts["--preset"]}'")
                if (n !in 1..presets.size) throw CliError("--preset is 1..${presets.size} for $engine $voice, got $n")
                listOf(presets[n - 1])
            }
            else -> listOf(presets.first())
        }

        if (instrument) return makeInstrument(voice, chosen.single(), opts, dir, out)

        for ((i, patch) in chosen.withIndex()) {
            val snip = patch.render()
            val safe = patch.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "PRESET" }
            val file = File(dir, "%s_%s_%02d_%s.wav".format(Locale.ROOT, engine, voice, i + 1, safe))
            FileOutputStream(file).use { WavWriter.write(it, snip) }
            out.println("${file.name}  ${"%.2f".format(Locale.ROOT, snip.durationSeconds)}s")
        }
        out.println("${chosen.size} rendered into ${dir.path}")
        return 0
    }

    /**
     * `--instrument`: the preset held down, as a keys instrument in the
     * dual-generation layout (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md).
     */
    private fun makeInstrument(voice: String, patch: Patch, opts: Options, dir: File, out: PrintStream): Int {
        fun seconds(flag: String, default: Float): Float {
            val raw = opts[flag] ?: return default
            return raw.toFloatOrNull() ?: throw CliError("$flag wants seconds, got '$raw'")
        }
        val spec = try {
            ResinPadMaker.Spec(
                ResinVoice.valueOf(voice),
                patch.macros,
                attackSeconds = seconds("--attack", ResinPadMaker.ATTACK.default),
                releaseSeconds = seconds("--release", ResinPadMaker.RELEASE.default),
            )
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "bad --attack or --release")
        }
        val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
        val midis = ResinPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            ResinPadMaker.renderZone(spec, midi)
        }
        ResinPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, each holds - ATTACK ${ResinPadMaker.secondsLabel(spec.attackSeconds)}, RELEASE ${ResinPadMaker.secondsLabel(spec.releaseSeconds)}")
        return 0
    }
}
