package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.FlockPadMaker
import com.snipsnap.shell.RimePadMaker
import com.snipsnap.shell.OverlapPadMaker
import com.snipsnap.shell.ContourPadMaker
import com.snipsnap.shell.OrphicaPadMaker
import com.snipsnap.shell.TidalPadMaker
import com.snipsnap.shell.OrigamiPadMaker
import com.snipsnap.shell.OpenSkyPadMaker
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.shell.RegistersPadMaker
import com.snipsnap.shell.CreasePadMaker
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisVoice
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianVoice
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiGestures
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberPresets
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapHeld
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapPlayback
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticeVoice
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Otis
import com.snipsnap.synth.OtisPatch
import com.snipsnap.synth.OtisVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
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
import com.snipsnap.synth.Ferry
import com.snipsnap.synth.Lorentz
import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.LorentzVoice
import com.snipsnap.synth.FerryPatch
import com.snipsnap.synth.FerryPresets
import com.snipsnap.synth.FerryVoice
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
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.Rime
import com.snipsnap.synth.RimeVoice
import com.snipsnap.synth.RimePatch
import com.snipsnap.synth.RimePresets
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
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalVoice
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
import com.snipsnap.synth.Ohm
import com.snipsnap.shell.OhmPadMaker
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
 * `--instrument` and `--drone` stay dry. An instrument contains held zones
 * and a drone a loop, each with its own shape. Recipe-based instruments
 * (CREASE, REGISTERS and OPENSKY among them) keep the complete source
 * configuration, including its authoring velocity, rather than
 * reconstructing only the macros.
 *
 * `--instrument [--attack S] [--release S]` renders the preset as keys that
 * sound while held. RESIN accepts attack and release; CREASE, ORPHICA, REGISTERS,
 * ORIGAMI model-4 FAN/STILL, OPENSKY HALO and FLOCK RIBBON keep their authored attack and accept release only.
 * ORIGAMI also accepts --patch JSON replay, retaining the saved note, velocity,
 * seed and finite render model. Its held zones span C3-C5 and override TUNE.
 * Its model-4 voices accept --gesture STEADY|BLOOM|UNFOLD and all six controls
 * through --macros; MOTION sets gesture duration in BLOOM/UNFOLD.
 * --expression LEVEL|FOLD keeps the existing level response by default, or lets
 * strike velocity scale the chosen fold. FOLD instruments export three velocity layers.
 * ALLOY accepts saved --patch JSON recipes, retaining their complete configuration
 * and HOLD setting while --midi and --velocity override note and strike energy.
 * CONTOUR exports seventeen settled-sustain zones with release only, omitting its
 * finite key/pluck attack.
 *
 * `--drone [--root A1] [--motion 0..1] [--rate 1|2|4] [--bpm N] [--bars N]
 * [--loop N]` (RESIN only) renders the preset as a loop-grid drone: the
 * whole loop, as long as the grid would make it at that tempo, written
 * [--loop] times end to end so the wrap can be heard.
 *
 * `--midi N` and `--velocity 0..1` (APSIS, ORIGAMI, FILAMENT, ALLOY, CAUSTIC, OVEN, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, LATTICE, SPOOL, SKITTER, TANGLE, CREASE, FLOCK, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, SCANNED_CHAIN, OSPREY, OBBLIGATO, ORPHICA, TRIO and the VENT/WICK prototypes) override
 * the preset's note and strike energy for a pad audition. EMBOUCHURE and BELLOWS --midi
 * chooses the played note directly by resetting the relative TUNE offset to zero.
 * SKITTER also accepts `--seed N` for its noise and `--scramble N` for a
 * repeatable macro variation around the selected preset.
 * OBBLIGATO accepts `--seed N` for its players and writes each WAV's complete
 * recipe beside it as a `.patch`, including its renderer model.
 * OPENSKY also accepts a saved `--patch <json>`, `--gate S`, and `--dry` /
 * `--wet`. The complete source timbre survives those overrides; OPENSKY HALO
 * can become a held instrument with `--instrument [--release S]`.
 * CREASE accepts saved `--patch <json>` recipes. Model 3 can become a held
 * instrument with `--instrument [--release S]`; the saved root and velocity
 * choose its sample bank and excitation. Earlier models need an explicit
 * `--model 3` override rather than being silently upgraded.
 * `--touch` adds soft/mid/hard excitation layers up to that saved velocity;
 * the usual player gain still follows the live key touch.
 * SHOAL accepts `--material HARMONIC|BOWL|WOOD|WIRE`. Material bodies are finite;
 * a finite-material override on a held preset is refused before any files are written.
 * CONTOUR accepts a saved `--patch <json>` and `--macros BODY=.2,EDGE=.8`.
 * Macro names are case-insensitive and values are finite 0..1. Complete saved
 * table/envelope configurations survive macro, note and velocity overrides.
 * TUNE in --macros cannot combine with --midi; choose one pitch control.
 * MERIDIAN SWEEP has an eight-preset dry bank, native
 * `--midi` / `--velocity`, `--seed`, `--macros LATITUDE=.8,MEMORY=.4`, and saved
 * `--patch <json>` and `--model 1|2`. Every WAV has a complete `.patch` recipe beside
 * it. Model 2 HOLD >= .975 exports a fixed-alignment sustain with WAV sampler
 * markers; model 1 retains the original finite gesture at every setting.
 * `--instrument [--release S] [--velocity 0..1]` exports the complete CONTOUR
 * recipe as a held keyboard. Its zones set TUNE and HOLD, so explicit
 * --midi and --macros TUNE/HOLD edits are refused in this mode.
 * OSPREY accepts `--seed N`, `--seconds .25..12` and `--model 1|2|3` for a finite SWELL gesture.
 * CREASE also accepts `--model 1|2|3`, retaining its approved note, velocity and six controls.
 * All four CAUSTIC voices accept `--model 1`, saved `--patch <json>` and `--macros` overrides,
 * retaining the patch's explicit note, velocity and native renderer model. It renders a
 * finite note; HOLD is reserved and must stay at zero.
 * LATTICE WARM/PLUCK/HOLLOW accepts existing `--patch` and `--macros` options.
 * Its `--midi 36..84` resets relative TUNE to .5; velocity-only edits keep saved tuning.
 * Every LATTICE WAV has a complete `.patch` beside it, including held-loop settings.
 * VENT accepts `--seed N` for its initial trajectory. PULSE, COIL and PLUME
 * use the factory kit recipes; ROUND, WIRE and TEAR retain CLI-only model-1 starts.
 * FLOCK GATHER/RIBBON/SPLIT accept `--seed N` across the full signed 64-bit range
 * for their finite performances. FLOCK also replays `--patch <json>`; RIBBON
 * becomes a captured-sustain instrument with `--instrument [--release S] [--touch]`,
 * retaining its exact model, seed and saved note/velocity. `--touch` adds three
 * independently captured source strengths up to the saved velocity.
 * RIME replays complete saved patches and exposes --midi, --velocity, --seconds
 * (excitation gate), --seed and all six --macros. Models 1–4 remain finite;
 * explicitly choose --model 5 for HOLD or --instrument [--release S]. Held keys
 * retain the unfolding opening before periodic sustain across nine native roots.
 * WICK offers twenty-five provisional PLUCK/WOOD/BREATH/BUBBLE/FLICKER/RATTLE recipes without adding a phone engine.
 * NACRE offers three playable finite PEARL starting points with native note and velocity.
 * FILAMENT exposes twelve approved THREAD/PULSE/CURL factory recipes with saved geometry and note/velocity overrides.
 * ORPHICA accepts `--seed N` for repeatable string excitation and grains, preserving all 64 bits.
 * Its `--midi N` resets relative TUNE to neutral and also sets the sampled root with --instrument;
 * `--velocity` sets the string energy for both shapes.
 * CHAMBER offers finite HYBRID/AQUARIUM recipes, native note/velocity, full 64-bit
 * `--seed N`, and `--patch <json>` replay retaining the complete room configuration.
 * APSIS supports complete --patch replay, signed 32-bit --seed, and six --macros controls.
 * TIDAL offers PAIR/STREAM factory encounters and reconstructed held keys with native note/velocity,
 * exact 64-bit --seed, versioned models, complete --patch replay, --macros HOLD=1,
 * and --instrument with the authored encounter before a reconstructed sustain.
 * TRIO accepts `--tempo 60..200 --beats 1..4`; the beat grid must fit within two seconds; answers may continue after it.
 * OTIS offers the sixteen auditioned note, chord and phrase recipes. --patch, --midi,
 * --velocity, signed32-bit --seed and six --macros controls retain the complete score.
 * LENGTH scales finite timing; there is no held-keyboard mode.
 * OHM STRING offers five finite family encounters, with --midi 48..72, --velocity and --seed.
 * OVERLAP replays complete --patch recipes with note/velocity overrides. A saved HELD
 * recipe also exports three native reference zones with --instrument [--release S].
 * Finite OVERLAP recipes keep their original playback; select held mode in SYNTH first.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        if (args.count { it.substringBefore('=') == "--macros" } > 1) {
            throw CliError("--macros supplies one comma-separated list; supply it only once")
        }
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out", "--midi", "--velocity", "--material", "--seed", "--seconds", "--model", "--scramble", "--attack", "--release", "--root", "--motion", "--rate", "--bpm", "--bars", "--loop", "--patch", "--gate", "--macros", "--gesture", "--expression", "--tempo", "--beats"),
            boolean = setOf("--all", "--instrument", "--touch", "--drone", "--dry", "--wet"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val gestureOverride = opts["--gesture"]?.let { raw ->
            if (engine != OrigamiPatch.ENGINE) throw CliError("--gesture is only supported by ORIGAMI, got $engine")
            OrigamiGesture.entries.firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
                ?: throw CliError("--gesture wants ${OrigamiGesture.entries.joinToString("|") { it.name }}, got '$raw'")
        }
        val expressionOverride = opts["--expression"]?.let { raw ->
            if (engine != OrigamiPatch.ENGINE) throw CliError("--expression is only supported by ORIGAMI, got $engine")
            OrigamiExpression.entries.firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
                ?: throw CliError("--expression wants ${OrigamiExpression.entries.joinToString("|") { it.name }}, got '$raw'")
        }
        val skyFlags = listOf("--gate", "--dry", "--wet").filter { opts.has(it) || opts[it] != null }
        if (skyFlags.isNotEmpty() && engine != OpenSkyPatch.ENGINE) {
            throw CliError("${skyFlags.joinToString()} are only supported by OPENSKY, got $engine")
        }
        if (opts["--patch"] != null && engine !in setOf(LorentzPatch.ENGINE, RimePatch.ENGINE, ApsisPatch.ENGINE, AlloyPatch.ENGINE, OpenSkyPatch.ENGINE, ContourPatch.ENGINE, ChamberPatch.ENGINE, TidalPatch.ENGINE, CausticPatch.ENGINE, CreasePatch.ENGINE, OrigamiPatch.ENGINE, FlockPatch.ENGINE, OverlapPatch.ENGINE, LatticePatch.ENGINE, OhmPatch.ENGINE, FerryPatch.ENGINE, MeridianPatch.ENGINE, OtisPatch.ENGINE)) {
            throw CliError("--patch is only supported by RIME, APSIS, ALLOY, OPENSKY, CONTOUR, CHAMBER, TIDAL, CAUSTIC, CREASE, ORIGAMI, FLOCK, OVERLAP, LATTICE, OHM, FERRY, MERIDIAN, OTIS and LORENTZ, got $engine")
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
            RimePatch.ENGINE -> RimeVoice.entries.firstOrNull { it.name == voice }?.let { RimePresets.forVoice(it) } ?: emptyList()
            FerryPatch.ENGINE -> FerryVoice.entries.firstOrNull { it.name == voice }?.let { FerryPresets.forVoice(it) } ?: emptyList()
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
        val macroOverrides = opts["--macros"]?.let { raw ->
            val names = when (engine) {
                RimePatch.ENGINE -> Rime.macrosFor(RimeVoice.valueOf(voice))
                OtisPatch.ENGINE -> Otis.macrosFor(OtisVoice.valueOf(voice))
                LorentzPatch.ENGINE -> Lorentz.macrosFor(LorentzVoice.valueOf(voice))
                FerryPatch.ENGINE -> Ferry.macrosFor(FerryVoice.valueOf(voice))
                TidalPatch.ENGINE -> Tidal.macrosFor(TidalVoice.valueOf(voice))
                ApsisPatch.ENGINE -> Apsis.macrosFor(ApsisVoice.valueOf(voice))
                ContourPatch.ENGINE -> ContourEngine.macrosFor(ContourVoice.valueOf(voice))
                MeridianPatch.ENGINE -> Meridian.macrosFor(MeridianVoice.valueOf(voice))
                LatticePatch.ENGINE -> Lattice.macrosFor(LatticeVoice.valueOf(voice))
                OrigamiPatch.ENGINE -> Origami.macrosFor(OrigamiVoice.valueOf(voice))
                CausticPatch.ENGINE -> CausticInstrument.macrosFor(CausticVoice.valueOf(voice))
                "PLUCK" -> if (voice == "UPRIGHT") Pluck.macrosFor(PluckVoice.UPRIGHT)
                    else throw CliError("--macros for PLUCK requires UPRIGHT")
                OhmPatch.ENGINE -> Ohm.macrosFor(Ohm.MODEL_VERSION)
                else -> throw CliError("--macros is only supported by MERIDIAN, RIME, OTIS, TIDAL, APSIS, CONTOUR, ORIGAMI, CAUSTIC, LATTICE, OHM, FERRY, LORENTZ and PLUCK UPRIGHT, got $engine")
            }.map { it.name }.toSet()
            val overrides = linkedMapOf<String, Float>()
            for (entry in raw.split(',')) {
                val fields = entry.split('=')
                if (fields.size != 2) throw CliError("--macros wants NAME=VALUE pairs separated by commas, got '$entry'")
                val name = fields[0].trim().uppercase(Locale.ROOT)
                if (name !in names) throw CliError("--macros unknown $engine control '$name'; choose ${names.joinToString()}")
                if (name in overrides) throw CliError("--macros repeats $name; supply each control once")
                val value = fields[1].trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                    ?: throw CliError("--macros $name wants a finite value in 0..1, got '${fields[1]}'")
                if (engine == CausticPatch.ENGINE && name == "HOLD" && value > 0f) {
                    throw CliError("--macros HOLD is reserved for CAUSTIC; only 0 is supported")
                }
                if (engine == FerryPatch.ENGINE && name == "HOLD" && fields[1].trim().toDoubleOrNull() != 0.0) {
                    throw CliError("FERRY is finite; HOLD must be 0")
                }
                if (engine == TidalPatch.ENGINE && name == "HOLD" && fields[1].trim().toDoubleOrNull() !in listOf(0.0, 1.0)) {
                    throw CliError("TIDAL HOLD is a NOTE/HOLD switch; choose 0 or 1")
                }
                overrides[name] = value
            }
            if ("TUNE" in overrides && opts["--midi"] != null) {
                throw CliError("--macros TUNE and --midi both set pitch; choose one")
            }
            overrides
        } ?: emptyMap()

        val instrument = opts.has("--instrument")
        val drone = opts.has("--drone")
        if (engine == OrigamiPatch.ENGINE && drone) {
            throw CliError("--drone is not supported by ORIGAMI; use --instrument for model-4 FAN or STILL")
        }
        if (opts.has("--touch") && (!instrument || engine !in setOf(CreasePatch.ENGINE, FlockPatch.ENGINE))) {
            throw CliError("--touch requires CREASE --instrument or FLOCK RIBBON --instrument")
        }
        val noteFlags = listOf("--midi", "--velocity").filter { opts[it] != null }
        if (instrument && engine == ContourPatch.ENGINE) {
            if (opts["--midi"] != null) throw CliError("CONTOUR --instrument samples the full four-octave keyboard; --midi shapes a finite audition")
            if (macroOverrides.keys.any { it == "TUNE" || it == "HOLD" }) {
                throw CliError("CONTOUR --instrument sets TUNE and HOLD for every zone; omit those --macros edits")
            }
        }
        val noteRange = if (noteFlags.isEmpty()) null else {
            if (drone || (instrument && engine !in setOf(RimePatch.ENGINE, OrphicaPatch.ENGINE, OpenSkyPatch.ENGINE, CreasePatch.ENGINE, ContourPatch.ENGINE, OrigamiPatch.ENGINE, FlockPatch.ENGINE, OverlapPatch.ENGINE, OhmPatch.ENGINE, TidalPatch.ENGINE))) throw CliError("${noteFlags.joinToString()} shape a pad audition - cannot combine with --instrument or --drone")
            when (engine) {
                RimePatch.ENGINE -> Rime.MIN_MIDI..Rime.MAX_MIDI
                OtisPatch.ENGINE -> Otis.MIDI_MIN..Otis.MIDI_MAX
                LorentzPatch.ENGINE -> Lorentz.MIN_MIDI..Lorentz.MAX_MIDI
                "PLUCK" -> if (voice == "UPRIGHT") 28..52
                    else throw CliError("${noteFlags.joinToString()} for PLUCK requires UPRIGHT")
                ApsisPatch.ENGINE -> Apsis.MIDI_MIN..Apsis.MIDI_MAX
                OrigamiPatch.ENGINE -> Origami.MIDI_MIN..Origami.MIDI_MAX
                TrioPatch.ENGINE -> Trio.MIN_MIDI..Trio.MAX_MIDI
                AlloyPatch.ENGINE -> Alloy.MIDI_MIN..Alloy.MIDI_MAX
                MeridianPatch.ENGINE -> Meridian.MIDI_MIN..Meridian.MIDI_MAX
                OvenPatch.ENGINE -> Oven.MIDI_MIN..Oven.MIDI_MAX
                CisternPatch.ENGINE -> Cistern.MIDI_MIN..Cistern.MIDI_MAX
                CausticPatch.ENGINE -> CausticInstrument.MIDI_MIN..CausticInstrument.MIDI_MAX
                FlotillaPatch.ENGINE -> Flotilla.MIDI_MIN..Flotilla.MIDI_MAX
                TesseraPatch.ENGINE -> Tessera.ROOT_MIDI..(Tessera.ROOT_MIDI + Tessera.TUNE_SEMITONES)
                PitchwheelPatch.ENGINE -> Pitchwheel.MIDI_MIN..Pitchwheel.MIDI_MAX
                RegistersPatch.ENGINE -> 24..96
                TanglePatch.ENGINE -> Tangle.MIN_MIDI..Tangle.MAX_MIDI
                CreasePatch.ENGINE -> Crease.MIDI_MIN..Crease.MIDI_MAX
                ShoalPatch.ENGINE -> Shoal.MIN_MIDI..Shoal.MAX_MIDI
                LacquerPatch.ENGINE -> Lacquer.MIDI_MIN..Lacquer.MIDI_MAX
                LatticePatch.ENGINE -> Lattice.MIDI_MIN..Lattice.MIDI_MAX
                StoopPatch.ENGINE -> Stoop.MIDI_MIN..Stoop.MIDI_MAX
                SpoolPatch.ENGINE -> Spool.rootMidi(SpoolVoice.valueOf(voice)).let { root -> root..(root + Spool.TUNE_SEMITONES) }
                EmbouchurePatch.ENGINE -> Embouchure.midiRange(EmbouchureVoice.valueOf(voice))
                BellowsPatch.ENGINE -> Bellows.midiRange(BellowsVoice.valueOf(voice))
                MurmurPatch.ENGINE -> Murmur.MIN_MIDI..Murmur.MAX_MIDI
                ScannedChainPatch.ENGINE -> ScannedChain.MIDI_MIN..ScannedChain.MIDI_MAX
                ObbligatoPatch.ENGINE -> Obbligato.MIDI_MIN..Obbligato.MIDI_MAX
                OrphicaPatch.ENGINE -> Orphica.MIDI_MIN..Orphica.MIDI_MAX
                OspreyPatch.ENGINE -> Osprey.MIDI_MIN..Osprey.MIDI_MAX
                OpenSkyPatch.ENGINE -> OpenSkyPatch.MIDI_MIN..OpenSkyPatch.MIDI_MAX
                RevelPatch.ENGINE -> Revel.ROOT_MIDI..(Revel.ROOT_MIDI + Revel.TUNE_SEMITONES)
                RelayPatch.ENGINE -> Relay.rootMidi(RelayVoice.valueOf(voice)).let { it..(it + Relay.TUNE_SEMITONES) }
                SkitterPatch.ENGINE -> Skitter.MIDI_MIN..Skitter.MIDI_MAX
                VentPatch.ENGINE -> Vent.MIDI_MIN..Vent.MIDI_MAX
                FlockPatch.ENGINE -> Flock.MIN_MIDI..Flock.MAX_MIDI
                OverlapPatch.ENGINE -> Overlap.MIN_MIDI..Overlap.MAX_MIDI
                WickPatch.ENGINE -> Wick.MIN_MIDI..Wick.MAX_MIDI
                FilamentPatch.ENGINE -> Filament.MIN_MIDI..Filament.MAX_MIDI
                ChamberPatch.ENGINE -> Chamber.MIDI_MIN..Chamber.MIDI_MAX
                TidalPatch.ENGINE -> Tidal.MIN_MIDI..Tidal.MAX_MIDI
                NacrePatch.ENGINE -> Nacre.MIN_MIDI..Nacre.MAX_MIDI
                FerryPatch.ENGINE -> Ferry.MIN_MIDI..Ferry.MAX_MIDI
                OhmPatch.ENGINE -> 48..72
                PrismPatch.ENGINE -> {
                    val prismVoice = PrismVoice.valueOf(voice)
                    val root = Prism.rootMidi(prismVoice)
                    root..(root + Prism.TUNE_SEMITONES)
                }
                ContourPatch.ENGINE -> ContourEngine.rootMidi(ContourVoice.valueOf(voice)).let {
                    (it - ContourEngine.TUNE_SEMITONES)..(it + ContourEngine.TUNE_SEMITONES)
                }
                else -> throw CliError("${noteFlags.joinToString()} are only supported by MERIDIAN, OTIS, APSIS, ORIGAMI, ALLOY, CAUSTIC, OVEN, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, LATTICE, SPOOL, SKITTER, TANGLE, CREASE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, SCANNED_CHAIN, OSPREY, OBBLIGATO, OPENSKY, VENT, FLOCK, WICK, ORPHICA, NACRE, CHAMBER, TIDAL, TRIO, OHM, FERRY and FILAMENT, got $engine")
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
            val supported = if (flag == "--seed") setOf(SkitterPatch.ENGINE, VentPatch.ENGINE, OspreyPatch.ENGINE, FlockPatch.ENGINE, ChamberPatch.ENGINE, TidalPatch.ENGINE, OrphicaPatch.ENGINE, ObbligatoPatch.ENGINE, OhmPatch.ENGINE, ApsisPatch.ENGINE, MeridianPatch.ENGINE, RimePatch.ENGINE, OtisPatch.ENGINE) else setOf(SkitterPatch.ENGINE)
            if (engine !in supported) throw CliError("$flag is only supported by ${supported.joinToString(" and ")}, got $engine")
            raw.toIntOrNull() ?: throw CliError("$flag wants an integer seed, got '$raw'")
        }
        val longSeedOverride = if (engine in setOf(FlockPatch.ENGINE, ChamberPatch.ENGINE, TidalPatch.ENGINE, FerryPatch.ENGINE)) opts["--seed"]?.let { raw ->
            raw.toLongOrNull() ?: throw CliError("--seed for $engine wants a signed 64-bit integer, got '$raw'")
        } else null
        val orphicaSeedOverride = if (engine == OrphicaPatch.ENGINE) opts["--seed"]?.let { raw ->
            raw.toLongOrNull() ?: throw CliError("--seed wants a 64-bit integer seed, got '$raw'")
        } else null
        val seedOverride = if (engine in setOf(FlockPatch.ENGINE, ChamberPatch.ENGINE, TidalPatch.ENGINE, OrphicaPatch.ENGINE, FerryPatch.ENGINE)) null else seedFlag("--seed")
        val secondsOverride = opts["--seconds"]?.let { raw ->
            if (engine !in setOf(OspreyPatch.ENGINE, RimePatch.ENGINE)) throw CliError("--seconds is only supported by OSPREY and RIME, got $engine")
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in (if(engine == RimePatch.ENGINE) .15f else .25f)..(if(engine == RimePatch.ENGINE) 4f else Osprey.MAX_SECONDS) }
                ?: throw CliError(if(engine == RimePatch.ENGINE) "--seconds for RIME wants .15..4 gate seconds, got '$raw'" else "--seconds wants .25..${Osprey.MAX_SECONDS} seconds, got '$raw'")
        }
        val modelOverride = opts["--model"]?.let { raw ->
            val lastModel = when (engine) {
                RimePatch.ENGINE -> Rime.MODEL_VERSION
                FerryPatch.ENGINE -> Ferry.MODEL_VERSION
                OspreyPatch.ENGINE -> Osprey.MODEL_VERSION
                CreasePatch.ENGINE -> Crease.MODEL_VERSION
                TidalPatch.ENGINE -> Tidal.MODEL_VERSION
                CausticPatch.ENGINE -> CausticInstrument.MODEL_VERSION
                OhmPatch.ENGINE -> Ohm.MODEL_VERSION
                MeridianPatch.ENGINE -> Meridian.MODEL_VERSION
                else -> throw CliError("--model is only supported by MERIDIAN, RIME, OSPREY, CREASE, TIDAL, CAUSTIC, OHM and FERRY, got $engine")
            }
            raw.toIntOrNull()?.takeIf { it in 1..lastModel }
                ?: throw CliError("--model wants 1..$lastModel, got '$raw'")
        }
        val scrambleRandom = seedFlag("--scramble")?.let { Random(it) }
        if (!instrument && (opts["--attack"] != null || opts["--release"] != null)) {
            throw CliError("--attack and --release shape a held instrument - add --instrument")
        }
        if (instrument) {
            if (engine == RimePatch.ENGINE && opts["--attack"] != null) throw CliError("RIME retains its UNFOLD opening; use --release")
            if (engine == TidalPatch.ENGINE && opts["--attack"] != null) throw CliError("TIDAL keeps its encounter attack; use --release")
            if (engine == OhmPatch.ENGINE && modelOverride != null && modelOverride < 3) throw CliError("OHM --instrument requires model 3 or later")
            if (engine !in setOf(RimePatch.ENGINE, "RESIN", RegistersPatch.ENGINE, OpenSkyPatch.ENGINE, OrphicaPatch.ENGINE, CreasePatch.ENGINE, ContourPatch.ENGINE, OrigamiPatch.ENGINE, FlockPatch.ENGINE, OverlapPatch.ENGINE, OhmPatch.ENGINE, TidalPatch.ENGINE)) throw CliError("RIME, RESIN, REGISTERS, OPENSKY HALO, ORPHICA, CREASE, CONTOUR, ORIGAMI FAN/STILL, FLOCK RIBBON, OVERLAP HELD, OHM and TIDAL export a keys instrument here")
            if (engine == OverlapPatch.ENGINE) {
                if ((savedPatch as? OverlapPatch)?.playback != OverlapPlayback.HELD)
                    throw CliError("OVERLAP --instrument requires a saved HELD --patch; select held mode in SYNTH first")
                if (opts["--attack"] != null)
                    throw CliError("OVERLAP keeps its 20 ms sampler onset; --instrument accepts --release")
            }
            if (engine == OhmPatch.ENGINE && opts["--attack"] != null) throw CliError("OHM keeps its authored attack; use --release for the held instrument")
            if (engine == CreasePatch.ENGINE && opts["--attack"] != null) throw CliError("CREASE keeps its pressure attack - use --release to shape the held instrument")
            if (engine == ContourPatch.ENGINE && opts["--attack"] != null) throw CliError("CONTOUR --instrument uses settled sustain without the finite attack; omit --attack and use --release")
            if (engine == RegistersPatch.ENGINE && opts["--attack"] != null) throw CliError("REGISTERS keeps its authored attack - use --release to shape the held instrument")
            if (engine == OrphicaPatch.ENGINE && opts["--attack"] != null) throw CliError("ORPHICA keeps the pluck attack; --instrument accepts --release")
            if (engine == FlockPatch.ENGINE) {
                if (!FlockPadMaker.supports(FlockVoice.valueOf(voice))) throw CliError("--instrument supports FLOCK RIBBON; $voice is a finite performance")
                if (opts["--attack"] != null) throw CliError("FLOCK RIBBON keeps its authored arrival; --instrument accepts --release")
            }
            if (engine == OrigamiPatch.ENGINE && opts["--attack"] != null) throw CliError("ORIGAMI keeps its authored attack; --attack is unavailable, use --release with --instrument")
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
        val flockInstrumentRelease = if (instrument && engine == FlockPatch.ENGINE) opts["--release"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in FlockPadMaker.RELEASE_MIN_SECONDS..FlockPadMaker.RELEASE_MAX_SECONDS }
                ?: throw CliError("--release wants finite seconds in ${FlockPadMaker.RELEASE_MIN_SECONDS}..${FlockPadMaker.RELEASE_MAX_SECONDS}, got '$raw'")
        } else null
        val origamiInstrumentRelease = if (instrument && engine == OrigamiPatch.ENGINE) opts["--release"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() && it in OrigamiPadMaker.RELEASE_MIN_SECONDS..OrigamiPadMaker.RELEASE_MAX_SECONDS }
                ?: throw CliError("--release wants finite seconds in ${OrigamiPadMaker.RELEASE_MIN_SECONDS}..${OrigamiPadMaker.RELEASE_MAX_SECONDS}, got '$raw'")
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
                is RimePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is LorentzPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to (midiOverride - Lorentz.MIN_MIDI) / Lorentz.TUNE_SEMITONES.toFloat()),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is PluckPatch -> patch.copy(
                    macros = if (midiOverride == null) patch.macros else patch.macros +
                        ("TUNE" to (midiOverride - 28) / Pluck.TUNE_SEMITONES.toFloat()),
                    velocity = velocityOverride ?: patch.velocity,
                )
                is ApsisPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OrigamiPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is TrioPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is AlloyPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is MeridianPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OvenPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is CisternPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is CausticPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FlotillaPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is PitchwheelPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is RegistersPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is StoopPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is LacquerPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is LatticePatch -> patch.copy(
                    midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity,
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to .5f),
                )
                is SpoolPatch -> patch.copy(
                    macros = midiOverride?.let { midi ->
                        patch.macros + ("TUNE" to (midi - Spool.rootMidi(patch.voice)) / Spool.TUNE_SEMITONES.toFloat())
                    } ?: patch.macros,
                    velocity = velocityOverride ?: patch.velocity,
                )
                is TanglePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is CreasePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is WickPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FilamentPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is ChamberPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is TidalPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is OtisPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity,
                    macros = if (midiOverride == null) patch.macros else patch.macros + ("TUNE" to .5f))
                is NacrePatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is FerryPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
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
                is ScannedChainPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
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
                is OverlapPatch -> patch.copy(midi = midiOverride ?: patch.midi, velocity = velocityOverride ?: patch.velocity)
                is TesseraPatch -> if (midiOverride == null) patch else patch.copy(
                    macros = patch.macros + ("TUNE" to (midiOverride - Tessera.ROOT_MIDI) / Tessera.TUNE_SEMITONES.toFloat()),
                )
                else -> patch
            }
            if (withNote is LorentzPatch) {
                withNote.copy(macros = withNote.macros + macroOverrides)
            } else if (withNote is ApsisPatch) {
                withNote.copy(macros = withNote.macros + macroOverrides,
                    config = withNote.config.copy(seedContext = seedOverride ?: withNote.config.seedContext))
            } else if (withNote is OrigamiPatch) {
                val gesture = gestureOverride ?: withNote.gesture
                if (gesture != OrigamiGesture.STEADY && withNote.model != OrigamiGestures.GEOMETRY_MODEL) {
                    throw CliError("--gesture $gesture requires ORIGAMI model 4; saved model ${withNote.model} keeps its original motion")
                }
                val expression = expressionOverride ?: withNote.expression
                if (expression != OrigamiExpression.LEVEL && withNote.model != OrigamiGestures.GEOMETRY_MODEL) {
                    throw CliError("--expression $expression requires ORIGAMI model 4; saved model ${withNote.model} keeps its original velocity response")
                }
                withNote.copy(
                    macros = withNote.macros + macroOverrides,
                    // Deliberately editing TUNE also edits an authored note, as in the native editor.
                    midi = macroOverrides["TUNE"]?.let { Origami.midiFor(withNote.voice, it) } ?: withNote.midi,
                    gesture = gesture,
                    expression = expression,
                )
            } else if (withNote is OpenSkyPatch) {
                withNote.copy(gateSeconds = gateOverride ?: withNote.gateSeconds, wet = wetOverride ?: withNote.wet)
            } else if (withNote is ShoalPatch && materialOverride != null) {
                if (!Shoal.supportsHold(Shoal.MODEL_VERSION, materialOverride) &&
                    Shoal.isLoop(withNote.macros["HOLD"] ?: 0f)) {
                    throw CliError("SHOAL $materialOverride renders finite notes; --material cannot override a HOLD preset")
                }
                withNote.copy(model = Shoal.MODEL_VERSION, material = materialOverride)
            } else if (withNote is RimePatch) {
                try {
                    withNote.copy(model = modelOverride ?: withNote.model, macros = withNote.macros + macroOverrides,
                        gateSeconds = secondsOverride ?: withNote.gateSeconds, seed = seedOverride ?: withNote.seed)
                } catch (e: IllegalArgumentException) { throw CliError("RIME: ${e.message}; use --model 5 for HOLD") }
            } else if (withNote is PluckPatch && withNote.voice == PluckVoice.UPRIGHT && macroOverrides.isNotEmpty()) {
                withNote.copy(macros = withNote.macros + macroOverrides)
            } else if (withNote is MeridianPatch) {
                withNote.copy(macros = withNote.macros + macroOverrides,
                    seedContext = seedOverride ?: withNote.seedContext, model = modelOverride ?: withNote.model)
            } else if (withNote is ContourPatch && macroOverrides.isNotEmpty()) {
                withNote.copy(macros = withNote.macros + macroOverrides)
            } else if (withNote is CausticPatch) {
                withNote.copy(macros = withNote.macros + macroOverrides, model = modelOverride ?: withNote.model)
            } else if (withNote is LatticePatch && macroOverrides.isNotEmpty()) {
                withNote.copy(macros = withNote.macros + macroOverrides)
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
                    throw CliError("TRIO's $beats beats at $tempo BPM exceed the two-second beat-grid window; raise --tempo or lower --beats")
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
            ) else if (withNote is TidalPatch) withNote.copy(
                macros = withNote.macros + macroOverrides,
                seed = longSeedOverride ?: withNote.seed,
                model = modelOverride ?: withNote.model,
            ) else if (withNote is OtisPatch) withNote.copy(seed = seedOverride ?: withNote.seed,
                macros = withNote.macros + macroOverrides)
            else if (withNote is VentPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else if (withNote is FlockPatch && longSeedOverride != null) withNote.copy(seed = longSeedOverride)
            else if (withNote is FerryPatch) {
                val model = modelOverride ?: withNote.model
                var controls = withNote.macros + macroOverrides
                if (model == 1 && "MATCH" in controls) {
                    if (controls.getValue("MATCH") != 1f || "MATCH" in macroOverrides)
                        throw CliError("FERRY MATCH requires --model 2; a detuned recipe cannot be downgraded")
                    controls = controls - "MATCH"
                }
                withNote.copy(seed = longSeedOverride ?: withNote.seed, macros = controls, model = model)
            }
            else if (withNote is ChamberPatch && longSeedOverride != null) withNote.copy(seed = longSeedOverride)
            else if (withNote is ObbligatoPatch && seedOverride != null) withNote.copy(seed = seedOverride)
            else if (withNote is OhmPatch) {
                val model = modelOverride ?: if (withNote.model < 3 && (instrument || "HOLD" in macroOverrides))
                    Ohm.MODEL_VERSION else withNote.model
                if (withNote.model >= 3 && model < withNote.model)
                    throw CliError("OHM expanded recipes cannot be downgraded; choose a recipe saved with model $model")
                withNote.copy(model = model, seed = seedOverride ?: withNote.seed,
                    macros = Ohm.defaults(model) + withNote.macros + macroOverrides)
            }
            else withNote
        }

        val skyInstrumentSpec = if (instrument && engine == OpenSkyPatch.ENGINE) try {
            OpenSkyPadMaker.Spec(chosen.single() as OpenSkyPatch, releaseSeconds = skyInstrumentRelease ?: OpenSkyPadMaker.RELEASE.default)
        } catch (e: IllegalArgumentException) {
            throw CliError("--instrument / --midi: ${e.message ?: "invalid OPENSKY instrument"}")
        } else null
        val flockInstrumentSpec = if (instrument && engine == FlockPatch.ENGINE) try {
            val patch = chosen.single() as FlockPatch
            FlockPadMaker.Spec(patch.copy(playback = FlockPlayback.HELD,
                releaseSeconds = flockInstrumentRelease ?: patch.releaseSeconds), touchLayers = opts.has("--touch"))
        } catch (e: IllegalArgumentException) {
            throw CliError("--instrument / --midi: ${e.message ?: "invalid FLOCK instrument"}")
        } else null
        val overlapInstrumentSpec = if (instrument && engine == OverlapPatch.ENGINE) try {
            val patch = chosen.single() as OverlapPatch
            val release = opts["--release"]?.let { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() && it in OverlapHeld.MIN_RELEASE_SECONDS..OverlapHeld.MAX_RELEASE_SECONDS }
                    ?: throw CliError("OVERLAP --release wants finite seconds in ${OverlapHeld.MIN_RELEASE_SECONDS}..${OverlapHeld.MAX_RELEASE_SECONDS}, got '$raw'")
            } ?: patch.releaseSeconds
            OverlapPadMaker.Spec(patch.copy(releaseSeconds = release))
        } catch (e: IllegalArgumentException) {
            throw CliError("OVERLAP --instrument: ${e.message ?: "invalid held recipe"}")
        } else null
        val creaseInstrumentSpec = if (instrument && engine == CreasePatch.ENGINE) try {
            val release = opts["--release"]?.let { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: throw CliError("--release wants finite seconds, got '$raw'")
            } ?: CreasePadMaker.RELEASE.default
            CreasePadMaker.Spec(chosen.single() as CreasePatch, releaseSeconds = release, touchLayers = opts.has("--touch"))
        } catch (e: IllegalArgumentException) {
            throw CliError("--instrument: ${e.message ?: "invalid CREASE instrument"}")
        } else null
        val contourInstrumentSpec = if (instrument && engine == ContourPatch.ENGINE) try {
            val release = opts["--release"]?.let { raw ->
                raw.toFloatOrNull() ?: throw CliError("--release wants seconds, got '$raw'")
            } ?: ContourPadMaker.RELEASE.default
            ContourPadMaker.Spec(chosen.single() as ContourPatch, releaseSeconds = release)
        } catch (e: IllegalArgumentException) {
            throw CliError("--release: ${e.message ?: "invalid CONTOUR instrument"}")
        } else null
        val tidalInstrumentSpec = if (instrument && engine == TidalPatch.ENGINE) try {
            val release = opts["--release"]?.let { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw CliError("--release wants finite seconds")
            } ?: TidalPadMaker.RELEASE.default
            TidalPadMaker.Spec(chosen.single() as TidalPatch, release)
        } catch (e: IllegalArgumentException) {
            throw CliError("TIDAL --instrument: ${e.message}")
        } else null
        val origamiInstrumentSpec = if (instrument && engine == OrigamiPatch.ENGINE) try {
            OrigamiPadMaker.Spec(chosen.single() as OrigamiPatch,
                releaseSeconds = origamiInstrumentRelease ?: OrigamiPadMaker.RELEASE.default)
        } catch (e: IllegalArgumentException) {
            throw CliError("--instrument: ${e.message ?: "ORIGAMI supports model-4 FAN and STILL only"}")
        } else null
        val ohmInstrumentSpec = if (instrument && engine == OhmPatch.ENGINE) try {
            val patch = chosen.single() as OhmPatch
            val release = opts["--release"]?.let { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() && it in .02f..3f }
                    ?: throw CliError("OHM --release wants finite seconds in 0.02..3")
            } ?: patch.releaseSeconds
            OhmPadMaker.Spec(patch.copy(macros = Ohm.defaults(patch.model) + patch.macros + ("HOLD" to 1f),
                releaseSeconds = release))
        } catch (e: IllegalArgumentException) {
            throw CliError("OHM --instrument: ${e.message ?: "invalid held recipe"}")
        } else null
        val rimeInstrumentSpec = if (instrument && engine == RimePatch.ENGINE) try {
            val release = opts["--release"]?.toFloatOrNull() ?: if (opts["--release"] == null) RimePadMaker.RELEASE.default
                else throw IllegalArgumentException("release must be finite seconds")
            RimePadMaker.Spec(chosen.single() as RimePatch, release)
        } catch (e: IllegalArgumentException) { throw CliError("RIME --instrument: ${e.message}; use --model 5 for HOLD") } else null
        // Resolve every recipe and instrument specification before creating output files.
        val dir = File(dirArg)
        if (!dir.isDirectory && !dir.mkdirs()) throw CliError("could not make the output folder: $dirArg")
        if (rimeInstrumentSpec != null) return makeRimeInstrument(rimeInstrumentSpec, dir, out)
        if (skyInstrumentSpec != null) return makeOpenSkyInstrument(skyInstrumentSpec, dir, out)
        if (flockInstrumentSpec != null) return makeFlockInstrument(flockInstrumentSpec, dir, out)
        if (overlapInstrumentSpec != null) return makeOverlapInstrument(overlapInstrumentSpec, dir, out)
        if (creaseInstrumentSpec != null) return makeCreaseInstrument(creaseInstrumentSpec, dir, out)
        if (contourInstrumentSpec != null) return makeContourInstrument(contourInstrumentSpec, dir, out)
        if (tidalInstrumentSpec != null) return makeTidalInstrument(tidalInstrumentSpec, dir, out)
        if (origamiInstrumentSpec != null) return makeOrigamiInstrument(origamiInstrumentSpec, dir, out)
        if (ohmInstrumentSpec != null) return makeOhmInstrument(ohmInstrumentSpec, dir, out)
        if (instrument) return makeInstrument(voice, chosen.single(), opts, dir, out)
        if (drone) return makeDrone(voice, chosen.single(), opts, dir, out)

        for ((i, patch) in chosen.withIndex()) {
            // What SEND TO PAD would make: the string machines take their ENSEMBLE, so the file you audition is the pad you would get.
            val landing = Presets.landingFor(engine, voice, patch.macros)
            val heldRibbon = if (patch is FlockPatch && patch.playback == FlockPlayback.HELD) FlockHeld.render(patch) else null
            val heldOverlap = if (patch is OverlapPatch && patch.playback == OverlapPlayback.HELD) OverlapHeld.render(patch) else null
            val heldOhm = if (patch is OhmPatch && Ohm.isHeld(patch)) Ohm.renderHeld(patch) else null
            val meridianHeld = if (patch is MeridianPatch && Meridian.isLoop(patch.macros, patch.model)) {
                Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
            } else null
            val snip = if (heldOverlap != null) heldOverlap.snip else if (meridianHeld != null) meridianHeld.snip else if (heldOhm != null) heldOhm.snip else if (heldRibbon != null) heldRibbon.snip else if (patch is TesseraPatch && velocityOverride != null) {
                val dry = Velocity.atVelocity(patch, velocityOverride)
                landing?.process(dry) ?: dry
            } else if (landing == null) patch.render() else PadRecipe(patch, landing).render()
            val safe = patch.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "PRESET" }
            val family = if (patch is ShoalPatch) "${voice}_${patch.material.name}" else voice
            val file = File(dir, "%s_%s_%02d_%s.wav".format(Locale.ROOT, engine, family, i + 1, safe))
            val heldRime = patch is RimePatch && Rime.isLoop(patch.macros)
            val heldTidal = patch is TidalPatch && Tidal.isHeld(patch.macros["HOLD"] ?: 0f)
            val sampler = if (meridianHeld != null && patch is MeridianPatch) SmplChunk(
                Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi),
                SmplChunk.Loop(meridianHeld.loopStartFrame.toLong(), meridianHeld.loopEndFrameExclusive.toLong()),
            ) else if (heldRime) SmplChunk((patch as RimePatch).midi,
                SmplChunk.Loop(0, snip.frameCount.toLong())) else if (heldTidal) SmplChunk((patch as TidalPatch).midi,
                SmplChunk.Loop(0, snip.frameCount.toLong())) else heldOhm?.let { SmplChunk((patch as OhmPatch).midi,
                SmplChunk.Loop(it.loopStartFrame, it.snip.frameCount.toLong())) }
                ?: heldRibbon?.let { SmplChunk((patch as FlockPatch).midi,
                    SmplChunk.Loop(it.loopStartFrame.toLong(), it.loopEndExclusive.toLong())) }
                ?: heldOverlap?.let { SmplChunk((patch as OverlapPatch).midi,
                    SmplChunk.Loop(it.loopStartFrame.toLong(), it.loopEndExclusive.toLong())) }
            FileOutputStream(file).buffered().use { WavWriter.write(it, snip, smpl = sampler) }
            if (patch is LorentzPatch || patch is MeridianPatch || patch is RimePatch || patch is OtisPatch || patch is FerryPatch || patch is ApsisPatch || patch is ObbligatoPatch || patch is TidalPatch || patch is LatticePatch ||
                patch is PluckPatch && patch.voice == PluckVoice.UPRIGHT) {
                File(dir, "${file.nameWithoutExtension}.patch").writeText(patch.toJsonText())
            }
            if (patch is FlockPatch) File(dir, "${file.nameWithoutExtension}.patch.json").writeText(patch.toJsonText())
            if (patch is OverlapPatch) File(dir, "${file.nameWithoutExtension}.patch.json").writeText(patch.toJsonText())
            if (patch is OhmPatch) File(dir, "${file.nameWithoutExtension}.patch.json").writeText(patch.toJsonText())
            val mode = when {
                patch is OpenSkyPatch -> if (patch.wet) "  (room + shimmer, stereo)" else "  (dry, stereo)"
                heldRime -> "  (resolved contour cycle, loop-enabled WAV)"
                meridianHeld != null -> "  (fixed-alignment swing, loop-enabled WAV)"
                heldTidal -> "  (reconstructed aftermath, loop-enabled WAV)"
                heldRibbon != null -> "  (captured RIBBON sustain, loop-enabled WAV)"
                heldOverlap != null -> "  (steady OVERLAP sustain, loop-enabled WAV)"
                heldOhm != null -> "  (OHM attack plus captured sustain, loop-enabled WAV)"
                patch is LatticePatch && Lattice.drumClassFor(patch.voice, patch.macros) == DrumClass.LOOP -> "  (held loop, mono)"
                landing != null -> "  (lands with ENSEMBLE, stereo)"
                else -> ""
            }
            out.println("${file.name}  ${"%.2f".format(Locale.ROOT, snip.durationSeconds)}s$mode")
        }
        out.println("${chosen.size} rendered into ${dir.path}")
        return 0
    }

    private fun makeOverlapInstrument(spec: OverlapPadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = OverlapPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { index, midi ->
            out.println("zone ${index + 1}/${midis.size} ${Scales.nameOf(midi)}")
            OverlapPadMaker.renderZone(spec, midi)
        }
        OverlapPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} native reference zones, steady OVERLAP sustain; sampler release ${spec.releaseSeconds} (MPC timing unmeasured)")
        out.println("recipe: ${File(dir, "$name.patch.json").path}")
        return 0
    }

    private fun makeOhmInstrument(spec: OhmPadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = OhmPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            OhmPadMaker.renderZone(spec, midi)
        }
        OhmPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} OHM zones, authored attack and sustain, RELEASE ${OhmPadMaker.secondsLabel(spec.releaseSeconds)}")
        return 0
    }

    private fun makeTidalInstrument(spec: TidalPadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = TidalPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            TidalPadMaker.renderZone(spec, midi)
        }
        TidalPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ MPC 2 twin)")
        out.println("${midis.size} TIDAL zones, encounter then held aftermath, RELEASE ${TidalPadMaker.secondsLabel(spec.releaseSeconds)}")
        return 0
    }

    private fun makeOrigamiInstrument(spec: OrigamiPadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = OrigamiPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            val progress = if (spec.layerCount > 1)
                " (layers ${i * spec.layerCount + 1}..${(i + 1) * spec.layerCount}/${midis.size * spec.layerCount})" else ""
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}$progress")
            OrigamiPadMaker.renderVelocityZone(spec, midi)
        }
        OrigamiPadMaker.exportLayers(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, each holds - authored attack retained, RELEASE ${OrigamiPadMaker.secondsLabel(spec.releaseSeconds)}")
        if (spec.layerCount > 1) out.println("expression layers: ${spec.layerCount} per zone, ${midis.size * spec.layerCount} samples; soft/mid/hard folded color up to the saved velocity")
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

    private fun makeFlockInstrument(spec: FlockPadMaker.Spec, dir: File, out: PrintStream): Int {
        val patch = spec.patch
        val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
        val midis = FlockPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            val progress = if (spec.touchLayers)
                " (layers ${i * spec.layerCount + 1}..${(i + 1) * spec.layerCount}/${midis.size * spec.layerCount})" else ""
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}$progress")
            FlockPadMaker.renderLayers(spec, midi)
        }
        FlockPadMaker.exportLayers(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} native zones, captured RIBBON sustain - RELEASE ${FlockPadMaker.secondsLabel(spec.releaseSeconds)}")
        if (spec.touchLayers) out.println("touch layers: ${spec.layerCount} per zone, ${midis.size * spec.layerCount} samples; soft/mid/hard excitation up to the saved velocity")
        out.println("recipe: ${File(dir, "$name.patch.json").path}")
        if (spec.touchLayers) out.println("instrument recipe: ${FlockPadMaker.recipeFile(dir, name).path}")
        return 0
    }

    private fun makeCreaseInstrument(spec: CreasePadMaker.Spec, dir: File, out: PrintStream): Int {
        val patch = spec.patch
        val name = OneNote.freshName(dir, Names.sanitizeStem(patch.name))
        val midis = CreasePadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { index, midi ->
            val progress = if (spec.touchLayers)
                " (layers ${index * spec.layerCount + 1}..${(index + 1) * spec.layerCount}/${midis.size * spec.layerCount})" else ""
            out.println("zone ${index + 1}/${midis.size} ${Scales.nameOf(midi)}$progress")
            CreasePadMaker.renderLayers(spec, midi)
        }
        CreasePadMaker.exportLayers(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, pressure attack and moving sustain - RELEASE ${CreasePadMaker.secondsLabel(spec.releaseSeconds)}")
        out.println("CREASE model ${patch.model}, saved root ${Scales.nameOf(patch.midi)}, saved velocity ${patch.velocity}")
        if (spec.touchLayers) out.println("touch layers: ${spec.layerCount} per zone, ${midis.size * spec.layerCount} samples; soft/mid/hard excitation up to the saved velocity")
        return 0
    }

    private fun makeRimeInstrument(spec: RimePadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = RimePadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            RimePadMaker.renderZone(spec, midi)
        }
        RimePadMaker.export(name, spec, notes, dir)
        File(dir, "$name.patch").writeText(spec.heldPatch.toJsonText())
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, contour opening and periodic sustain - RELEASE ${RimePadMaker.secondsLabel(spec.releaseSeconds)}")
        return 0
    }


    private fun makeContourInstrument(spec: ContourPadMaker.Spec, dir: File, out: PrintStream): Int {
        val name = OneNote.freshName(dir, Names.sanitizeStem(spec.patch.name))
        val midis = ContourPadMaker.zoneMidis(spec)
        val notes = midis.mapIndexed { i, midi ->
            out.println("zone ${i + 1}/${midis.size} ${Scales.nameOf(midi)}")
            ContourPadMaker.renderZone(spec, midi)
        }
        ContourPadMaker.export(name, spec, notes, dir)
        out.println("instrument: ${File(dir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("${midis.size} zones, settled sustain (finite attack omitted) - RELEASE ${ContourPadMaker.secondsLabel(spec.releaseSeconds)}")
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
