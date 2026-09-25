package com.snipsnap.cli

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import com.snipsnap.synth.ResinDrone
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
 *
 * `--drone [--root A1] [--motion 0..1] [--rate 1|2|4] [--bpm N] [--bars N]
 * [--loop N]` (RESIN only) renders the preset as a loop-grid drone: the
 * whole loop, as long as the grid would make it at that tempo, written
 * [--loop] times end to end so the wrap can be heard.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--attack", "--release", "--root", "--motion", "--rate", "--bpm", "--bars", "--loop"),
            boolean = setOf("--all", "--instrument", "--drone"),
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
        val drone = opts.has("--drone")
        val droneFlags = listOf("--root", "--motion", "--rate", "--bpm", "--bars", "--loop").filter { opts[it] != null }
        if (!drone && droneFlags.isNotEmpty()) {
            throw CliError("${droneFlags.joinToString()} shape a drone - add --drone")
        }
        if (drone) {
            if (engine != "RESIN") throw CliError("only RESIN drones yet - try snipsnap synth RESIN BASS --drone")
            if (instrument) throw CliError("--drone and --instrument are two different things - pick one")
            if (opts.has("--all")) throw CliError("--drone makes one drone per call - pick a --preset, not --all")
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
        if (drone) return makeDrone(voice, chosen.single(), opts, dir, out)

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

    /**
     * `--drone`: the preset as a loop-grid drone, rendered the way the grid
     * would at that tempo (docs/superpowers/specs/2026-09-25-resin-drone-design.md).
     */
    private fun makeDrone(voice: String, patch: Patch, opts: Options, dir: File, out: PrintStream): Int {
        val v = ResinVoice.valueOf(voice)
        val range = DroneMaker.roots(v)
        val root = opts["--root"]?.let { raw ->
            val midi = Scales.midiOf(raw) ?: throw CliError("--root wants a note like A1 or C#2, got '$raw'")
            if (midi !in range) {
                throw CliError("--root for RESIN $voice is ${Scales.nameOf(range.first)}..${Scales.nameOf(range.last)}, got $raw")
            }
            midi
        } ?: DroneMaker.defaultRoot(v, key = null)
        val motion = opts["--motion"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it in 0f..1f } ?: throw CliError("--motion wants 0..1, got '$raw'")
        } ?: DroneMaker.DEFAULT_MOTION
        val rate = opts["--rate"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in ResinDrone.RATES }
                ?: throw CliError("--rate wants ${ResinDrone.RATES.joinToString("/")} breaths, got '$raw'")
        } ?: DroneMaker.DEFAULT_RATE
        val bpm = opts["--bpm"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it in Session.MIN_BPM..Session.MAX_BPM }
                ?: throw CliError("--bpm wants ${Session.MIN_BPM.toInt()}..${Session.MAX_BPM.toInt()}, got '$raw'")
        } ?: SessionBuilder.DEFAULT_BPM
        val bars = opts["--bars"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in Session.VALID_BARS }
                ?: throw CliError("--bars wants one of ${Session.VALID_BARS.joinToString("/")}, got '$raw'")
        } ?: SessionBuilder.DEFAULT_BARS
        val loops = opts["--loop"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_DRONE_LOOPS } ?: throw CliError("--loop wants 1..$MAX_DRONE_LOOPS, got '$raw'")
        } ?: 1

        val session = SessionBuilder.empty(DRONE_SAMPLE_RATE, bpm, bars)
        val spec = DroneMaker.spec(v, patch.macros, motion, rate)
        val once = DroneMaker.render(spec, root, session)
        val all = FloatArray(once.size * loops) { once[it % once.size] }
        val name = Names.sanitizeStem("${patch.name}_DRONE_${Scales.nameOf(root)}")
        val file = File(dir, "$name.wav")
        FileOutputStream(file).use { WavWriter.write(it, Snip(all, channels = 1, sampleRate = session.sampleRate)) }
        out.println(
            "${DroneMaker.label(root, session)} · MOTION ${DroneMaker.motionLabel(motion)} · ${DroneMaker.breathsLabel(rate)} · " +
                "${"%.0f".format(Locale.ROOT, bpm)} BPM",
        )
        out.println("drone: ${file.path}" + if (loops > 1) " ($loops loops end to end)" else "")
        return 0
    }

    /** The rate every other WAV this command writes is at (the engines' own). */
    private const val DRONE_SAMPLE_RATE = 44_100

    /** Enough to hear several wraps; a cap so a typo can't write gigabytes. */
    private const val MAX_DRONE_LOOPS = 16
}
