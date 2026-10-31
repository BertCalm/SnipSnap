package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/**
 * The S3.5 factory kit: a *melodic* kit, where the 4×4 grid is a scale, not
 * a drum layout. This is the keys-on-pads promise from docs/SYNTH_ROADMAP.md
 * arriving early as one-shots — no keygroup export needed, because every pad
 * is just a WAV like any other. Every pad carries its [PadRecipe].
 */
object SynthKits {
    /** Sixteen finite Libration notes; native touch zones retain their energy. */
    fun libration(): List<ArrangedPad> = LibrationKits.arranged()

    /** Finite model-two colors with three native velocity zones per pad. */
    fun trefoil(): List<ArrangedPad?> = TrefoilKits.build()

    /**
     * Eight WARM notes in C minor pentatonic from C3, followed by all three source profiles
     * as finite and held recipes. Every pad retains its exact MIDI root and source velocity.
     * The engine's fixed gain and dry routing preserve the directional passage differences.
     */
    fun lattice(): List<ArrangedPad?> {
        fun arranged(patch: LatticePatch, factoryName: String = patch.name) = pad(patch, Lattice.drumClassFor(patch.voice, patch.macros))
            .copy(displayName = patch.name, source = mapOf(
                "rootMidi" to patch.midi.toString(), "rootNote" to Scales.nameOf(patch.midi),
                "factoryPreset" to factoryName,
            ))
        val voices = LatticeVoice.entries
        val finite = voices.associateWith { voice ->
            LatticePresets.forVoice(voice).filterNot { Lattice.isLoop((Lattice.defaults(voice) + it.macros).getValue("HOLD")) }
        }
        val anchor = finite.getValue(LatticeVoice.WARM).first()
        val melody = PENTATONIC.take(8).mapIndexed { index, semitone ->
            arranged(anchor.copy(name = "${anchor.name} ${index + 1}", midi = Lattice.DEFAULT_MIDI + semitone,
                macros = anchor.macros + mapOf("TUNE" to .5f, "HOLD" to 0f)), anchor.name)
        }
        val sourceChoices = voices.map { finite.getValue(it).first() }
        val held = voices.map { voice ->
            LatticePresets.forVoice(voice).first { Lattice.isLoop((Lattice.defaults(voice) + it.macros).getValue("HOLD")) }
        }
        val alternatives = listOf(finite.getValue(LatticeVoice.WARM)[1], finite.getValue(LatticeVoice.PLUCK)[1])
        return melody + (sourceChoices + alternatives + held).map { arranged(it) }
    }

    /** Sixteen dry, finite encounters; long samples play once despite LOOP classification. */
    fun tidal(): List<ArrangedPad?> {
        fun arranged(patch: TidalPatch) = pad(patch, Tidal.drumClassFor(patch.voice, patch.macros)).copy(
            oneShot = true,
            displayName = patch.name,
            source = mapOf("engine" to patch.engine, "voice" to patch.voiceName,
                "preset" to patch.name, "authoredMidi" to patch.midi.toString()),
        )
        val notes = intArrayOf(48, 51, 53, 55, 58)
        return TidalVoice.entries.flatMap { voice ->
            notes.map { midi ->
                arranged(TidalPatch("${voice.name} ${Scales.nameOf(midi)}", voice, Tidal.defaults(voice), midi = midi))
            } + TidalPresets.forVoice(voice).map(::arranged)
        }
    }

    /** Sixteen finite C-minor-pentatonic notes with complete scanned-chain recipes. */
    fun scannedChain(): List<ArrangedPad?> {
        val voices = List(8) { ScannedChainVoice.PLUCK } + List(4) { ScannedChainVoice.METAL } + List(4) { ScannedChainVoice.BLOOM }
        return voices.mapIndexed { index, voice ->
            val scaleIndex = when { index < 8 -> index; index < 12 -> index - 8; else -> index - 12 }
            // Keep the starter's original default sounds as the editable preset bank grows.
            val patch = ScannedChainPatch("Chain ${index + 1}", voice, midi = 48 + PENTATONIC[scaleIndex])
            pad(patch, DrumClass.TONAL)
        }
    }

    /** The finite TRIO conversations, in the same order as its factory preset bank. */
    fun trio(): List<ArrangedPad?> = TrioKits.classic()

    /**
     * Sixteen dry OHM notes from the five accepted model-2 encounters. A01–A08
     * play Solo up C minor pentatonic from C3; the other pads compare the four
     * relationships at C4, three low answers at C3 and a Gather at C5.
     * Native C4 recipes remain exactly the factory patches. Only a transposed
     * copy's name and explicit MIDI change; touch, body, length, relationship,
     * velocity, seed, gesture and model stay with the accepted recipe.
     * Finite pitched gestures are TONAL one-shots, as in OVEN and COROLLA:
     * a long tail is not a held loop and must remain available to IN KEY.
     */
    fun ohm(): List<ArrangedPad?> {
        val encounters = OhmPresets.all().associateBy { it.name }
        fun note(name: String, midi: Int): ArrangedPad {
            val original = encounters.getValue(name)
            val displayName = "$name ${Scales.nameOf(midi)}"
            val patch = if (midi == original.midi) original else original.copy(name = displayName, midi = midi)
            return pad(patch, DrumClass.TONAL).copy(
                displayName = displayName,
                oneShot = true,
                source = mapOf(
                    "engine" to OhmPatch.ENGINE,
                    "preset" to original.name,
                    "pitchMidi" to midi.toString(),
                    "model" to patch.model.toString(),
                    "seed" to patch.seed.toString(),
                    "gesture" to patch.gesture.name,
                ),
            )
        }
        return PENTATONIC.take(8).map { note("Solo", 48 + it) } + listOf(
            note("Argument", 60), note("Communion", 60), note("Quiet Company", 60), note("Gather", 60),
            note("Argument", 48), note("Communion", 48), note("Quiet Company", 48), note("Gather", 72),
        )
    }

    /**
     * Twelve dry model-2 VENT pads in C minor pentatonic: bass, short note,
     * percussive hit and long texture for PULSE, COIL and PLUME. Four slots
     * remain free for captures. Every finite one-shot retains its full recipe.
     */
    fun vent(): List<ArrangedPad?> = VentKitPresets.entries.map { entry ->
        val level = when (entry.role) {
            "Bass" -> .72f
            "Short note" -> .54f
            "Percussive hit" -> .60f
            "Long texture" -> .22f
            else -> error("Unfiled VENT role ${entry.role}")
        }
        pad(entry.patch, Vent.drumClassFor(entry.patch.voice, entry.patch.macros))
            .copy(level = level, displayName = entry.patch.name)
    }

    /**
     * PLACEHOLDER, not a tuned value — Phase 1's MOTION stage is the real
     * answer to "melodic pads hold dead still"; until it lands, [Tape]'s own
     * wow+flutter (already implemented, `Tape.kt`'s WOW_HZ/FLUTTER_HZ) is
     * the cheapest motion sitting unused in the tree. This is a stopgap,
     * not a decision, and wants a human audition pass before it ships as-is.
     *
     * Measured (not guessed) what this amount actually does: a 440Hz tone
     * through `Tape.process(mapOf("WOBBLE" to 0.05f))`, tracked with a
     * short-window FFT plus parabolic peak interpolation (never
     * autocorrelation — [TuningAccuracyTest] already caught autocorrelation
     * locking onto a harmonic and reporting 1047Hz for a real 523.93Hz
     * fundamental), swings from -8.1 to +3.3 cents peak across the ~0.77s
     * wow cycle. That is below "obviously detuned" (a semitone is 100
     * cents) but well above the ~5-cent just-noticeable threshold, which is
     * the point: felt as motion, not heard as pitch error. For scale, a
     * real consumer cassette deck's wow+flutter spec (~0.1-0.3% RMS) works
     * out to roughly 2-5 cents, so this sits at the rougher end of "cheap
     * deck," not "broken" — a reasonable stopgap register, still
     * provisional.
     */
    private const val MELODIC_WOBBLE_AMOUNT = 0.05f

    private val melodicMotion = FxChain().withSection("tape", mapOf("WOBBLE" to MELODIC_WOBBLE_AMOUNT))

    private fun pad(patch: Patch, drumClass: DrumClass, fx: FxChain? = null): ArrangedPad {
        val recipe = PadRecipe(patch, fx)
        return ArrangedPad(recipe.render(), drumClass, recipe.toJsonValue())
    }

    /** The authoritative dry orbital performance bank, with native velocity zones. */
    fun apsis(): List<ArrangedPad?> = ApsisKitBank.arrangedPads()

    private fun pluck(name: String, voice: PluckVoice, semitone: Int, vararg extra: Pair<String, Float>) =
        pad(
            PluckPatch(name, voice, mapOf("TUNE" to semitone / Pluck.TUNE_SEMITONES.toFloat()) + extra),
            DrumClass.TONAL,
            melodicMotion,
        )

