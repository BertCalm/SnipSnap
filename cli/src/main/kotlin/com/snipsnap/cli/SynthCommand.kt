package com.snipsnap.cli

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.PadRecipe
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
 * A preset that lands with a rack chain (the string machines - VELVET BRASS
 * STRING MACHINE and THIN STRINGS, RESIN BRASS WIDE STRINGS and DARK STRINGS)
 * renders through it, as SEND TO PAD would, and comes out a stereo file.
 * `--instrument` and `--drone` rebuild from the preset's macros alone and stay
 * dry: an instrument is nine held zones and a drone a loop, each with its own
 * shape, and neither is a one-shot pad.
 *
 * `--instrument [--attack S] [--release S]` (RESIN only) renders the preset
 * held down instead: a keys instrument that sounds while a key is held.
 *
 * `--drone [--root A1] [--motion 0..1] [--rate 1|2|4] [--bpm N] [--bars N]
 * [--loop N]` (RESIN only) renders the preset as a loop-grid drone: the
 * whole loop, as long as the grid would make it at that tempo, written
 * [--loop] times end to end so the wrap can be heard.
 *
 * `--midi N` and `--velocity 0..1` (CISTERN and FLOTILLA only) override
 * the preset's note and strike energy for a pad audition.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--midi", "--velocity", "--attack", "--release", "--root", "--motion", "--rate", "--bpm", "--bars", "--loop"),
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
        val drone = opts.has("--drone")
        val noteFlags = listOf("--midi", "--velocity").filter { opts[it] != null }
        val noteRange = if (noteFlags.isEmpty()) null else {
            if (instrument || drone) throw CliError("${noteFlags.joinToString()} shape a pad audition - cannot combine with --instrument or --drone")
            when (engine) {
                CisternPatch.ENGINE -> Cistern.MIDI_MIN..Cistern.MIDI_MAX
                FlotillaPatch.ENGINE -> Flotilla.MIDI_MIN..Flotilla.MIDI_MAX
                else -> throw CliError("${noteFlags.joinToString()} are only supported by CISTERN and FLOTILLA, got $engine")
            }
        }
        val midiOverride = opts["--midi"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in noteRange!! }
                ?: throw CliError("--midi for $engine is ${noteRange!!.first}..${noteRange.last}, got '$raw'")
        }
        val velocityOverride = opts["--velocity"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                ?: throw CliError("--velocity wants a finite value in 0..1, got '$raw'")
        }
        if (!instrument && (opts["--attack"] != null || opts["--release"] != null)) {
            throw CliError("--attack and --release shape a held instrument - add --instrument")
        }
        if (instrument) {
            if (engine != "RESIN") throw CliError("only RESIN exports a keys instrument here - try snipsnap synth RESIN BRASS --instrument")
            if (opts.has("--all")) throw CliError("--instrument makes one instrument per call - pick a --preset, not --all")
        }
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

        val chosen: List<Patch> = (when {
            opts.has("--all") -> presets
            opts["--preset"] != null -> {
                val n = opts["--preset"]!!.toIntOrNull()
                    ?: throw CliError("--preset wants a number, got '${opts["--preset"]}'")
                if (n !in 1..presets.size) throw CliError("--preset is 1..${presets.size} for $engine $voice, got $n")
                listOf(presets[n - 1])
            }
            else -> listOf(presets.first())
        }).map { patch ->
            if (noteFlags.isEmpty()) patch else when (patch) {
                is CisternPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FlotillaPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                else -> patch
            }
        }

        if (instrument) return makeInstrument(voice, chosen.single(), opts, dir, out)
        if (drone) return makeDrone(voice, chosen.single(), opts, dir, out)

        for ((i, patch) in chosen.withIndex()) {
            // What SEND TO PAD would make: the string machines take their ENSEMBLE, so the file you audition is the pad you would get.
            val landing = Presets.landingFor(engine, voice, patch.macros)
            val snip = if (landing == null) patch.render() else PadRecipe(patch, landing).render()
            val safe = patch.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "PRESET" }
            val file = File(dir, "%s_%s_%02d_%s.wav".format(Locale.ROOT, engine, voice, i + 1, safe))
            FileOutputStream(file).use { WavWriter.write(it, snip) }
            out.println("${file.name}  ${"%.2f".format(Locale.ROOT, snip.durationSeconds)}s" + if (landing == null) "" else "  (lands with ENSEMBLE, stereo)")
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

        val session = SessionBuilder.empty(WavWriter.MPC_SAMPLE_RATE, bpm, bars)
        val spec = DroneMaker.spec(v, patch.macros, motion, rate)
        val once = DroneMaker.render(spec, root, session)
        val all = FloatArray(once.size * loops) { once[it % once.size] }
        val name = Names.sanitizeStem("${patch.name}_DRONE_${Scales.nameOf(root)}")
        val file = File(dir, "$name.wav")
        FileOutputStream(file).use { WavWriter.write(it, Snip(all, channels = 1, sampleRate = session.sampleRate)) }
        // ASCII, like every other line this CLI prints: a terminal that
        // isn't UTF-8 shows the app's middle dots and cent signs as '?'.
        val span = DroneMaker.span(root, session)
        val around = span * session.barsPerInterval
        val cents = DroneFit.nudgeCents(root, span, session)
        out.println(
            "%s - %d %s - %+.2f cents - MOTION +/-%.1f oct - %s - %.0f BPM".format(
                Locale.ROOT, Scales.nameOf(root), around, if (around == 1) "bar" else "bars", cents,
                motion * ResinDrone.MOTION_MAX_OCTAVES, DroneMaker.breathsLabel(rate), bpm,
            ),
        )
        out.println("drone: ${file.path}" + if (loops > 1) " ($loops loops end to end)" else "")
        return 0
    }

    /** Enough to hear several wraps; a cap so a typo can't write gigabytes. */
    private const val MAX_DRONE_LOOPS = 16
}
