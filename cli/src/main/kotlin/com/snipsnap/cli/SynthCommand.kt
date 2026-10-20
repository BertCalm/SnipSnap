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
import com.snipsnap.shell.OrphicaPadMaker
import com.snipsnap.shell.OpenSkyPadMaker
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.shell.RegistersPadMaker
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberPresets
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches
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
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPatch
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
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalMaterial
import com.snipsnap.synth.ShoalPatch
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPatch
import com.snipsnap.synth.Tangle
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.Velocity
import com.snipsnap.synth.Trio
import com.snipsnap.synth.TrioPatch
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentPatch
import com.snipsnap.synth.VentPresets
import com.snipsnap.synth.VentVoice
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.OhmPatch
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
 * `--instrument [--attack S] [--release S]` renders the preset as keys that
 * sound while held. RESIN accepts attack and release; ORPHICA, REGISTERS and
 * OPENSKY HALO keep their authored attack and accept release only.
 *
 * `--drone [--root A1] [--motion 0..1] [--rate 1|2|4] [--bpm N] [--bars N]
 * [--loop N]` (RESIN only) renders the preset as a loop-grid drone: the
 * whole loop, as long as the grid would make it at that tempo, written
 * [--loop] times end to end so the wrap can be heard.
 *
 * `--midi N` and `--velocity 0..1` (ORIGAMI, ALLOY, OVEN, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, CREASE, FLOCK, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, OBBLIGATO, ORPHICA, TRIO and the VENT/WICK prototypes) override
 * the preset's note and strike energy for a pad audition. EMBOUCHURE and BELLOWS --midi
 * chooses the played note directly by resetting the relative TUNE offset to zero.
 * SKITTER also accepts `--seed N` for its noise and `--scramble N` for a
 * repeatable macro variation around the selected preset.
 * OBBLIGATO accepts `--seed N` for its players and writes each WAV's complete
 * recipe beside it as a `.patch`, including its renderer model.
 * OPENSKY also accepts a saved `--patch <json>`, `--gate S`, and `--dry` /
 * `--wet`. The complete source timbre survives those overrides; OPENSKY HALO
 * can become a held instrument with `--instrument [--release S]`.
 * SHOAL accepts `--material HARMONIC|BOWL|WOOD|WIRE`. Material bodies are finite;
 * a finite-material override on a held preset is refused before any files are written.
 * CONTOUR accepts a saved `--patch <json>` and `--macros BODY=.2,EDGE=.8`.
 * Macro names are case-insensitive and values are finite 0..1. Complete saved
 * table/envelope configurations survive macro, note and velocity overrides.
 * TUNE in --macros cannot combine with --midi; choose one pitch control.
 * OSPREY accepts `--seed N`, `--seconds .25..12` and `--model 1|2|3` for a finite SWELL gesture.
 * CREASE also accepts `--model 1|2|3`, retaining its approved note, velocity and six controls.
 * VENT accepts `--seed N` for its initial trajectory. PULSE, COIL and PLUME
 * use the factory kit recipes; ROUND, WIRE and TEAR retain CLI-only model-1 starts.
 * FLOCK GATHER/RIBBON/SPLIT accept `--seed N` across the full signed 64-bit range
 * for their finite performances.
 * WICK offers thirteen provisional PLUCK/WOOD/BUBBLE recipes without adding a phone engine.
 * NACRE likewise auditions six provisional finite PEARL duets with native note and velocity.
 * ORPHICA accepts `--seed N` for repeatable string excitation and grains, preserving all 64 bits.
 * Its `--midi N` resets relative TUNE to neutral and also sets the sampled root with --instrument;
 * `--velocity` sets the string energy for both shapes.
 * CHAMBER offers finite HYBRID/AQUARIUM recipes, native note/velocity, full 64-bit
 * `--seed N`, and `--patch <json>` replay retaining the complete room configuration.
 * TRIO accepts `--tempo 60..200 --beats 1..4`; the finite gesture must fit within two seconds.
 * OHM STRING offers five finite family encounters, with --midi 48..72, --velocity and --seed.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        if (args.count { it.substringBefore('=') == "--macros" } > 1) {
            throw CliError("--macros supplies one comma-separated list; supply it only once")
        }
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--midi", "--velocity", "--material", "--seed", "--seconds", "--model", "--scramble", "--attack", "--release", "--root", "--motion", "--rate", "--bpm", "--bars", "--loop", "--patch", "--gate", "--macros", "--tempo", "--beats"),
            boolean = setOf("--all", "--instrument", "--drone", "--dry", "--wet"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val skyFlags = listOf("--gate", "--dry", "--wet").filter { opts.has(it) || opts[it] != null }
        if (skyFlags.isNotEmpty() && engine != OpenSkyPatch.ENGINE) {
            throw CliError("${skyFlags.joinToString()} are only supported by OPENSKY, got $engine")
        }
        if (opts["--patch"] != null && engine !in setOf(OpenSkyPatch.ENGINE, ContourPatch.ENGINE, ChamberPatch.ENGINE)) {
            throw CliError("--patch is only supported by OPENSKY, CONTOUR and CHAMBER, got $engine")
        }
        if (opts.has("--dry") && opts.has("--wet")) throw CliError("--dry and --wet are two render modes - pick one")
        val gateOverride = opts["--gate"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in OpenSkyPatch.GATE_SECONDS_MIN..OpenSkyPatch.GATE_SECONDS_MAX }
                ?: throw CliError("--gate wants finite seconds in ${OpenSkyPatch.GATE_SECONDS_MIN}..${OpenSkyPatch.GATE_SECONDS_MAX}, got '$raw'")
        }
        val wetOverride: Boolean? = when {
            opts.has("--dry") -> false
            opts.has("--wet") -> true
            else -> null
        }
        val savedPatch = opts["--patch"]?.let { path ->
            if (opts.has("--all") || opts["--preset"] != null) throw CliError("--patch supplies one sound - cannot combine with --preset or --all")
            val patch = try {
                Patches.fromJsonText(File(path).readText())
            } catch (e: Exception) {
                throw CliError("could not read --patch '$path': ${e.message ?: "invalid $engine patch"}")
            }
            if (patch.engine != engine) throw CliError("--patch records ${patch.engine}, requested $engine")
            if (patch.voiceName != voice) throw CliError("--patch records $engine ${patch.voiceName}, requested $voice")
            patch
        }

        val presets = when (engine) {
            VentPatch.ENGINE -> VentVoice.entries.firstOrNull { it.name == voice }?.let {
                if (Vent.defaultModelFor(it) == Vent.MODEL) VentPresets.forVoice(it) else Presets.forVoice(engine, voice)
            } ?: emptyList()
            WickPatch.ENGINE -> WickVoice.entries.firstOrNull { it.name == voice }?.let { WickPresets.forVoice(it) } ?: emptyList()
            ChamberPatch.ENGINE -> ChamberVoice.entries.firstOrNull { it.name == voice }?.let { ChamberPresets.forVoice(it) } ?: emptyList()
            NacrePatch.ENGINE -> NacreVoice.entries.firstOrNull { it.name == voice }?.let { NacrePresets.forVoice(it) } ?: emptyList()
            else -> Presets.forVoice(engine, voice)
        }
        if (presets.isEmpty()) throw CliError("no such engine/voice: $engine $voice")
        val materialOverride = opts["--material"]?.let { raw ->
            if (engine != ShoalPatch.ENGINE) throw CliError("--material is only supported by SHOAL, got $engine")
            ShoalMaterial.entries.firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
                ?: throw CliError("--material wants ${ShoalMaterial.entries.joinToString("|") { it.name }}, got '$raw'")
        }
        val contourMacros = opts["--macros"]?.let { raw ->
            if (engine != ContourPatch.ENGINE) throw CliError("--macros is only supported by CONTOUR, got $engine")
            val names = ContourEngine.macrosFor(ContourVoice.valueOf(voice)).map { it.name }.toSet()
            val overrides = linkedMapOf<String, Float>()
            for (entry in raw.split(',')) {
                val fields = entry.split('=')
                if (fields.size != 2) throw CliError("--macros wants NAME=VALUE pairs separated by commas, got '$entry'")
                val name = fields[0].trim().uppercase(Locale.ROOT)
                if (name !in names) throw CliError("--macros unknown CONTOUR control '$name'; choose ${names.joinToString()}")
                if (name in overrides) throw CliError("--macros repeats $name; supply each control once")
                val value = fields[1].trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                    ?: throw CliError("--macros $name wants a finite value in 0..1, got '${fields[1]}'")
                overrides[name] = value
            }
            if ("TUNE" in overrides && opts["--midi"] != null) {
                throw CliError("--macros TUNE and --midi both set pitch; choose one")
            }
            overrides
        } ?: emptyMap()

        val instrument = opts.has("--instrument")
        val drone = opts.has("--drone")
        val noteFlags = listOf("--midi", "--velocity").filter { opts[it] != null }
        val noteRange = if (noteFlags.isEmpty()) null else {
            if (drone || (instrument && engine !in setOf(OrphicaPatch.ENGINE, OpenSkyPatch.ENGINE))) throw CliError("${noteFlags.joinToString()} shape a pad audition - cannot combine with --instrument or --drone")
            when (engine) {
                OrigamiPatch.ENGINE -> Origami.MIDI_MIN..Origami.MIDI_MAX
                TrioPatch.ENGINE -> Trio.MIN_MIDI..Trio.MAX_MIDI
                AlloyPatch.ENGINE -> Alloy.MIDI_MIN..Alloy.MIDI_MAX
                OvenPatch.ENGINE -> Oven.MIDI_MIN..Oven.MIDI_MAX
                CisternPatch.ENGINE -> Cistern.MIDI_MIN..Cistern.MIDI_MAX
                FlotillaPatch.ENGINE -> Flotilla.MIDI_MIN..Flotilla.MIDI_MAX
                TesseraPatch.ENGINE -> Tessera.ROOT_MIDI..(Tessera.ROOT_MIDI + Tessera.TUNE_SEMITONES)
                PitchwheelPatch.ENGINE -> Pitchwheel.MIDI_MIN..Pitchwheel.MIDI_MAX
                RegistersPatch.ENGINE -> 24..96
                TanglePatch.ENGINE -> Tangle.MIN_MIDI..Tangle.MAX_MIDI
                CreasePatch.ENGINE -> Crease.MIDI_MIN..Crease.MIDI_MAX
                ShoalPatch.ENGINE -> Shoal.MIN_MIDI..Shoal.MAX_MIDI
                LacquerPatch.ENGINE -> Lacquer.MIDI_MIN..Lacquer.MIDI_MAX
                StoopPatch.ENGINE -> Stoop.MIDI_MIN..Stoop.MIDI_MAX
                SpoolPatch.ENGINE -> Spool.rootMidi(SpoolVoice.valueOf(voice)).let { root -> root..(root + Spool.TUNE_SEMITONES) }
                EmbouchurePatch.ENGINE -> Embouchure.midiRange(EmbouchureVoice.valueOf(voice))
                BellowsPatch.ENGINE -> Bellows.midiRange(BellowsVoice.valueOf(voice))
                MurmurPatch.ENGINE -> Murmur.MIN_MIDI..Murmur.MAX_MIDI
                ObbligatoPatch.ENGINE -> Obbligato.MIDI_MIN..Obbligato.MIDI_MAX
                OrphicaPatch.ENGINE -> Orphica.MIDI_MIN..Orphica.MIDI_MAX
                OspreyPatch.ENGINE -> Osprey.MIDI_MIN..Osprey.MIDI_MAX
                OpenSkyPatch.ENGINE -> OpenSkyPatch.MIDI_MIN..OpenSkyPatch.MIDI_MAX
                RevelPatch.ENGINE -> Revel.ROOT_MIDI..(Revel.ROOT_MIDI + Revel.TUNE_SEMITONES)
                RelayPatch.ENGINE -> Relay.rootMidi(RelayVoice.valueOf(voice)).let { it..(it + Relay.TUNE_SEMITONES) }
                SkitterPatch.ENGINE -> Skitter.MIDI_MIN..Skitter.MIDI_MAX
                VentPatch.ENGINE -> Vent.MIDI_MIN..Vent.MIDI_MAX
                FlockPatch.ENGINE -> Flock.MIN_MIDI..Flock.MAX_MIDI
                WickPatch.ENGINE -> Wick.MIN_MIDI..Wick.MAX_MIDI
                ChamberPatch.ENGINE -> Chamber.MIDI_MIN..Chamber.MIDI_MAX
                NacrePatch.ENGINE -> Nacre.MIN_MIDI..Nacre.MAX_MIDI
                OhmPatch.ENGINE -> 48..72
                PrismPatch.ENGINE -> {
                    val prismVoice = PrismVoice.valueOf(voice)
                    val root = Prism.rootMidi(prismVoice)
                    root..(root + Prism.TUNE_SEMITONES)
                }
                ContourPatch.ENGINE -> ContourEngine.rootMidi(ContourVoice.valueOf(voice)).let {
                    (it - ContourEngine.TUNE_SEMITONES)..(it + ContourEngine.TUNE_SEMITONES)
                }
                else -> throw CliError("${noteFlags.joinToString()} are only supported by ORIGAMI, ALLOY, OVEN, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, CREASE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, OBBLIGATO, OPENSKY, VENT, FLOCK, WICK, ORPHICA, NACRE, CHAMBER, TRIO and OHM, got $engine")
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
        val phraseFlags = listOf("--tempo", "--beats").filter { opts[it] != null }
        if (phraseFlags.isNotEmpty() && (engine != TrioPatch.ENGINE || instrument || drone)) {
            throw CliError("${phraseFlags.joinToString()} shape a finite TRIO pad audition")
        }
        val tempoOverride = opts["--tempo"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 60f..200f }
                ?: throw CliError("--tempo for TRIO is 60..200 BPM, got '$raw'")
        }
        val beatsOverride = opts["--beats"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..4 }
                ?: throw CliError("--beats for TRIO is 1..4, got '$raw'")
        }
        fun seedFlag(flag: String): Int? = opts[flag]?.let { raw ->
            val supported = if (flag == "--seed") setOf(SkitterPatch.ENGINE, VentPatch.ENGINE, OspreyPatch.ENGINE, FlockPatch.ENGINE, ChamberPatch.ENGINE, OrphicaPatch.ENGINE, ObbligatoPatch.ENGINE, OhmPatch.ENGINE) else setOf(SkitterPatch.ENGINE)
            if (engine !in supported) throw CliError("$flag is only supported by ${supported.joinToString(" and ")}, got $engine")
            raw.toIntOrNull() ?: throw CliError("$flag wants an integer seed, got '$raw'")
        }
        val longSeedOverride = if (engine in setOf(FlockPatch.ENGINE, ChamberPatch.ENGINE)) opts["--seed"]?.let { raw ->
            raw.toLongOrNull() ?: throw CliError("--seed for $engine wants a signed 64-bit integer, got '$raw'")
        } else null
        val orphicaSeedOverride = if (engine == OrphicaPatch.ENGINE) opts["--seed"]?.let { raw ->
            raw.toLongOrNull() ?: throw CliError("--seed wants a 64-bit integer seed, got '$raw'")
        } else null
        val seedOverride = if (engine in setOf(FlockPatch.ENGINE, ChamberPatch.ENGINE, OrphicaPatch.ENGINE)) null else seedFlag("--seed")
        val secondsOverride = opts["--seconds"]?.let { raw ->
            if (engine != OspreyPatch.ENGINE) throw CliError("--seconds is only supported by OSPREY, got $engine")
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in .25f..Osprey.MAX_SECONDS }
                ?: throw CliError("--seconds wants .25..${Osprey.MAX_SECONDS} seconds, got '$raw'")
        }
        val modelOverride = opts["--model"]?.let { raw ->
            val lastModel = when (engine) {
                OspreyPatch.ENGINE -> Osprey.MODEL_VERSION
                CreasePatch.ENGINE -> Crease.MODEL_VERSION
                else -> throw CliError("--model is only supported by OSPREY and CREASE, got $engine")
            }
            raw.toIntOrNull()?.takeIf { it in 1..lastModel }
                ?: throw CliError("--model wants 1..$lastModel, got '$raw'")
        }
        val scrambleRandom = seedFlag("--scramble")?.let { Random(it) }
        if (!instrument && (opts["--attack"] != null || opts["--release"] != null)) {
            throw CliError("--attack and --release shape a held instrument - add --instrument")
        }
        if (instrument) {
            if (engine !in setOf("RESIN", RegistersPatch.ENGINE, OpenSkyPatch.ENGINE, OrphicaPatch.ENGINE)) throw CliError("RESIN, REGISTERS, OPENSKY HALO and ORPHICA export a keys instrument here - try snipsnap synth REGISTERS PIPE --instrument")
            if (engine == RegistersPatch.ENGINE && opts["--attack"] != null) throw CliError("REGISTERS keeps its authored attack - use --release to shape the held instrument")
            if (engine == OrphicaPatch.ENGINE && opts["--attack"] != null) throw CliError("ORPHICA keeps the pluck attack; --instrument accepts --release")
            if (opts.has("--all")) throw CliError("--instrument makes one instrument per call - pick a --preset, not --all")
            if (engine == OpenSkyPatch.ENGINE) {
                if (!OpenSkyPadMaker.supports(OpenSkyVoice.valueOf(voice))) throw CliError("--instrument supports OPENSKY HALO; $voice is a finite sound")
                if (opts["--attack"] != null) throw CliError("--attack is RESIN only; OPENSKY HALO keeps the patch's authored arrival")
                if (gateOverride != null) throw CliError("--gate shapes a finite OPENSKY sound - cannot combine with --instrument")
            }
        }
        val skyInstrumentRelease = if (instrument && engine == OpenSkyPatch.ENGINE) opts["--release"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in OpenSkyPadMaker.RELEASE_MIN_SECONDS..OpenSkyPadMaker.RELEASE_MAX_SECONDS }
                ?: throw CliError("--release wants finite seconds in ${OpenSkyPadMaker.RELEASE_MIN_SECONDS}..${OpenSkyPadMaker.RELEASE_MAX_SECONDS}, got '$raw'")
        } else null
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

        val chosen: List<Patch> = (when {
            savedPatch != null -> listOf(savedPatch)
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
                is OrigamiPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is TrioPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is AlloyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OvenPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
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
                is CreasePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is WickPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is ChamberPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is NacrePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OhmPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is ShoalPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
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
                is ObbligatoPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OrphicaPatch -> patch.copy(
                    config = patch.config.copy(
                        midi = midiOverride ?: patch.config.midi,
                        velocity = velocityOverride ?: patch.config.velocity,
                    ),
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to .5f),
                )
                is OspreyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OpenSkyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
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
            if (withNote is OpenSkyPatch) {
                withNote.copy(gateSeconds = gateOverride ?: withNote.gateSeconds, wet = wetOverride ?: withNote.wet)
            } else if (withNote is ShoalPatch && materialOverride != null) {
                if (!Shoal.supportsHold(Shoal.MODEL_VERSION, materialOverride) &&
                    Shoal.isLoop(withNote.macros["HOLD"] ?: 0f)) {
                    throw CliError("SHOAL $materialOverride renders finite notes; --material cannot override a HOLD preset")
                }
                withNote.copy(model = Shoal.MODEL_VERSION, material = materialOverride)
            } else if (withNote is ContourPatch && contourMacros.isNotEmpty()) {
                withNote.copy(macros = withNote.macros + contourMacros)
            } else if (withNote is SkitterPatch) {
                val withSeed = if (seedOverride == null) withNote else withNote.copy(seed = seedOverride)
                if (scrambleRandom == null) withSeed else withSeed.copy(
                    name = "Scrambled ${withSeed.name}",
                    macros = Skitter.scramble(withSeed.voice, scrambleRandom, near = withSeed),
                )
            } else if (withNote is TrioPatch && phraseFlags.isNotEmpty()) {
                val tempo = tempoOverride ?: withNote.config.tempoBpm
                val beats = beatsOverride ?: withNote.config.beats
                if (beats * 60f / tempo > 2f) {
                    throw CliError("TRIO's $beats beats at $tempo BPM exceed the two-second gesture window; raise --tempo or lower --beats")
                }
                withNote.copy(config = withNote.config.copy(tempoBpm = tempo, beats = beats))
            } else if (withNote is OrphicaPatch && orphicaSeedOverride != null) {
                withNote.copy(config = withNote.config.copy(seed = orphicaSeedOverride))
            } else if (withNote is OspreyPatch) withNote.copy(
                seed = seedOverride ?: withNote.seed,
                durationSeconds = secondsOverride ?: withNote.durationSeconds,
                modelVersion = modelOverride ?: withNote.modelVersion,
            ) else if (withNote is CreasePatch) withNote.copy(
                model = modelOverride ?: withNote.model,
            ) else if (withNote is VentPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else if (withNote is FlockPatch && longSeedOverride != null) withNote.copy(seed = longSeedOverride)
            else if (withNote is ChamberPatch && longSeedOverride != null) withNote.copy(seed = longSeedOverride)
            else if (withNote is ObbligatoPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else if (withNote is OhmPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else withNote
        }

        val skyInstrumentSpec = if (instrument && engine == OpenSkyPatch.ENGINE) try {
            OpenSkyPadMaker.Spec(chosen.single() as OpenSkyPatch, releaseSeconds = skyInstrumentRelease ?: OpenSkyPadMaker.RELEASE.default)
        } catch (e: IllegalArgumentException) {
            throw CliError("--instrument / --midi: ${e.message ?: "invalid OPENSKY instrument"}")
        } else null
        // Resolve every recipe and instrument specification before creating output files.
        val dir = File(dirArg)
        if (!dir.isDirectory && !dir.mkdirs()) throw CliError("could not make the output folder: $dirArg")
        if (skyInstrumentSpec != null) return makeOpenSkyInstrument(skyInstrumentSpec, dir, out)
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
            val family = if (patch is ShoalPatch) "${voice}_${patch.material.name}" else voice
            val file = File(dir, "%s_%s_%02d_%s.wav".format(Locale.ROOT, engine, family, i + 1, safe))
            FileOutputStream(file).buffered().use { WavWriter.write(it, snip) }
            if (patch is ObbligatoPatch) File(dir, "${file.nameWithoutExtension}.patch").writeText(patch.toJsonText())
            val mode = when {
                patch is OpenSkyPatch -> if (patch.wet) "  (room + shimmer, stereo)" else "  (dry, stereo)"
                landing != null -> "  (lands with ENSEMBLE, stereo)"
                else -> ""
            }
            out.println("${file.name}  ${"%.2f".format(Locale.ROOT, snip.durationSeconds)}s$mode")
        }
        out.println("${chosen.size} rendered into ${dir.path}")
        return 0
    }

    private fun makeOpenSkyInstrument(spec: OpenSkyPadMaker.Spec, dir: File, out: PrintStream): Int {
        val patch = spec.patch
        val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
        val midis = OpenSkyPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            OpenSkyPadMaker.renderZone(spec, midi)
        }
        OpenSkyPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, each holds - RELEASE ${OpenSkyPadMaker.secondsLabel(spec.releaseSeconds)}, stereo")
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
        if (patch is OrphicaPatch) {
            val spec = try {
                OrphicaPadMaker.Spec(patch.config, patch.macros, seconds("--release", OrphicaPadMaker.RELEASE.default))
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "bad --release")
            }
            val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
            val midis = OrphicaPadMaker.zoneMidis(spec)
            val notes = midis.mapIndexed { i, midi ->
                out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
                OrphicaPadMaker.renderZone(spec, midi)
            }
            OrphicaPadMaker.export(name, spec, notes, dir)
            out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
            out.println("${midis.size} stereo zones, captured sustain - RELEASE ${OrphicaPadMaker.secondsLabel(spec.releaseSeconds)}")
            return 0
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
            out.println("${midis.size} zones, each holds - authored attack retained, RELEASE ${RegistersPadMaker.secondsLabel(spec.releaseSeconds)}")
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
        FileOutputStream(file).buffered().use { WavWriter.write(it, Snip(all, channels = 1, sampleRate = session.sampleRate)) }
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
