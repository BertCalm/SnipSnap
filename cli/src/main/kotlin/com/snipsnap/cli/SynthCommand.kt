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
import com.snipsnap.shell.RegistersPadMaker
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePatch
import com.snipsnap.synth.EmbouchureVoice
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.RegistersPatch
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.RelayVoice
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.Spool
import com.snipsnap.synth.SpoolPatch
import com.snipsnap.synth.SpoolVoice
import com.snipsnap.synth.Revel
import com.snipsnap.synth.RevelPatch
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPatch
import com.snipsnap.synth.Tangle
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.Velocity
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentPatch
import com.snipsnap.synth.VentPresets
import com.snipsnap.synth.VentVoice
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Locale
import kotlin.random.Random

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
 * `--instrument [--attack S] [--release S]` (RESIN; REGISTERS accepts release only) renders the preset
 * held down instead: a keys instrument that sounds while a key is held.
 *
 * `--drone [--root A1] [--motion 0..1] [--rate 1|2|4] [--bpm N] [--bars N]
 * [--loop N]` (RESIN only) renders the preset as a loop-grid drone: the
 * whole loop, as long as the grid would make it at that tempo, written
 * [--loop] times end to end so the wrap can be heard.
 *
 * `--midi N` and `--velocity 0..1` (ALLOY, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY and the VENT/FLOCK/WICK prototypes) override
 * the preset's note and strike energy for a pad audition. EMBOUCHURE and BELLOWS --midi
 * chooses the played note directly by resetting the relative TUNE offset to zero.
 * SKITTER also accepts `--seed N` for its noise and `--scramble N` for a
 * repeatable macro variation around the selected preset.
 * OSPREY accepts `--seed N`, `--seconds .25..12` and `--model 1|2|3` for a finite SWELL gesture.
 * VENT accepts `--seed N` for its initial trajectory. Its provisional
 * starting recipes are available here while remaining outside the factory roster.
 * FLOCK GATHER/RIBBON/SPLIT also keep provisional recipes outside that roster;
 * `--seed N` accepts the full signed 64-bit range for their finite performances.
 * WICK likewise offers its nine provisional PLUCK/BUBBLE recipes without adding a phone engine.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--midi", "--velocity", "--seed", "--seconds", "--model", "--scramble", "--attack", "--release", "--root", "--motion", "--rate", "--bpm", "--bars", "--loop"),
            boolean = setOf("--all", "--instrument", "--drone"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val presets = when (engine) {
            VentPatch.ENGINE -> VentVoice.entries.firstOrNull { it.name == voice }?.let { VentPresets.forVoice(it) } ?: emptyList()
            FlockPatch.ENGINE -> FlockVoice.entries.firstOrNull { it.name == voice }?.let { FlockPresets.forVoice(it) } ?: emptyList()
            WickPatch.ENGINE -> WickVoice.entries.firstOrNull { it.name == voice }?.let { WickPresets.forVoice(it) } ?: emptyList()
            else -> Presets.forVoice(engine, voice)
        }
        if (presets.isEmpty()) throw CliError("no such engine/voice: $engine $voice")

        val instrument = opts.has("--instrument")
        val drone = opts.has("--drone")
        val noteFlags = listOf("--midi", "--velocity").filter { opts[it] != null }
        val noteRange = if (noteFlags.isEmpty()) null else {
            if (instrument || drone) throw CliError("${noteFlags.joinToString()} shape a pad audition - cannot combine with --instrument or --drone")
            when (engine) {
                AlloyPatch.ENGINE -> Alloy.MIDI_MIN..Alloy.MIDI_MAX
                CisternPatch.ENGINE -> Cistern.MIDI_MIN..Cistern.MIDI_MAX
                FlotillaPatch.ENGINE -> Flotilla.MIDI_MIN..Flotilla.MIDI_MAX
                TesseraPatch.ENGINE -> Tessera.ROOT_MIDI..(Tessera.ROOT_MIDI + Tessera.TUNE_SEMITONES)
                PitchwheelPatch.ENGINE -> Pitchwheel.MIDI_MIN..Pitchwheel.MIDI_MAX
                RegistersPatch.ENGINE -> 24..96
                TanglePatch.ENGINE -> Tangle.MIN_MIDI..Tangle.MAX_MIDI
                LacquerPatch.ENGINE -> Lacquer.MIDI_MIN..Lacquer.MIDI_MAX
                StoopPatch.ENGINE -> Stoop.MIDI_MIN..Stoop.MIDI_MAX
                SpoolPatch.ENGINE -> Spool.rootMidi(SpoolVoice.valueOf(voice)).let { root -> root..(root + Spool.TUNE_SEMITONES) }
                EmbouchurePatch.ENGINE -> Embouchure.midiRange(EmbouchureVoice.valueOf(voice))
                BellowsPatch.ENGINE -> Bellows.midiRange(BellowsVoice.valueOf(voice))
                MurmurPatch.ENGINE -> Murmur.MIN_MIDI..Murmur.MAX_MIDI
                OspreyPatch.ENGINE -> Osprey.MIDI_MIN..Osprey.MIDI_MAX
                RevelPatch.ENGINE -> Revel.ROOT_MIDI..(Revel.ROOT_MIDI + Revel.TUNE_SEMITONES)
                RelayPatch.ENGINE -> Relay.rootMidi(RelayVoice.valueOf(voice)).let { it..(it + Relay.TUNE_SEMITONES) }
                SkitterPatch.ENGINE -> Skitter.MIDI_MIN..Skitter.MIDI_MAX
                VentPatch.ENGINE -> Vent.MIDI_MIN..Vent.MIDI_MAX
                FlockPatch.ENGINE -> Flock.MIN_MIDI..Flock.MAX_MIDI
                WickPatch.ENGINE -> Wick.MIN_MIDI..Wick.MAX_MIDI
                PrismPatch.ENGINE -> {
                    val prismVoice = PrismVoice.valueOf(voice)
                    val root = Prism.rootMidi(prismVoice)
                    root..(root + Prism.TUNE_SEMITONES)
                }
                ContourPatch.ENGINE -> ContourEngine.rootMidi(ContourVoice.valueOf(voice)).let {
                    (it - ContourEngine.TUNE_SEMITONES)..(it + ContourEngine.TUNE_SEMITONES)
                }
                else -> throw CliError("${noteFlags.joinToString()} are only supported by ALLOY, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, VENT, FLOCK and WICK, got $engine")
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
        fun seedFlag(flag: String): Int? = opts[flag]?.let { raw ->
            val supported = if (flag == "--seed") setOf(SkitterPatch.ENGINE, VentPatch.ENGINE, OspreyPatch.ENGINE, FlockPatch.ENGINE) else setOf(SkitterPatch.ENGINE)
            if (engine !in supported) throw CliError("$flag is only supported by ${supported.joinToString(" and ")}, got $engine")
            raw.toIntOrNull() ?: throw CliError("$flag wants an integer seed, got '$raw'")
        }
        val flockSeedOverride = if (engine == FlockPatch.ENGINE) opts["--seed"]?.let { raw ->
            raw.toLongOrNull() ?: throw CliError("--seed for FLOCK wants a signed 64-bit integer, got '$raw'")
        } else null
        val seedOverride = if (engine == FlockPatch.ENGINE) null else seedFlag("--seed")
        val secondsOverride = opts["--seconds"]?.let { raw ->
            if (engine != OspreyPatch.ENGINE) throw CliError("--seconds is only supported by OSPREY, got $engine")
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in .25f..Osprey.MAX_SECONDS }
                ?: throw CliError("--seconds wants .25..${Osprey.MAX_SECONDS} seconds, got '$raw'")
        }
        val modelOverride = opts["--model"]?.let { raw ->
            if (engine != OspreyPatch.ENGINE) throw CliError("--model is only supported by OSPREY, got $engine")
            raw.toIntOrNull()?.takeIf { it in 1..Osprey.MODEL_VERSION }
                ?: throw CliError("--model wants 1..${Osprey.MODEL_VERSION}, got '$raw'")
        }
        val scrambleRandom = seedFlag("--scramble")?.let { Random(it) }
        if (!instrument && (opts["--attack"] != null || opts["--release"] != null)) {
            throw CliError("--attack and --release shape a held instrument - add --instrument")
        }
        if (instrument) {
            if (engine !in setOf("RESIN", RegistersPatch.ENGINE)) throw CliError("only RESIN and REGISTERS export a keys instrument here - try snipsnap synth REGISTERS PIPE --instrument")
            if (engine == RegistersPatch.ENGINE && opts["--attack"] != null) throw CliError("REGISTERS keeps its speaking attack - use --release to shape the held instrument")
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
            val withNote = if (noteFlags.isEmpty()) patch else when (patch) {
                is AlloyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is CisternPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FlotillaPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is PitchwheelPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is RegistersPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is StoopPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is LacquerPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is SpoolPatch -> patch.copy(
                    macros = midiOverride?.let { midi ->
                        patch.macros + ("TUNE" to (midi - Spool.rootMidi(patch.voice)) / Spool.TUNE_SEMITONES.toFloat())
                    } ?: patch.macros,
                    velocity = velocityOverride ?: patch.velocity,
                )
                is TanglePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is WickPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is RelayPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros +
                        ("TUNE" to (midiOverride - Relay.rootMidi(patch.voice)) / Relay.TUNE_SEMITONES.toFloat()),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is EmbouchurePatch -> patch.copy(
                    midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity,
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to 0f),
                )
                is BellowsPatch -> patch.copy(
                    midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity,
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to 0f),
                )
                is MurmurPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OspreyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is RevelPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros +
                        ("TUNE" to (midiOverride - Revel.ROOT_MIDI) / Revel.TUNE_SEMITONES.toFloat()),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is PrismPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros +
                        ("TUNE" to (midiOverride - Prism.rootMidi(patch.voice)) / Prism.TUNE_SEMITONES.toFloat()),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is ContourPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros +
                        ("TUNE" to (.5f + (midiOverride - ContourEngine.rootMidi(patch.voice)) /
                            (2f * ContourEngine.TUNE_SEMITONES))),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is SkitterPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is VentPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FlockPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is TesseraPatch -> if (midiOverride == null) patch else patch.copy(
                    macros = patch.macros + ("TUNE" to (midiOverride - Tessera.ROOT_MIDI) / Tessera.TUNE_SEMITONES.toFloat()),
                )
                else -> patch
            }
            if (withNote is SkitterPatch) {
                val withSeed = if (seedOverride == null) withNote else withNote.copy(seed = seedOverride)
                if (scrambleRandom == null) withSeed else withSeed.copy(
                    name = "Scrambled ${withSeed.name}",
                    macros = Skitter.scramble(withSeed.voice, scrambleRandom, near = withSeed),
                )
            } else if (withNote is OspreyPatch) withNote.copy(
                seed = seedOverride ?: withNote.seed,
                durationSeconds = secondsOverride ?: withNote.durationSeconds,
                modelVersion = modelOverride ?: withNote.modelVersion,
            ) else if (withNote is VentPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else if (withNote is FlockPatch && flockSeedOverride != null) withNote.copy(seed = flockSeedOverride)
            else withNote
        }

        if (instrument) return makeInstrument(voice, chosen.single(), opts, dir, out)
        if (drone) return makeDrone(voice, chosen.single(), opts, dir, out)

        for ((i, patch) in chosen.withIndex()) {
            // What SEND TO PAD would make: the string machines take their ENSEMBLE, so the file you audition is the pad you would get.
            val landing = Presets.landingFor(engine, voice, patch.macros)
            val snip = if (patch is TesseraPatch && velocityOverride != null) {
                val dry = Velocity.atVelocity(patch, velocityOverride)
                landing?.process(dry) ?: dry
            } else if (landing == null) patch.render() else PadRecipe(patch, landing).render()
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
        if (patch is RegistersPatch) {
            val spec = try {
                RegistersPadMaker.Spec(patch, releaseSeconds = seconds("--release", RegistersPadMaker.RELEASE.default))
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "bad --release")
            }
            val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
            val midis = RegistersPadMaker.zoneMidis(spec)
            val notes = midis.mapIndexed { i, midi ->
                out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
                RegistersPadMaker.renderZone(spec, midi)
            }
            RegistersPadMaker.export(name, spec, notes, dir)
            out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
            out.println("${midis.size} zones, each holds - speaking attack retained, RELEASE ${RegistersPadMaker.secondsLabel(spec.releaseSeconds)}")
            return 0
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
