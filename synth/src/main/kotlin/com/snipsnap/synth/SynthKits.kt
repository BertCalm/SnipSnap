package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * The S3.5 factory kit: a *melodic* kit, where the 4×4 grid is a scale, not
 * a drum layout. This is the keys-on-pads promise from docs/SYNTH_ROADMAP.md
 * arriving early as one-shots — no keygroup export needed, because every pad
 * is just a WAV like any other. Every pad carries its [PadRecipe].
 */
object SynthKits {

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
     * provisional (nothing in BORE has been heard yet); this kit is what the audition
     * page's first section plays.
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
     * The BALLAST acceptance kit: ROOT up the minor pentatonic from C2 on the first four pads (A01-A04), every knob but
     * TUNE at its default, so the row is the bass before anyone touches it; then two presets each of WIRE, DEEP, GLINT,
     * BLOOM and SWARM (A05-A14) and a DEEP and a BLOOM preset with a long gate (A15-A16). Every pad is dry (BALLAST has no
     * landing chain) and filed by [Ballast.drumClassFor]: the gate plus the structure's own ring runs past the
     * classifier's 1.5 s line on most of them, which files them LOOP by length, and a drum program plays every pad once
     * through either way. The presets are provisional until the audition gate; this kit is what the page's kit section plays.
     */
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
}