    private fun stab(name: String, voice: TonewheelVoice, semitone: Int) =
        pad(
            TonewheelPatch(name, voice, mapOf("TUNE" to semitone / Tonewheel.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
            melodicMotion,
        )

    /** A TINES KALIMBA note: TUNE snaps from A3 over the same 24 semitones PLUCK's voices use. */
    private fun tines(name: String, semitone: Int) =
        pad(
            TinesPatch(name, TinesVoice.KALIMBA, mapOf("TUNE" to semitone / Tines.KALIMBA_TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
            melodicMotion,
        )

    /** A minor pentatonic: 0 3 5 7 10, repeating up the octaves. */
    private val PENTATONIC = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24)

    fun melodic(): List<ArrangedPad?> = listOf(
        pluck("Nylon 1", PluckVoice.NYLON, PENTATONIC[0]),        // A01 - the root
        pluck("Nylon 2", PluckVoice.NYLON, PENTATONIC[1]),        // A02
        pluck("Nylon 3", PluckVoice.NYLON, PENTATONIC[2]),        // A03
        pluck("Nylon 4", PluckVoice.NYLON, PENTATONIC[3]),        // A04
        pluck("Nylon 5", PluckVoice.NYLON, PENTATONIC[4]),        // A05
        pluck("Nylon 6", PluckVoice.NYLON, PENTATONIC[5]),        // A06
        // The kalimba's root sits an octave above the nylon's, so subtracting
        // an octave keeps one unbroken pitch line while the timbre climbs.
        // It is a TINES voice now: a kalimba tine is a bar, not a string.
        tines("Kalimba 1", PENTATONIC[6] - 12),  // A07 - kalimba takes over
        tines("Kalimba 2", PENTATONIC[7] - 12),  // A08
        tines("Kalimba 3", PENTATONIC[8] - 12),  // A09
        tines("Kalimba 4", PENTATONIC[9] - 12),  // A10
        tines("Kalimba 5", PENTATONIC[10] - 12), // A11
        pluck("Harp Crown", PluckVoice.HARP, 24, "DOUBLE" to 0.4f),  // A12 - harp crown (E5, the in-scale fifth)
        stab("Soul Root", TonewheelVoice.SOUL, 0),                // A13 - stabs: root
        stab("Stab Fourth", TonewheelVoice.STAB, 5),              // A14 - fourth
        stab("Stab Fifth", TonewheelVoice.STAB, 7),               // A15 - fifth
        stab("Full Octave", TonewheelVoice.FULL, 12),             // A16 - octave, everything out
    )

    /**
     * The chip kit — the roadmap's free bonus, cashed: VELVET squares and
     * THUMP/TINES drums, every pad rendered through CRUNCH via the FxChain.
     * Maximum kitsch, zero new DSP. Drums on the bottom row and a half,
     * chip-square pentatonic from A07 up. Because each pad's recipe stores
     * the patch *and* the converter chain, the whole aesthetic is one edit
     * away from being someone else's.
     */
    fun chip(): List<ArrangedPad?> {
        // One converter for the whole kit: ~9 bits, lowered hold rate, a
        // little grit. Consistent grunge is what makes it read as hardware.
        val console = FxChain(
            crunch = mapOf("BITS" to 0.75f, "RATE" to 0.6f, "TONE" to 0.65f, "GRIT" to 0.2f),
        )

        fun chipNote(n: Int, semitone: Int) = pad(
            VelvetPatch("Chip Note $n", VelvetVoice.CHIP, mapOf("TUNE" to semitone / Velvet.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
            console,
        )

        return listOf(
            pad(ThumpPatch("Chip Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.5f, "DECAY" to 0.35f)), DrumClass.KICK, console), // A01
            pad(ThumpPatch("Chip Snare", ThumpVoice.SNARE, mapOf("SNAP" to 0.8f, "DECAY" to 0.3f)), DrumClass.SNARE, console), // A02
            pad(ThumpPatch("Chip Hat", ThumpVoice.HAT_CLOSED, emptyMap()), DrumClass.HAT_CLOSED, console),  // A03
            pad(ThumpPatch("Chip Open Hat", ThumpVoice.HAT_OPEN, mapOf("DECAY" to 0.4f)), DrumClass.HAT_OPEN, console), // A04
            pad(TinesPatch("Game Over", TinesVoice.TOY, emptyMap()), DrumClass.PERC, console),              // A05
            pad(TinesPatch("Laser", TinesVoice.ZAP, emptyMap()), DrumClass.PERC, console),                  // A06
            chipNote(1, PENTATONIC[0]), chipNote(2, PENTATONIC[1]),                                         // A07 A08
            chipNote(3, PENTATONIC[2]), chipNote(4, PENTATONIC[3]),                                         // A09 A10
            chipNote(5, PENTATONIC[4]), chipNote(6, PENTATONIC[5]),                                         // A11 A12
            chipNote(7, PENTATONIC[6]), chipNote(8, PENTATONIC[7]),                                         // A13 A14
            chipNote(9, PENTATONIC[8]), chipNote(10, PENTATONIC[9]),                                        // A15 A16
        )
    }

    /**
     * The atmosphere kit: VOX choirs on the bottom rows, GRAINS clouds
     * above — the first kit whose sounds come from granulating *other
     * sounds*, which in the app means granulating your captures. GRAINS
     * pads carry no recipe yet (a cloud's recipe needs a source-file
     * reference, which is the app layer's kit-folder job); VOX pads are
     * fully editable like every other synth pad.
     */
    fun cloud(): List<ArrangedPad?> {
        fun vox(name: String, voice: VoxVoice, semitone: Int) = pad(
            VoxPatch(name, voice, mapOf("TUNE" to semitone / Vox.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
        )

        fun cloudOf(source: com.snipsnap.audio.Snip, cls: DrumClass, seconds: Float, seed: Int, vararg macros: Pair<String, Float>) =
            ArrangedPad(Grains.render(source, macros.toMap(), seconds = seconds, seed = seed), cls)

        val bell = Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.9f))
        val chime = Tines.render(TinesVoice.CHIME, mapOf("DECAY" to 0.8f))
        val brass = Velvet.render(VelvetVoice.BRASS, mapOf("DECAY" to 0.9f))
        val koto = Pluck.render(PluckVoice.KOTO, mapOf("DAMP" to 0.2f))
        val choirSrc = Vox.render(VoxVoice.CHOIR, mapOf("DECAY" to 1f))

        return listOf(
            vox("Choir Root", VoxVoice.CHOIR, 0),                                    // A01
            vox("Choir Fourth", VoxVoice.CHOIR, 5),                                  // A02
            vox("Choir Fifth", VoxVoice.CHOIR, 7),                                   // A03
            vox("Robot Root", VoxVoice.ROBOT, 0),                                    // A04
            vox("Ghost Low", VoxVoice.GHOST, 0),                                     // A05
            vox("Ghost High", VoxVoice.GHOST, 12),                                   // A06
            cloudOf(bell, DrumClass.PERC, 1.2f, 21, "SIZE" to 0.3f, "DRIFT" to 0.4f),      // A07 bell scatter
            cloudOf(chime, DrumClass.PERC, 1.2f, 22, "SIZE" to 0.2f, "SHINE" to 0.5f),     // A08 glass stutter
            cloudOf(koto, DrumClass.PERC, 1.2f, 23, "SIZE" to 0.35f, "DRIFT" to 0.6f),     // A09 koto scatter
            cloudOf(brass, DrumClass.TONAL, 1.4f, 24, "SIZE" to 0.8f, "SMEAR" to 0.8f),    // A10 brass smear
            cloudOf(choirSrc, DrumClass.TONAL, 1.4f, 25, "SIZE" to 0.9f, "PITCH" to 0.25f), // A11 choir under
            cloudOf(bell, DrumClass.TONAL, 1.4f, 26, "SIZE" to 0.7f, "PITCH" to 0.75f, "SHINE" to 0.6f), // A12 bell above
            cloudOf(choirSrc, DrumClass.LOOP, 2.5f, 27, "SIZE" to 1f, "SMEAR" to 1f, "PITCH" to 0.25f),  // A13 choir drone
            cloudOf(brass, DrumClass.LOOP, 2.5f, 28, "SIZE" to 1f, "SMEAR" to 0.9f, "DRIFT" to 0.3f),    // A14 brass pad
            cloudOf(bell, DrumClass.LOOP, 2.5f, 29, "SIZE" to 0.9f, "SHINE" to 0.8f, "DRIFT" to 0.5f),   // A15 shimmer wash
            cloudOf(chime, DrumClass.LOOP, 2.5f, 30, "SIZE" to 0.6f, "PITCH" to 0.3f, "DRIFT" to 0.7f),  // A16 deep glass
        )
    }

    /**
     * The TIDE kit (docs/SYNTH_ROADMAP.md, S9): WOOD BONGO walks C minor
     * pentatonic up the first two rows — the West Coast plucked pattern,
     * the sound the engine exists for — then DRIP, GONG and FLARE presets
     * above it. Dry: the gate's own decay is the space. BONGO and DRIP pads
     * are PERC ([TidePresetsTest] has the measurement), GONG and FLARE TONAL
     * (notes DECAY can hold); every pad carries its recipe.
     */
    fun tide(): List<ArrangedPad?> {
        val wood = TidePresets.forVoice(TideVoice.BONGO).first { it.name == "WOOD BONGO" }
        fun bongo(n: Int, semitone: Int) = pad(
            TidePatch("Bongo $n", TideVoice.BONGO, wood.macros + ("TUNE" to semitone / Tide.TUNE_SEMITONES.toFloat())),
            DrumClass.PERC,
        )
        fun preset(voice: TideVoice, name: String) = pad(
            TidePresets.forVoice(voice).first { it.name == name },
            if (voice == TideVoice.GONG || voice == TideVoice.FLARE) DrumClass.TONAL else DrumClass.PERC,
        )

        return listOf(
            bongo(1, PENTATONIC[0]), bongo(2, PENTATONIC[1]), bongo(3, PENTATONIC[2]), bongo(4, PENTATONIC[3]), // A01-A04
            bongo(5, PENTATONIC[4]), bongo(6, PENTATONIC[5]), bongo(7, PENTATONIC[6]), bongo(8, PENTATONIC[7]), // A05-A08
            preset(TideVoice.DRIP, "RAIN DRIP"), preset(TideVoice.DRIP, "BUBBLE"),                              // A09 A10
            preset(TideVoice.DRIP, "ICE BLIP"), preset(TideVoice.DRIP, "SPLASH TICK"),                         // A11 A12
            preset(TideVoice.GONG, "TEMPLE GONG"), preset(TideVoice.GONG, "TIN CAN"),                          // A13 A14
            preset(TideVoice.FLARE, "SNARL FLARE"), preset(TideVoice.FLARE, "FOLD BASS"),                       // A15 A16
        )
    }

    /**
     * The SIREN kit (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md):
     * AIR RAID walks root, minor third, fifth and octave across A01–A04,
     * then TRILL and LASER one-shots (A05–A08), BIRD and the dive and the
     * climb (A09–A12), and the four LOOPs across the top row (A13–A16) —
     * the renders the SURFACE holds under a finger. Every one-shot carries
     * the landing's own ECHO in its recipe ([Siren.landingChain]); every
     * LOOP is dry and filed as the LOOP it is. Every pad carries its recipe.
     */
    fun siren(): List<ArrangedPad?> {
        val airRaid = SirenPresets.forVoice(SirenVoice.WAIL).first { it.name == "AIR RAID" }
        fun wail(n: Int, semitone: Int) = SirenPatch(
            "Wail $n", SirenVoice.WAIL,
            airRaid.macros + ("TUNE" to (12 + semitone) / Siren.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Siren.drumClassFor(it.voice, it.macros), Siren.landingChain(it.macros)) }
        fun preset(voice: SirenVoice, name: String) = SirenPresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Siren.drumClassFor(it.voice, it.macros), Siren.landingChain(it.macros)) }

        return listOf(
            wail(1, 0), wail(2, 3), wail(3, 7), wail(4, 12),                                                     // A01-A04
            preset(SirenVoice.TRILL, "TWO TONE"), preset(SirenVoice.TRILL, "PATROL"),                           // A05 A06
            preset(SirenVoice.LASER, "RAY GUN"), preset(SirenVoice.LASER, "DEEP LASER"),                        // A07 A08
            preset(SirenVoice.BIRD, "CHIRP"), preset(SirenVoice.BIRD, "LOW BIRD"),                              // A09 A10
            preset(SirenVoice.WAIL, "DIVE WAIL"), preset(SirenVoice.TRILL, "RISE TRILL"),                       // A11 A12
            preset(SirenVoice.WAIL, "WAIL LOOP"), preset(SirenVoice.TRILL, "TRILL LOOP"),                       // A13 A14
            preset(SirenVoice.LASER, "LASER LOOP"), preset(SirenVoice.BIRD, "BIRD LOOP"),                       // A15 A16
        )
    }

    /**
     * The FORK acceptance kit: a row of TINE across a spread of steps (the
     * electric piano the phone plays as a scale), then all eight of BAR's
     * own presets (the vibraphone-shaped voice) — the same "sweep a tune
     * range, then the named roster" shape [siren]'s own kit takes.
     */
    fun fork(): List<ArrangedPad?> {
        fun tine(n: Int, semitone: Int, strike: Float = 0.55f) = ForkPatch(
            "Tine $n", ForkVoice.TINE,
            Fork.defaults(ForkVoice.TINE) + mapOf("TUNE" to semitone / Fork.TUNE_SEMITONES.toFloat(), "STRIKE" to strike),
        ).let { pad(it, Fork.drumClassFor(it.voice, it.macros)) }
        fun bar(name: String) = ForkPresets.forVoice(ForkVoice.BAR).first { it.name == name }
            .let { pad(it, Fork.drumClassFor(it.voice, it.macros)) }

        return listOf(
            tine(1, 0), tine(2, 3), tine(3, 5), tine(4, 7),                                                     // A01-A04
            tine(5, 10), tine(6, 12), tine(7, 15), tine(8, 19),                                                  // A05-A08
            bar("VIBE BELL"), bar("COLD METAL"), bar("MALLET RING"), bar("BRIGHT CHIME"),                        // A09-A12
            bar("DEEP BAR"), bar("SHORT KNOCK"), bar("ROUND TONE"), bar("LOUD CLANG"),                           // A13-A16
        )
    }

    /**
     * The BORE acceptance kit: FLUTE and SAX each across a major triad and its octave
     * (A01-A08, so the pads play a chord and a scale step apart, the way [tide]'s bongos
     * climb), then four presets of each voice (A09-A16) chosen to be four different
     * things: a breathy note, a hard tongue, a swell and a LOOP for FLUTE; a low honk, a
     * bite, a high stab and a LOOP for SAX. Every one-shot carries the landing's own TAPE
     * in its recipe ([Bore.landingChain]); every LOOP is dry and filed as the LOOP it is.
     * A drum program plays every pad once through, LOOP or not (`Loop=False`, as SIREN's
     * LOOP pads do): the wrap is heard in the audition page's REPEAT, and held on a pad
     * once R2's held instrument exists. Every pad carries its recipe. The presets are
     * provisional ([BorePresets] says what of BORE has been heard: SAX one-shots and the
     * SOLO LOOP's earlier periodic loop, not the current loop or FLUTE's); this kit is
     * what the audition page's KIT section plays.
     */
    fun bore(): List<ArrangedPad?> {
        fun note(voice: BoreVoice, n: Int, semitone: Int) = BorePatch(
            "${voice.name.lowercase().replaceFirstChar { it.uppercase() }} $n", voice,
            Bore.defaults(voice) + ("TUNE" to semitone / Bore.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Bore.drumClassFor(it.voice, it.macros), Bore.landingChain(it.macros)) }
        fun preset(voice: BoreVoice, name: String) = BorePresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Bore.drumClassFor(it.voice, it.macros), Bore.landingChain(it.macros)) }

        return listOf(
            note(BoreVoice.FLUTE, 1, 0), note(BoreVoice.FLUTE, 2, 4), note(BoreVoice.FLUTE, 3, 7), note(BoreVoice.FLUTE, 4, 12),   // A01-A04
            note(BoreVoice.SAX, 1, 0), note(BoreVoice.SAX, 2, 4), note(BoreVoice.SAX, 3, 7), note(BoreVoice.SAX, 4, 12),           // A05-A08
            preset(BoreVoice.FLUTE, "BREATHY"), preset(BoreVoice.FLUTE, "HARD TONGUE"),                                           // A09 A10
            preset(BoreVoice.FLUTE, "SOFT SWELL"), preset(BoreVoice.FLUTE, "STEADY LOOP"),                                        // A11 A12
            preset(BoreVoice.SAX, "LOW HONK"), preset(BoreVoice.SAX, "BITE"),                                                     // A13 A14
            preset(BoreVoice.SAX, "HIGH STAB"), preset(BoreVoice.SAX, "SOLO LOOP"),                                               // A15 A16
        )
    }

    /**
     * HOLD for the ARCO kit's CELLO row: the bottom of the knob, 0.3 s of bow ([Arco.HOLD_MIN_SECONDS]),
     * the shortest stab the engine makes. Read off what the engine does there: the stroke at the default BOW
     * ([Arco.attackSeconds], 63 ms at BOW 0.5) is far under 0.85 of 0.3 s (0.255 s), so the
     * clamp ([Arco.ATTACK_HOLD_FRACTION]) never bites; the vibrato is scaled by how long the
     * note lasts and is nothing at 0.6 s of bow or less ([Arco.VIBRATO_HOLD_FROM_SECONDS]), so a stab is a
     * plain note and not a wobble; the stop after the bow lifts is its 0.15 s floor
     * ([Arco.STOP_FLOOR_SECONDS], and half the hold is the same 0.15), so the whole pad is 0.50 s, well
     * under the classifier's 1.5 s line, which files it PERC. Any longer HOLD would make the pad a held
     * note, which a pad that is struck wants least: the held note is the audition page's three-second set,
     * and the long note on this kit is its LOOP pad (A15). What it costs, measured on the rendered pads
     * (A01-A08, C2 to F3, the default BOW 0.5, in 25 ms windows): the string builds over the whole bow, so
     * the level is half of its peak at 0.11 to 0.16 s and 90 percent of it at 0.22 to 0.27 s, and the peak
     * comes at 0.27 to 0.29 s of a pad that is 0.500 s long, with the bow lifting just after, so each pad
     * is a swell cut at its top and not a hit.
     */
    private const val ARCO_STAB_HOLD = 0.0f

    /**
     * The ARCO acceptance kit: CELLO walking the minor pentatonic up the first two rows
     * (A01-A08, the root C2 first, the way [melodic] and [tide] climb) as stabs, so the pads play a
     * bass line and a tune a step apart; then the six ERHU presets (A09-A14); then the two LOOP
     * presets, CELLO's ENDLESS DRAW and ERHU's ENDLESS CRY (A15, A16). Every other macro on the
     * CELLO row is at its default, so the row is what the knobs sound like before anyone touches
     * them. Every pad is dry (a bowed note's own tail is its stop, and ARCO has no landing chain),
     * filed by [Arco.drumClassFor] (PERC for the stabs, LOOP for the two loops, and by its length
     * rule either for a preset: PERC under 1.5 s of render, LOOP over it), and carries its recipe. A drum program plays every pad once through, LOOP or not
     * (`Loop=False`, as BORE's and SIREN's LOOP pads do): the wrap is heard in the audition page's
     * REPEAT and its SURFACE stand-in. The presets are provisional until the audition gate; this
     * kit is what the page's kit section plays.
     */
    fun arco(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = ArcoPatch(
            "Cello $n", ArcoVoice.CELLO,
            Arco.defaults(ArcoVoice.CELLO) + mapOf(
                "TUNE" to semitone / Arco.CELLO_TUNE_SEMITONES.toFloat(),
                "HOLD" to ARCO_STAB_HOLD,
            ),
        ).let { pad(it, Arco.drumClassFor(it.voice, it.macros)) }
        fun preset(voice: ArcoVoice, name: String) = ArcoPresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Arco.drumClassFor(it.voice, it.macros)) }

        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),   // A01-A04
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),   // A05-A08
            preset(ArcoVoice.ERHU, "NASAL LINE"), preset(ArcoVoice.ERHU, "MOON FIDDLE"),                      // A09 A10
            preset(ArcoVoice.ERHU, "THIN SCRAPE"), preset(ArcoVoice.ERHU, "HIGH CRY"),                        // A11 A12
            preset(ArcoVoice.ERHU, "SLOW CRY"), preset(ArcoVoice.ERHU, "TEA HOUSE"),                          // A13 A14
            preset(ArcoVoice.CELLO, "ENDLESS DRAW"), preset(ArcoVoice.ERHU, "ENDLESS CRY"),                   // A15 A16
        )
    }

    /**
     * The MERCURY acceptance kit: PING up the minor pentatonic from its root, C4, on the first two rows
     * (A01-A08), every knob but TUNE at its default, so the row is the bell before anyone touches it;
     * then four SING presets (A09-A12) and four BLADE presets (A13-A16). Every pad is dry (MERCURY has no
     * landing chain) and filed by [Mercury.drumClassFor]. Every MERCURY render is its contact plus a
     * ringing tail of at least 0.4 s, and at the default GLASS that is past the classifier's 1.5 s line, so
     * the length rule files these pads LOOP; a drum program plays every pad once through either way. The
     * presets are provisional until the audition gate; this kit is what the page's kit section plays.
     */
    fun mercury(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = MercuryPatch(
            "Ping $n", MercuryVoice.PING,
            Mercury.defaults(MercuryVoice.PING) + ("TUNE" to semitone / Mercury.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Mercury.drumClassFor(it.voice, it.macros)) }
        fun preset(voice: MercuryVoice, name: String) = MercuryPresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Mercury.drumClassFor(it.voice, it.macros)) }

        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),   // A01-A04
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),   // A05-A08
            preset(MercuryVoice.SING, "LONG RUB"), preset(MercuryVoice.SING, "SINGING EDGE"),                 // A09 A10
            preset(MercuryVoice.SING, "GLASS CURRENT"), preset(MercuryVoice.SING, "LOW HUM"),                 // A11 A12
            preset(MercuryVoice.BLADE, "BENT RIBBON"), preset(MercuryVoice.BLADE, "WHISTLE BEND"),            // A13 A14
            preset(MercuryVoice.BLADE, "DOWN BEND"), preset(MercuryVoice.BLADE, "WOBBLE STEEL"),              // A15 A16
        )
    }

    /**
     * The second MERCURY kit (R2c): the three voices the first kit does not carry, as their own presets, none of them
     * at the default TUNE. EDDY five (A01-A05), VESSEL five (A06-A10), SHARD six (A11-A16), each a one-shot pad, dry, filed
     * by [Mercury.drumClassFor] (LOOP by length, like the first). The first kit stays as the owner heard it, the pentatonic
     * row included; this one is the audition's way to hear the three on the MPC. Provisional until their audition.
     */
    fun mercury2(): List<ArrangedPad?> {
        fun preset(voice: MercuryVoice, name: String) = MercuryPresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Mercury.drumClassFor(it.voice, it.macros)) }

        return listOf(
            preset(MercuryVoice.EDDY, "HEARTH HUM"), preset(MercuryVoice.EDDY, "TWIN BEATING"),               // A01 A02
            preset(MercuryVoice.EDDY, "SLOW SWIRL"), preset(MercuryVoice.EDDY, "RIM WAVER"),                  // A03 A04
            preset(MercuryVoice.EDDY, "STRUCK BOWL"),                                                         // A05
            preset(MercuryVoice.VESSEL, "CISTERN"), preset(MercuryVoice.VESSEL, "DRAIN PIPE"),                // A06 A07
            preset(MercuryVoice.VESSEL, "BOILER HUM"), preset(MercuryVoice.VESSEL, "WATER TOWER"),            // A08 A09
            preset(MercuryVoice.VESSEL, "TWIN PIPES"),                                                        // A10
            preset(MercuryVoice.SHARD, "BROKEN PANE"), preset(MercuryVoice.SHARD, "SPLINTER"),                // A11 A12
            preset(MercuryVoice.SHARD, "SKITTER"), preset(MercuryVoice.SHARD, "HAIRLINE"),                    // A13 A14
            preset(MercuryVoice.SHARD, "FRAYED EDGE"), preset(MercuryVoice.SHARD, "TIN SKY"),                 // A15 A16
        )
    }

    /**
     * The LEAD family's amp (shape), written in the kit: DRIVE 0.78 is gain 37 on VALVE's law, a
     * cooler drive than CHUG's landing (DRIVE 0.85, gain 106; it was hotter than the first build's
     * gain 13), with SAG and CAB as CHUG's and TONE 0.5 (flat) against CHUG's 0.3. The owner heard
     * the lead pads at the gate and called them usable; the chain was left as built.
     */
    private val LEAD_VALVE = mapOf("DRIVE" to 0.78f, "SAG" to 0.4f, "TONE" to 0.5f, "CAB" to 0.95f)

    private fun magnetNote(
        voice: MagnetVoice,
        semitone: Int,
        name: String,
        extra: Map<String, Float> = emptyMap(),
        fx: FxChain = Magnet.landingChain(voice),
    ) = pad(
        MagnetPatch(name, voice, Magnet.defaults(voice) + mapOf("TUNE" to semitone / Magnet.TUNE_SEMITONES.toFloat()) + extra),
        DrumClass.TONAL,
        fx,
    )

    /**
     * The MAGNET R1 kit: a roster of defaults plus TUNE, with no preset lookups. A01-A08 are a B minor
     * riff on CHUG, A09-A14 an open E chord on JANGLE, A15-A16 the LEAD family: CHUG, BLEND toward the
     * neck, through the lead amp above. Every pad lands through its voice's VALVE chain
     * ([Magnet.landingChain]) and carries its recipe.
     */
    fun magnet(): List<ArrangedPad?> {
        val riff = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17)
        val chord = intArrayOf(0, 7, 12, 16, 19, 24)
        val lead = intArrayOf(19, 24)
        val leadChain = FxChain().withSection("valve", LEAD_VALVE)
        return buildList {
            riff.forEachIndexed { i, s -> add(magnetNote(MagnetVoice.CHUG, s, "Chug ${i + 1}")) }
            chord.forEachIndexed { i, s -> add(magnetNote(MagnetVoice.JANGLE, s, "Jangle ${i + 1}")) }
            lead.forEachIndexed { i, s -> add(magnetNote(MagnetVoice.CHUG, s, "Lead ${i + 1}", mapOf("BLEND" to 0.35f), leadChain)) }
        }
    }

    /** The reviewed dry BALLAST kit, retaining each pad's saved voice and macro recipe. */
    fun ballast(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = BallastPatch(
            "Root $n", BallastVoice.ROOT,
            Ballast.defaults(BallastVoice.ROOT) + ("TUNE" to (12 + semitone) / Ballast.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Ballast.drumClassFor(it.voice, it.macros)) }
        fun preset(voice: BallastVoice, name: String) = BallastPresets.forVoice(voice).first { it.name == name }
            .let { pad(it, Ballast.drumClassFor(it.voice, it.macros)) }

        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),   // A01-A04
            preset(BallastVoice.WIRE, "LONG STRING"), preset(BallastVoice.WIRE, "UPPER HALO"),                // A05 A06
            preset(BallastVoice.DEEP, "LOWER REPLY"), preset(BallastVoice.DEEP, "LOOSE MOUNT"),                // A07 A08
            preset(BallastVoice.GLINT, "GLASS WAKE"), preset(BallastVoice.GLINT, "DENSE TILES"),              // A09 A10
            preset(BallastVoice.BLOOM, "DELAYED OPEN"), preset(BallastVoice.BLOOM, "SLOW CLIMB"),             // A11 A12
            preset(BallastVoice.SWARM, "DENSE RATTLE"), preset(BallastVoice.SWARM, "TILE STORM"),             // A13 A14
            preset(BallastVoice.DEEP, "LOW WELL"), preset(BallastVoice.BLOOM, "WARM LIFT"),                  // A15 A16
        )
    }

    /**
     * The AEROSTAT acceptance kit. A01–A08 walk the minor pentatonic from C3 with
     * every other knob at its default, so the row is the instrument before anyone
     * touches it. A09–A13 are the five factory presets. A14 and A15 climb two
     * more notes, and A16 is the held loop (the strike is not in that buffer).
     * Every pad is dry: the whistle and the vessel are the engine, not a rack
     * section. Filed by [Aerostat.drumClassFor], which is PERC or LOOP and never
     * a drum class.
     */
    fun aerostat(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int, extra: Map<String, Float> = emptyMap()) = AerostatPatch(
            "Float $n", AerostatVoice.FLOAT,
            Aerostat.defaults(AerostatVoice.FLOAT) + mapOf("TUNE" to semitone / Aerostat.TUNE_SEMITONES.toFloat()) + extra,
        ).let { pad(it, Aerostat.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = AerostatPresets.forVoice(AerostatVoice.FLOAT).first { it.name == name }
            .let { pad(it, Aerostat.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("SOFT CATCH"), preset("TWIN PIPES"), preset("HEAVY ROTOR"), preset("DRIFTING STEAM"),
            preset("HIGH ENVELOPE"),
            note(9, 14),
            note(10, 19),
            note(11, 24, mapOf("HOLD" to 1f)),
        )
    }

    /**
     * The FLOTILLA kit: RIPPLE up the minor pentatonic from C4 on A01–A08, then eight presets
     * that walk the rest of the idea (open wood, a large hull, crossing routes, the warm dome,
     * a gentle drift, a dense gather, and the two held notes). Every pad is dry. The length rule
     * files them LOOP. A drum program plays each pad once; the held pads' wrap is the audition's.
     */
    fun flotilla(): List<ArrangedPad?> {
        val walk = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17)
        fun note(n: Int, semi: Int) = FlotillaPatch(
            "Wake $n",
            FlotillaVoice.RIPPLE,
            Flotilla.defaults(FlotillaVoice.RIPPLE),
            midi = (60 + semi).coerceIn(Flotilla.MIDI_MIN, Flotilla.MIDI_MAX),
        ).let { pad(it, Flotilla.drumClassFor(it.voice, it.macros)) }
        fun preset(voice: FlotillaVoice, name: String) =
            FlotillaPresets.forVoice(voice).first { it.name == name }
                .let { pad(it, Flotilla.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, walk[0]), note(2, walk[1]), note(3, walk[2]), note(4, walk[3]),
            note(5, walk[4]), note(6, walk[5]), note(7, walk[6]), note(8, walk[7]),
            preset(FlotillaVoice.KNOCK, "Open Wood"),
            preset(FlotillaVoice.HOLLOW, "Deep Cavity"),
            preset(FlotillaVoice.CROSSWAVE, "Crossing Paths"),
            preset(FlotillaVoice.DRIFT, "Warm Canopy"),
            preset(FlotillaVoice.DRIFT, "Gentle Current"),
            preset(FlotillaVoice.GATHER, "Gathered Vessels"),
            preset(FlotillaVoice.DRIFT, "Held Sparse"),
            preset(FlotillaVoice.GATHER, "Held Dense"),
        )
    }

    /**
     * A01–A08 walk C minor pentatonic from C4 with all six surface profiles:
     * clear strike, delayed rain, spreading cascade, wet basin, thin ripple,
     * draining return, soft skin and an isolated heavy answer. A09–A16 add
     * low-register landings and sustained liquid, high-register articulation,
     * and a settled held circle. All twelve presets appear, with deliberate
     * register repeats; every recipe is dry and stores its exact MIDI pitch.
     */
    fun cistern(): List<ArrangedPad?> {
        fun preset(name: String, midi: Int) =
            CisternPresets.all().first { it.name == name }.copy(midi = midi)
                .let { pad(it, Cistern.drumClassFor(it.voice, it.macros)) }
        return listOf(
            preset("First Drop", 60), preset("Hanging Rain", 63),
            preset("Wide Cascade", 65), preset("Wet Basin", 67),
            preset("Thin Ripple", 70), preset("Clear Return", 72),
            preset("Soft Skin", 75), preset("Quiet Reservoir", 77),
            preset("Heavy Landing", 48), preset("Slow Drain", 55),
            preset("Dense Surface", 63), preset("Hanging Rain", 67),
            preset("Wet Basin", 48), preset("Thin Ripple", 75),
            preset("Clear Return", 70), preset("Replenished Circle", 60),
        )
    }

    /**
     * The TREMOR kit. A01–A08 are HIDE walking the minor pentatonic from C2, every knob but TUNE at
     * the voice default, so the row is the drum before anyone touches it. A09–A14 are one-shot
     * presets (four hands, beads, a passive cage, a powered bloom, a late wire, a dense fault).
     * A15–A16 are the two held presets.
     *
     * Every pad is dry and filed by [Tremor.drumClassFor]. Export leaves the mute group at 0: a
     * tuned drum rings through the next hit, and hat-style choke is the wrong contract for this
     * instrument. Held pads are still one-shots in the program (the wrap is the sample's own loop,
     * proved by the seam gate, the way MERCURY's long pads are filed LOOP and played once).
     */
    fun tremor(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = TremorPatch(
            "Hide $n", TremorVoice.HIDE,
            Tremor.defaults(TremorVoice.HIDE) + ("TUNE" to semitone / Tremor.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Tremor.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = TremorPresets.all().first { it.name == name }
            .let { pad(it, Tremor.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("FOUR HANDS"), preset("SETTLING BED"), preset("DRY CAGE"), preset("CHARGED TAIL"),
            preset("LATE STRAND"), preset("BROKEN RETURN"), preset("HELD DRUM"), preset("HELD BLOOM"),
        )
    }

    /**
     * CIRCUIT's pitched ensemble kit. The first two rows walk C minor
     * pentatonic from C2; the remaining pads explore every voice and end
     * with the recurring ensemble. Every pad carries its editable recipe
     * and lands dry so the circle and canyon remain the engine's own paths.
     */
    fun circuit(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = CircuitPatch(
            "Circuit $n", CircuitVoice.ROOT,
            Circuit.defaults(CircuitVoice.ROOT) + ("TUNE" to semitone / Circuit.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Circuit.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = CircuitPresets.all().first { it.name == name }
            .let { pad(it, Circuit.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("SLOW PARADE"), preset("MOVING ACCENTS"), preset("PAIRED WOOD"), preset("CLAY REPLY"),
            preset("CHEST GESTURE"), preset("WIDE CIRCLE"), preset("FAST GATHER"), preset("HELD CIRCLE"),
        )
    }

    /**
     * The MURK audition kit: CLUNK climbs the C minor pentatonic on A01–A08, then six sounds
     * explore sharp wood, heavy fog and owl answers. A15–A16 carry settled recurring groves.
     * Every pad is dry, carries its regenerable recipe, and is filed by [Murk.drumClassFor].
     */
    fun murk(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = MurkPatch(
            "Wood $n", MurkVoice.CLUNK,
            Murk.defaults(MurkVoice.CLUNK) + ("TUNE" to semitone / Murk.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Murk.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = MurkPresets.all().first { it.name == name }
            .let { pad(it, Murk.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("TONAL AXE"), preset("FIRST PULSE"), preset("DISTANT CALL"), preset("HEAVY AIR"),
            preset("ANSWERING WOOD"), preset("SOFT BARK"), preset("HELD TREES"), preset("HELD OWLS"),
        )
    }

    /**
     * The dry NIMBUS kit. A01–A08 walk RING up C minor pentatonic from C3. The top half
     * explores shimmer, stack restoration, chamber geometry and rim contact, then holds metal.
     * Each pad keeps its full recipe and the engine's pitched routing.
     */
    fun nimbus(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = NimbusPatch(
            "Metal $n", NimbusVoice.RING,
            Nimbus.defaults(NimbusVoice.RING) + ("TUNE" to semitone / Nimbus.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Nimbus.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = NimbusPresets.all().first { it.name == name }
            .let { pad(it, Nimbus.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("THIN CROWN"), preset("GATHERING STACK"), preset("NARROW THROAT"), preset("WIDE MOUTH"),
            preset("DARK PLATE"), preset("SOFT FIELD"), preset("RIM KISS"), preset("HELD METAL"),
        )
    }

    /**
     * TESSERA's dry pitched kit. A01–A08 walk WOOD up C minor pentatonic from C3; A09–A16
     * contain the eight named material and chamber recipes, ending with settled held material.
     * Every pad stores its complete recipe. Frame transfers and chamber answers come from the
     * engine, with no external effect, mute group or shared state between independently rendered pads.
     */
    fun tessera(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = TesseraPatch(
            "Tessera $n", TesseraVoice.WOOD,
            Tessera.defaults(TesseraVoice.WOOD) + ("TUNE" to semitone / Tessera.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Tessera.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            TesseraPresets.all().map { pad(it, Tessera.drumClassFor(it.voice, it.macros)) }
    }

    /**
     * Eight REED notes in C minor pentatonic followed by the eight dry audition presets.
     * Every pad carries the model, player gesture and native velocity in its recipe.
     * Finite notes are TONAL; Held Effort is a settled LOOP for repeated playback.
     */
    // The original sixteen-pad acceptance kit is a saved factory contract. The experimental GROW
    // presets (EmbouchurePresets.experimental) are available in SYNTH without changing that kit.
    fun embouchure(): List<ArrangedPad?> = embouchureStarterPatches().map(::embouchurePad)

    /** The original starter layout remains fixed when the selectable factory roster grows. */
    fun embouchureStarterPatches(): List<EmbouchurePatch> {
        val initial = EmbouchurePresets.initial()
        val lead = initial.single { it.name == "Leaning Reed" }
        val notes = PENTATONIC.take(8).mapIndexed { i, semitone ->
            lead.copy(name = "Reed ${i + 1}", midi = Embouchure.rootMidi(lead.voice) + semitone,
                macros = lead.macros + ("TUNE" to 0f))
        }
        return notes + initial
    }

    private fun embouchurePad(patch: EmbouchurePatch): ArrangedPad =
        pad(patch, Embouchure.drumClassFor(patch.voice, patch.macros)).copy(displayName = patch.name)

    /** Twelve authored presets followed by four complementary notes; every render stays dry. */
    data class EmbouchureFactoryBank(
        val id: String,
        val name: String,
        val presetPatches: List<EmbouchurePatch>,
        val notePatches: List<EmbouchurePatch>,
    ) {
        init {
            require(presetPatches.size == 12 && notePatches.size == 4) {
                "EMBOUCHURE factory banks contain twelve presets and four keyed notes"
            }
            require((presetPatches + notePatches).all { it.model == Embouchure.MODEL_VERSION }) {
                "EMBOUCHURE factory banks use the current renderer model"
            }
        }

        val patches: List<EmbouchurePatch> get() = presetPatches + notePatches
        val noteSlots: List<Int> get() = (13..16).toList()
        fun render(): List<ArrangedPad?> = patches.map { SynthKits.embouchurePad(it) }
    }

    /**
     * Three authored factory banks cover each of the thirty-six presets exactly once.
     * Paired voices keep their own gesture, note and velocity; pads 13–16 add root/fifth
     * and minor-third/seventh answers in C minor pentatonic, with explicit finite notes.
     * These additional exports leave the original FRESH TAPE starter unchanged.
     */
    fun embouchureFactoryBanks(): List<EmbouchureFactoryBank> {
        val presets = EmbouchurePresets.all()
        require(presets.size == 36 && presets.map { it.name }.distinct().size == 36) {
            "EMBOUCHURE factory banks require thirty-six distinct authored presets"
        }
        val initial = EmbouchurePresets.initial().associateBy { it.name }
        fun note(preset: String, name: String, semitone: Int): EmbouchurePatch {
            val source = initial.getValue(preset)
            return source.copy(name = name, midi = Embouchure.rootMidi(source.voice) + semitone,
                macros = source.macros + mapOf("TUNE" to 0f, "HOLD" to 0f))
        }
        fun bank(id: String, name: String, voices: List<EmbouchureVoice>, notes: List<EmbouchurePatch>) =
            EmbouchureFactoryBank(id, name,
                voices.flatMap { voice -> presets.filter { it.voice == voice } }, notes)
        return listOf(
            bank("embouchure-reeds-air", "Embouchure Reeds and Air",
                listOf(EmbouchureVoice.REED, EmbouchureVoice.WHISTLE), listOf(
                    note("Leaning Reed", "Reed Root C3", 0), note("Leaning Reed", "Reed Fifth G3", 7),
                    note("Narrow Air", "Air Third Eb4", 3), note("Narrow Air", "Air Seventh Bb4", 10),
                )),
            bank("embouchure-brass-bass", "Embouchure Brass and Bass",
                listOf(EmbouchureVoice.BRASS, EmbouchureVoice.BASS), listOf(
                    note("Breath Pocket", "Bass Root C2", 0), note("Breath Pocket", "Bass Fifth G2", 7),
                    note("Muted Bell", "Brass Third Eb3", 3), note("Muted Bell", "Brass Seventh Bb3", 10),
                )),
            bank("embouchure-bow-soft", "Embouchure Bow and Soft",
                listOf(EmbouchureVoice.BOW, EmbouchureVoice.SOFT), listOf(
                    note("Relaxed Bow", "Bow Root C3", 0), note("Relaxed Bow", "Bow Fifth G3", 7),
                    note("Soft Entry", "Soft Third Eb3", 3), note("Soft Entry", "Soft Seventh Bb3", 10),
                )),
        )
    }

    /** Four accepted OBBLIGATO relationships across C4, Eb4, F4 and G4, with native velocity zones. */
    fun obbligato(): List<ArrangedPad?> = ObbligatoKits.build()

    /**
     * A dry pitched wheel kit for the first audition. A01–A08 walk CLUNK up C minor
     * pentatonic from C3. A09–A14 contain the six voice defaults, A15 adds the next
     * scale note, and A16 is the settled held TURN. Each pad has a fresh independent
     * object and an editable recipe with its exact root; finite gestures use TONAL
     * routing and HOLD uses LOOP, preserving the instrument's pitched identity.
     */
    fun pitchwheel(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = PitchwheelPatch(
            "Wheel $n", PitchwheelVoice.CLUNK, Pitchwheel.defaults(PitchwheelVoice.CLUNK),
            midi = Pitchwheel.DEFAULT_MIDI + semitone,
        ).let { pad(it, Pitchwheel.drumClassFor(it.voice, it.macros)) }
        val defaults = PitchwheelPresets.all().filterNot { Pitchwheel.isLoop(it.macros.getValue("HOLD")) }
        val held = PitchwheelPresets.all().single { Pitchwheel.isLoop(it.macros.getValue("HOLD")) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            defaults.map { pad(it, Pitchwheel.drumClassFor(it.voice, it.macros)) } +
            listOf(note(9, PENTATONIC[8]), pad(held, Pitchwheel.drumClassFor(held.voice, held.macros)))
    }

    /**
     * Sixteen dry bellows pads: eight CHAMBER notes in C minor pentatonic from
     * C3 and the original eight contrasting presets. The larger preset library
     * does not change this kit's sixteen-pad layout. Each recipe retains its MIDI base,
     * excitation velocity, model and bellows gesture. Held presets route as LOOP.
     */
    fun bellows(): List<ArrangedPad?> {
        fun landed(patch: BellowsPatch) = pad(patch, Bellows.drumClassFor(patch.voice, patch.macros))
            .copy(displayName = patch.name)
        val scale = PENTATONIC.take(8).mapIndexed { i, semitone ->
            landed(BellowsPatch(
                "Bellows ${i + 1}", BellowsVoice.CHAMBER,
                Bellows.defaults(BellowsVoice.CHAMBER) + ("TUNE" to 0f),
                midi = Bellows.ROOT_MIDI + semitone,
            ))
        }
        val contrasts = listOf(
            "Amber Breath", "Silver Ranks", "Midnight Pull", "Forward Push",
            "Village Stab", "Dancing Reeds", "Quiet Wheel", "Wrist Sparks",
        )
        return scale + contrasts.map { name -> BellowsPresets.all().first { it.name == name } }.map(::landed)
    }

    /**
     * Sixteen dry FM/formant pads for the first audition. A01–A08 walk KEY up
     * C minor pentatonic from C3, with explicit roots rather than a tuning macro.
     * The upper pads contrast all six roles and end with settled held choir,
     * delivered once with a saved 8 ms terminal fade rather than a hard stop.
     * Every pad carries operator, mouth and envelope state in its complete recipe.
     */
    fun murmur(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = MurmurPatch(
            "Murmur $n", MurmurVoice.KEY, Murmur.defaults(MurmurVoice.KEY),
            midi = 48 + semitone, config = Murmur.configurationFor(MurmurVoice.KEY, model = 2),
        ).let { pad(it, Murmur.drumClassFor(it.voice, it.macros)) }
        val contrasts = listOf(
            "CLOSED CIRCUIT", "ROUND HUM", "DUST VOWEL", "POCKET VOICE",
            "NARROW CALL", "SMALL CHOIR", "GLASS MOUTH", "HELD HUM",
        )
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            contrasts.map { name ->
                MurmurPresets.all().first { it.name == name }
                    .let {
                        if (name != "HELD HUM") pad(it, Murmur.drumClassFor(it.voice, it.macros))
                        else {
                            // HOLD supplies a seamless source; this starter pad plays it once.
                            // Keep the source recipe intact and save only its delivery edge fade.
                            val recipe = PadRecipe(it, terminalFadeMs = 8f)
                            ArrangedPad(recipe.render(), Murmur.drumClassFor(it.voice, it.macros), recipe.toJsonValue())
                        }
                    }
            }
    }

    /** Eight native-note lead pads followed by the accepted Round 3 source recipes. */
    fun openSky(): List<ArrangedPad?> {
        val presets = OpenSkyPresets.all()
        val lead = presets.first { it.name == "Solar Arc" }
        val notes = PENTATONIC.take(8).mapIndexed { i, semitone ->
            pad(lead.copy(name = "OpenSky ${i + 1}", midi = 48 + semitone), DrumClass.TONAL)
        }
        return notes + presets.map { pad(it, DrumClass.TONAL) }
    }

    /**
     * Fifteen dry shore/sea comparisons and one intermediate coast with extended
     * excitation. All sixteen pads are finite SWELL gestures at C4, retaining
     * their note, seed, duration and model in the regenerable recipe.
     */
    fun osprey(): List<ArrangedPad?> {
        val midpoint = OspreyPatch(
            "BETWEEN SHORES", OspreyVoice.SWELL,
            Osprey.defaults(OspreyVoice.SWELL) + mapOf(
                "SHORE" to .375f, "SEA" to .5f, "MOTION" to .45f,
                "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to 1f,
            ),
            seed = 731,
        )
        return (OspreyPresets.coastWeather() + midpoint).map { patch ->
            pad(patch, Osprey.drumClassFor(patch.voice, patch.macros))
        }
    }

    /**
     * Sixteen named model-3 sea gestures in C minor pentatonic: low foundations,
     * short notes, moving textures and long swells. Every pad plays its complete
     * finite render and retains its own note, velocity, seed and duration.
     */
    fun ospreyCurated(): List<ArrangedPad?> = OspreyCuratedPresets.all().map { patch ->
        pad(patch, Osprey.drumClassFor(patch.voice, patch.macros)).copy(displayName = patch.name)
    }

    /**
     * Eight dry harmonic keys in C minor pentatonic followed by the eight
     * curated spectral recipes. Each note rebuilds its own partial phases
     * and contours; finite notes are TONAL and the held veil is LOOP.
     */
    fun prism(): List<ArrangedPad?> {
        // Keep the original sixteen-pad layout as the selectable factory bank grows.
        val bank = PrismPresets.all()
        val presets = listOf(
            "Soft Facets", "Wooden Spectrum", "Partial Tines", "Hollow Register",
            "Root Glass", "Slow Separation", "Small Prism", "Amber Veil",
        ).map { name -> bank.first { it.name == name } }
        fun note(n: Int, semitone: Int) = PrismPatch(
            "Prism $n", PrismVoice.KEY,
            presets.first().macros +
                ("TUNE" to semitone / Prism.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Prism.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            presets.map { pad(it, Prism.drumClassFor(it.voice, it.macros)) }
    }

    /**
     * Sixteen dry hybrid pads: eight Long Prism KEY notes in C minor pentatonic,
     * six distinct finite factory roles, and the original settled held STRING/SOFT presets.
     * Every pad saves the generated attack, PM routing, envelopes, root and energy.
     */
    fun alloy(): List<ArrangedPad?> {
        val presets = AlloyPresets.all()
        val key = AlloyPresets.forVoice(AlloyVoice.KEY).single { it.name == "Long Prism" }
        fun landed(patch: AlloyPatch) = pad(patch, Alloy.drumClassFor(patch.voice, patch.macros))
        val scale = PENTATONIC.take(8).mapIndexed { i, semitone ->
            landed(key.copy(name = "Alloy ${i + 1}", midi = Alloy.DEFAULT_MIDI + semitone))
        }
        val finite = listOf("Hollow Rail", "Reed Switch", "Open Chime", "Bright Stamp",
            "Beating Glass", "Velvet Rise").map { name -> landed(presets.single { it.name == name }) }
        // These settled-loop references stay stable while the finite factory roles change.
        val heldReferences = AlloyPresets.heldReferences()
        val held = listOf("Held Filament", "Endless Ember").map { name ->
            landed(heldReferences.single { it.name == name })
        }
        return scale + finite + held

    }

    /**
     * Eight CRANE notes climb C minor pentatonic, followed by the six model-4 presets and
     * the accepted FAN/STILL source tones. Every pad is a dry finite tonal note with its
     * complete editable recipe; TUNE chooses the note without an overriding MIDI field.
     */
    fun origami(): List<ArrangedPad?> {
        fun arranged(patch: OrigamiPatch): ArrangedPad {
            val midi = patch.midi ?: Origami.midiFor(patch.voice, patch.macros.getValue("TUNE"))
            require(midi in Origami.MIDI_MIN..Origami.MIDI_MAX) { "ORIGAMI starter note out of range: $midi" }
            return pad(patch, DrumClass.TONAL).copy(
                displayName = patch.name,
                source = mapOf("rootMidi" to midi.toString(), "rootNote" to Scales.nameOf(midi), "rootOrigin" to "authored"),
                oneShot = true,
            )
        }
        val crane = OrigamiPresets.forVoice(OrigamiVoice.CRANE).first()
        val notes = PENTATONIC.take(8).mapIndexed { index, semitone ->
            val midi = Origami.MIDI_MIN + semitone
            require(midi in Origami.MIDI_MIN..Origami.MIDI_MAX) { "ORIGAMI starter note out of range: $midi" }
            crane.copy(
                name = "Paper ${index + 1}",
                macros = crane.macros + ("TUNE" to (midi - Origami.MIDI_MIN) / Origami.TUNE_SEMITONES.toFloat()),
            )
        }
        val sources = listOf(OrigamiVoice.FAN, OrigamiVoice.STILL).map { voice ->
            val reference = OrigamiPresets.forVoice(voice).first()
            reference.copy(
                name = if (voice == OrigamiVoice.FAN) "Fan Source" else "Still Source",
                macros = reference.macros + mapOf("FOLD" to 0f, "MOTION" to 0f),
            )
        }
        return (notes + OrigamiPresets.all() + sources).map(::arranged)
    }

    /**
     * A dry groove kit: anchored bass and tine rows, then a muted/bright clav pair,
     * hollow organ, whistle, bright fifth chord and octave bell answer. The final two pads hold.
     * Exact note, source registration, answer and chord voicing stay in every recipe.
     */
    fun stoop(): List<ArrangedPad?> {
        fun sound(voice: StoopVoice, name: String, midi: Int, held: Boolean = false, shadow: Float? = null, presetName: String? = null): ArrangedPad {
            val presets = StoopPresets.forVoice(voice)
            val preset = if (presetName == null) presets.first() else presets.single { it.name == presetName }
            val macros = preset.macros + ("HOLD" to if (held) 1f else 0f) +
                (shadow?.let { mapOf("SHADOW" to it) } ?: emptyMap())
            val patch = preset.copy(name = name, macros = macros, midi = midi,
                chordIntervals = if (voice == StoopVoice.CHORD) listOf(0, 7, 12) else preset.chordIntervals)
            val tags = mapOf("rootMidi" to midi.toString(), "rootNote" to Scales.nameOf(midi), "factoryPreset" to preset.name) +
                if (voice == StoopVoice.CHORD) mapOf("chordIntervals" to patch.chordIntervals.joinToString(",")) else emptyMap()
            return pad(patch, Stoop.drumClassFor(voice, macros)).copy(displayName = name, source = tags)
        }
        return listOf(
            sound(StoopVoice.BASS, "Bass C2", 36), sound(StoopVoice.BASS, "Bass Eb2", 39),
            sound(StoopVoice.BASS, "Bass F2", 41), sound(StoopVoice.BASS, "Bass G2", 43),
            sound(StoopVoice.TINE, "Tine C4", 60), sound(StoopVoice.TINE, "Tine Eb4", 63),
            sound(StoopVoice.TINE, "Tine F4", 65), sound(StoopVoice.TINE, "Tine G4", 67),
            sound(StoopVoice.CLAV, "Clav C3", 48), sound(StoopVoice.CLAV, "Wire Clav G3", 55, presetName = "Short Wire"),
            sound(StoopVoice.ORGAN, "Hollow Organ C3", 48, presetName = "Small Pipes"),
            sound(StoopVoice.LEAD, "Whistle C5", 72, presetName = "Pocket Whistle"),
            sound(StoopVoice.CHORD, "Bright Fifth C3", 48, presetName = "Small Cinema"),
            sound(StoopVoice.TINE, "Bell Answer C4", 60, shadow = .6f, presetName = "After Key"),
            sound(StoopVoice.ORGAN, "Held Organ C3", 48, held = true),
            sound(StoopVoice.CHORD, "Held Fifth C3", 48, held = true),
        )

    }

    /**
     * A dry layered-tone audition kit. Eight KEY notes climb C minor pentatonic;
     * the upper half keeps the original eight reference recipes, including held SILK.
     * Each pad keeps its root, velocity and complete layer profile for regeneration.
     */
    fun lacquer(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = LacquerPatch(
            "Lacquer $n", LacquerVoice.KEY, Lacquer.defaults(LacquerVoice.KEY),
            midi = Lacquer.DEFAULT_MIDI + semitone,
        ).let { pad(it, Lacquer.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            LacquerPresets.references().map { pad(it, Lacquer.drumClassFor(it.voice, it.macros)) }
    }

    /**
     * SKITTER's dry pitched kit. The first eight pads walk BASS up C minor
     * pentatonic from C3; the remaining pads expose clean, hollow, fluttering,
     * chirping and frayed reactions, ending with two seamless held recipes.
     */
    fun skitter(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = SkitterPatch(
            "Circuit $n", SkitterVoice.BASS, Skitter.defaults(SkitterVoice.BASS),
            midi = Skitter.DEFAULT_MIDI + semitone,
        ).let { pad(it, Skitter.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = SkitterPresets.all().first { it.name == name }
            .let { pad(it, Skitter.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } + listOf(
            preset("Quiet Circuit"), preset("Nervous Wire"), preset("Open Interval"), preset("Aftershock"),
            preset("Pitched Chirp"), preset("Soft Fray"), preset("Held Flutter"), preset("Frayed Sustain"),
        )
    }

    /**
     * The dry TANGLE kit. A01–A08 walk BASS up C minor pentatonic from C2;
     * A09–A14 expose leads, a hollow pair, crawling pulses, metal and knots.
     * The last two pads hold the oscillator relationship. Every pad saves its
     * root and velocity and renders without an FX chain: the movement belongs
     * to the interacting oscillators. Finite sounds route as TONAL, held as LOOP.
     */
    fun tangle(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = TanglePatch(
            "Tangle Bass $n", TangleVoice.BASS, Tangle.defaults(TangleVoice.BASS),
            midi = Tangle.rootMidi(TangleVoice.BASS) + semitone,
        ).let { pad(it, Tangle.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = TanglePresets.all().first { it.name == name }
            .let { pad(it, Tangle.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("Catch Wire"), preset("Hollow Pair"), preset("Crawling Teeth"), preset("Metal Recoil"),
            preset("Tight Knot"), preset("Quiet Trap"), preset("Long Escape"), preset("Held Struggle"),
        )
    }

    /** Sixteen dry finite CHAMBER notes and saved room contrasts, rendered at their native pitches. */
    fun chamber(): List<ArrangedPad?> = ChamberKits.build()

    /**
     * Eight fuller Alloy notes in C minor pentatonic from C3, then the two heard
     * Alloy anchors, three character voices, and sparse, short and low positions.
     * Every pad is finite and dry; a duration-based LOOP class is a
     * filename/category, not a seamless sustain claim. All sixteen save their patch.
     */
    fun caustic(): List<ArrangedPad?> {
        fun arranged(patch: CausticPatch) = pad(patch, CausticInstrument.drumClassFor(patch.voice, patch.macros, patch.model))
            .copy(displayName = patch.name)
        val fuller = CausticPresets.all().first { it.name == "Fuller Body" }
        val scale = PENTATONIC.take(8).mapIndexed { index, semitone ->
            arranged(fuller.copy(name = "Caustic Key ${index + 1}", midi = 48 + semitone))
        }
        val positions = listOf("Fuller Body", "Preferred Alloy", "Hollow Mallet", "Rubbed Ring",
            "Travelling Catches", "Sparse Alloy", "Short Glint", "Low Vessel")
        return scale + positions.map { name -> arranged(CausticPresets.all().single { it.name == name }) }
    }

    /**
     * Eight Corner Bass keys in C minor pentatonic from C2, the six approved positions,
     * then two held variants. Every dry pad stores its exact root, velocity and model.
     * Finite notes route as TONAL; the last two pads contain settled LOOP material.
     */
    fun crease(): List<ArrangedPad?> {
        fun arranged(patch: CreasePatch) = pad(patch, Crease.drumClassFor(patch.voice, patch.macros))
            .copy(displayName = patch.name)
        val approved = CreasePresets.patches()
        val bass = approved.single { it.name == "Corner Bass" }
        val scale = PENTATONIC.take(8).mapIndexed { index, semitone ->
            arranged(bass.copy(name = "Corner Bass ${index + 1}", midi = bass.midi + semitone))
        }
        val held = listOf("Paper Keys", "Ink Ribbon").map { name ->
            val source = approved.single { it.name == name }
            arranged(source.copy(name = "$name LOOP", macros = source.macros + ("HOLD" to 1f)))
        }
        return scale + approved.map(::arranged) + held
    }

    /**
     * Sixteen dry finite flock performances. A01–A08 play First Gathering up
     * C minor pentatonic from C4; the upper eight pads retain the other factory
     * recipes, including every RIBBON and SPLIT. Notes share the base recipe's
     * seed so their differences come from pitch alone. Each pad records its
     * exact seed, model, note and velocity; HOLD extends a one-shot, never a loop.
     */
    fun flock(): List<ArrangedPad?> {
        fun landed(patch: FlockPatch) = pad(patch, Flock.drumClassFor(patch.voice, patch.macros))
            .copy(displayName = patch.name)
        val gathering = FlockPresets.forVoice(FlockVoice.GATHER).first()
        val notes = PENTATONIC.take(8).mapIndexed { index, semitone ->
            landed(gathering.copy(name = "Flock Gather ${index + 1}", midi = 60 + semitone))
        }
        return notes + FlockPresets.all().filterNot { it == gathering }.map(::landed)
    }

    /**
     * OVEN's dry finite-note audition kit. A01–A08 walk GRAND up C minor
     * pentatonic from C3; the remaining pads expose all four articulations.
     * Every pad retains its requested root, strike velocity and material model.
     * Long resonance tails remain TONAL; no pad pretends to be a held loop.
     */
    fun oven(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = OvenPatch(
            "Oven Grand $n", OvenVoice.GRAND, Oven.defaults(OvenVoice.GRAND),
            midi = Oven.ROOT_MIDI + semitone, velocity = .8f,
        ).let { pad(it, Oven.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = OvenPresets.all().first { it.name == name }
            .let { pad(it, Oven.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(8).mapIndexed { i, semitone -> note(i + 1, semitone) } + listOf(
            preset("Iron Parlour"), preset("Felt Ledger"), preset("Quiet Room"), preset("Bright Ledger"),
            preset("Hammered Iron"), preset("Cold Rivet"), preset("Afterglow"), preset("Hanging Frame"),
        )
    }

    /**
     * WICK's dry first-listen kit, kept outside FRESH TAPE while provisional.
     * PLUCK, WOOD, BUBBLE, BREATH, FLICKER and RATTLE each speak at C3 and C4.
     * The remaining four pads compare held BUBBLE, BREATH, FLICKER and RATTLE.
     * Each voice's native-root default is its exact library patch. This
     * sixteen-pad selection samples the twenty-five-recipe library with ten
     * exact library recipes and six transposed default variations;
     * the complete bank remains available through the audition and CLI.
     * Every pad retains its note, velocity and controls in its recipe.
     */
    fun wick(): List<ArrangedPad?> {
        val voices = listOf(WickVoice.PLUCK, WickVoice.WOOD, WickVoice.BUBBLE, WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE)
        val defaults = voices.map { WickPresets.forVoice(it).first() }
        val notes = defaults.flatMap { patch ->
            listOf(48, 60).map { midi ->
                if (midi == patch.midi) patch else patch.copy(
                    name = "${patch.voice.name.lowercase().replaceFirstChar { it.uppercase() }} C${midi / 12 - 1}",
                    midi = midi,
                )
            }
        }
        val recipeNames = listOf(
            "Held Bubbles", "Held Breath", "Held Flicker", "Held Rattle",
        )
        val recipes = recipeNames.map { name -> WickPresets.all().first { it.name == name } }
        return (notes + recipes).map { pad(it, Wick.drumClassFor(it.voice, it.macros)) }
    }

    /**
     * OVERLAP's dry sixteen-pad starter: the approved twelve musical recipes
     * retain their order, followed by octave-lower copies of its four plucks.
     * Every sample plays once; HOLD supplies finite excitation, not a loop.
     */
    fun overlap(): List<ArrangedPad?> {
        val bank = OverlapMusicalBank.all()
        require(bank.size == 12) { "OVERLAP's approved musical bank has twelve entries" }
        fun sound(entry: OverlapBankEntry, patch: OverlapPatch = entry.patch, transpose: Int = 0): ArrangedPad =
            pad(patch, DrumClass.TONAL).copy(
                displayName = patch.name, oneShot = true,
                source = mapOf(
                    "presetId" to (entry.id + if (transpose == 0) "" else "-low"),
                    "basePresetId" to entry.id,
                    "presetFamily" to entry.family.name,
                    "referenceMidi" to patch.midi.toString(),
                    "referenceNote" to Scales.nameOf(patch.midi),
                    "transposeSemitones" to transpose.toString(),
                    "model" to patch.model.toString(),
                ),
            )
        val lowNames = listOf("LOW THREAD", "LOW PIN", "LOW CUP", "LOW SEED")
        return bank.map { sound(it) } + bank.take(4).mapIndexed { index, entry ->
            sound(entry, entry.patch.copy(name = lowNames[index], midi = entry.patch.midi - 12), -12)
        }
    }

    /**
     * The dry COROLLA kit. A01–A08 walk TONGUE up the C minor pentatonic from C3; the top half
     * contrasts opening, contact, field and enclosure, ending with two held textures. Finite
     * gestures land as TONAL so IN KEY can retune them; HOLD alone selects LOOP and settled
     * material suitable for repeated playback. The engine's pitched identity takes precedence
     * over the generic classifier's length-based LOOP bucket.
     */
    fun corolla(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = CorollaPatch(
            "Petal $n", CorollaVoice.TONGUE,
            Corolla.defaults(CorollaVoice.TONGUE) + ("TUNE" to semitone / Corolla.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Corolla.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = CorollaPresets.all().first { it.name == name }
            .let { pad(it, Corolla.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("OPENING BELL"), preset("FOLDED CHAMBER"), preset("MAGNETIC CHOIR"), preset("QUIET CHATTER"),
            preset("FAST ORBIT"), preset("HOLLOW HUSK"), preset("PETAL CLOUD"), preset("CLOSED CIRCUIT"),
        )
    }

    /**
     * The THAW kit. A01–A08 walk C minor pentatonic from C3 on BRITTLE. A09–A14 explore the
     * warming runner, melt, channels, frozen bridges, heavy sheet and returning frost; A15–A16
     * are settled held material. Each dry pad carries its full recipe and uses THAW's pitched
     * duration-aware filing.
     * Held samples contain the settled loop alone, and the kit plays them once like other held
     * factory pads. No external effect supplies the material transition.
     */
    fun thaw(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = ThawPatch(
            "Ice $n", ThawVoice.BRITTLE,
            Thaw.defaults(ThawVoice.BRITTLE) + ("TUNE" to semitone / Thaw.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Thaw.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = ThawPresets.all().first { it.name == name }
            .let { pad(it, Thaw.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("COPPER RUNNER"), preset("SOFT MELT"), preset("CLEAR CHANNEL"), preset("FROZEN BRIDGE"),
            preset("SLOW SHEET"), preset("RETURNING FROST"), preset("COLD CHOIR"), preset("THERMAL CYCLE"),
        )
    }

    /**
     * A01–A08 walk C minor pentatonic on KNOCK. The upper eight pads explore every aperture
     * character and end with settled powered suction. All pads land dry with recipes that
     * regenerate the same coupled shell; the held pad contains its loop without the first catch.
     */
    fun undertow(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = UndertowPatch(
            "Inlet $n", UndertowVoice.KNOCK,
            Undertow.defaults(UndertowVoice.KNOCK) + ("TUNE" to semitone / Undertow.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Undertow.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = UndertowPresets.all().first { it.name == name }
            .let { pad(it, Undertow.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("SOFT INLET"), preset("FLUTTER CHAMBER"), preset("ALTERNATING SEAL"), preset("DEEP SPIRAL"),
            preset("HEAVY CATCH"), preset("RETURNING AIR"), preset("GENTLE BYPASS"), preset("HELD SUCTION"),
        )
    }

    /**
     * A dry vessel kit: A01–A08 walk C minor pentatonic from C3 on BLOOM, A09–A14
     * explore the threaded, closing, murmuring, strained and deep bodies, and A15–A16
     * contain settled powered loops. Every pad carries its complete regenerable recipe.
     */
    fun suture(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = SuturePatch(
            "Bronze $n", SutureVoice.BLOOM,
            Suture.defaults(SutureVoice.BLOOM) + ("TUNE" to semitone / Suture.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Suture.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = SuturePresets.all().first { it.name == name }
            .let { pad(it, Suture.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("WOODEN EYE"), preset("SLOW TAKE-UP"), preset("CLOSING SHELL"), preset("FINE SEAM"),
            preset("RESISTED STITCH"), preset("DEEP VESSEL"), preset("QUIET MURMUR"), preset("RETURNING GAP"),
        )
    }

    /** Eight wooden GLASS-motion scale notes, six material contrasts and two harmonic loops, all dry. */
    fun shoal(): List<ArrangedPad?> {
        fun arranged(patch: ShoalPatch) = pad(patch, Shoal.drumClassFor(patch.voice, patch.macros))
        val notes = PENTATONIC.take(8).mapIndexed { index, semitone ->
            ShoalPatch(
                "Wood ${index + 1}", ShoalVoice.GLASS, Shoal.defaults(ShoalVoice.GLASS),
                midi = Shoal.rootMidi(ShoalVoice.GLASS) + semitone,
            ).let(::arranged)
        }
        val recipes = ShoalPresets.all()
        return notes + recipes.filterNot { Shoal.isLoop(it.macros.getValue("HOLD")) }.map(::arranged) +
            recipes.filter { Shoal.isLoop(it.macros.getValue("HOLD")) }.map(::arranged)
    }

    /**
     * Relay's dry audition kit: eight C-minor-pentatonic keys, one seed for each voice,
     * and two held phrases. Every pad stores the source definitions, handoff roster,
     * accent clock and loop configuration needed to regenerate it. Finite phrases are
     * TONAL; only HOLD phrases use LOOP routing. The held pads export their repeating
     * region and play once in a drum program, like the other held factory kits.
     */
    fun relay(): List<ArrangedPad?> {
        fun arranged(patch: RelayPatch) = pad(patch, Relay.drumClassFor(patch.voice, patch.macros))
        val notes = PENTATONIC.take(8).mapIndexed { index, semitone ->
            RelayPatch(
                "Handoff ${index + 1}", RelayVoice.KEY,
                Relay.defaults(RelayVoice.KEY) + ("TUNE" to semitone / Relay.TUNE_SEMITONES.toFloat()),
            ).let(::arranged)
        }
        fun selected(name: String) = RelayPresets.all().single { it.name == name }.let(::arranged)
        // The first KEY seed duplicates Handoff 6 at C4. Use its authored contrast here,
        // and select by name so a growing preset bank never expands this sixteen-pad kit.
        val voices = listOf("AFTERIMAGE KEY", "POCKET RELAY", "MUTED PASSAGE",
            "ORGAN AFTERGLOW", "BELL EXIT", "SOFT EXCHANGE").map(::selected)
        val held = listOf("HELD HANDOFF", "FOUR CORNERS").map(::selected)
        return notes + voices + held
    }

    /** Eight C minor pentatonic roots and eight dry ensemble perspectives, with full recipes. */
    fun revel(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = RevelPatch(
            "Circle $n", RevelVoice.CIRCLE,
            Revel.defaults(RevelVoice.CIRCLE) + ("TUNE" to semitone / Revel.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Revel.drumClassFor(it.voice, it.macros)) }
        fun preset(name: String) = RevelPresets.all().first { it.name == name }
            .let { pad(it, Revel.drumClassFor(it.voice, it.macros)) }
        return listOf(
            note(1, PENTATONIC[0]), note(2, PENTATONIC[1]), note(3, PENTATONIC[2]), note(4, PENTATONIC[3]),
            note(5, PENTATONIC[4]), note(6, PENTATONIC[5]), note(7, PENTATONIC[6]), note(8, PENTATONIC[7]),
            preset("Skin Conversation"), preset("Close Pass"), preset("Two Directions"), preset("Three Listeners"),
            preset("Flower Path"), preset("Elastic Answer"), preset("Rolling Floor"), preset("Held Revel"),
        )
    }

    /**
     * Sixteen dry tonal pads: seven KEY notes in C minor pentatonic, followed by
     * the nine original recipes. The expanded preset browser stays independent
     * of this sixteen-pad layout. HOLD contains the settled sustain alone.
     */
    fun contour(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = ContourPatch(
            "Contour $n", ContourVoice.KEY,
            ContourEngine.defaults(ContourVoice.KEY) +
                ("TUNE" to (.5f + semitone / (2f * ContourEngine.TUNE_SEMITONES))),
        ).let { pad(it, ContourEngine.drumClassFor(it.voice, it.macros)) }
        return PENTATONIC.take(7).mapIndexed { i, semitone -> note(i + 1, semitone) } +
            ContourPresets.original().map { pad(it, ContourEngine.drumClassFor(it.voice, it.macros)) }
    }

    /** Four notes from the approved THREAD recipe followed by all twelve dry factory recipes. */
    fun filament(): List<ArrangedPad?> {
        fun arranged(patch: FilamentPatch) = pad(patch, Filament.drumClassFor(patch.voice, patch.macros)).copy(
            displayName = patch.name,
            source = mapOf("rootMidi" to patch.midi.toString(), "rootNote" to Scales.nameOf(patch.midi), "rootOrigin" to "authored"),
        )
        val thread = FilamentPresets.forVoice(FilamentVoice.THREAD).first()
        val notes = PENTATONIC.take(4).mapIndexed { index, semitone ->
            thread.copy(
                name = "Filament ${index + 1}",
                midi = thread.midi + semitone,
            ).let(::arranged)
        }
        return notes + FilamentPresets.all().map(::arranged)
    }

    /**
     * Dry original recordings in C minor pentatonic. A01–A08 play the clear string family;
     * A09–A14 expose all six first-review voices, and A15–A16 repeat forward/return cycles
     * using a finite frozen imprint snapshot. Every pad carries the complete machine recipe.
     */
    fun spool(): List<ArrangedPad?> {
        fun note(n: Int, semitone: Int) = SpoolPatch(
            "Frame $n", SpoolVoice.STRING,
            Spool.defaults(SpoolVoice.STRING) + ("TUNE" to semitone / Spool.TUNE_SEMITONES.toFloat()),
        ).let { pad(it, Spool.drumClassFor(it.voice, it.macros)) }
        fun preset(voice: SpoolVoice, hold: Boolean = false) = SpoolPresets.forVoice(voice).first()
            .let { if (hold) it.copy(name = "${it.name} LOOP", macros = it.macros + ("HOLD" to 1f)) else it }
            .let { pad(it, Spool.drumClassFor(it.voice, it.macros)) }
        return (0..7).map { i -> note(i + 1, PENTATONIC[i]) } +
            SpoolVoice.entries.map { preset(it) } +
            listOf(preset(SpoolVoice.CHOIR, hold = true), preset(SpoolVoice.PALIMPSEST, hold = true))
    }
}
