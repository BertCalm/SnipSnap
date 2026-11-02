package com.snipsnap.synth

/**
 * Thirty-six original dry recipes followed by six settings selected in listening.
 * The original bank retains its order and sound; the added KEY and low CHOIR recipes
 * use their saved render definition rather than changing existing voice defaults.
 */
object MurmurPresets {
    private fun p(voice: MurmurVoice, name: String, vararg macros: Pair<String, Float>) =
        MurmurPatch(name, voice, Murmur.defaults(voice) + macros.toMap(),
            config = Murmur.configurationFor(voice, model = 2))

    private val presets = listOf(
        p(MurmurVoice.BASS, "CLOSED CIRCUIT").copy(midi = 48),
        p(MurmurVoice.BASS, "ROUND HUM", "MOUTH" to .18f, "EDGE" to .19f,
            "BREATH" to .16f, "MOTION" to .44f, "BODY" to .88f).copy(midi = 48),
        p(MurmurVoice.KEY, "TINE WHISPER"),
        p(MurmurVoice.KEY, "DUST VOWEL", "MOUTH" to .67f, "EDGE" to .28f,
            "BREATH" to .34f, "MOTION" to .48f, "BODY" to .39f),
        p(MurmurVoice.PLUCK, "SHORT ANSWER"),
        p(MurmurVoice.PLUCK, "POCKET VOICE", "MOUTH" to .62f, "EDGE" to .32f,
            "BREATH" to .25f, "MOTION" to .61f, "BODY" to .61f),
        p(MurmurVoice.LEAD, "OPEN THROAT"),
        p(MurmurVoice.LEAD, "NARROW CALL", "MOUTH" to .85f, "EDGE" to .63f,
            "BREATH" to .28f, "MOTION" to .26f, "BODY" to .35f),
        p(MurmurVoice.CHOIR, "SMALL CHOIR"),
        p(MurmurVoice.CHOIR, "HELD HUM", "MOUTH" to .29f, "EDGE" to .25f,
            "BREATH" to .37f, "MOTION" to .43f, "BODY" to .72f, "HOLD" to 1f),
        p(MurmurVoice.METAL, "COPPER HUM"),
        p(MurmurVoice.METAL, "GLASS MOUTH", "MOUTH" to .73f, "EDGE" to .92f,
            "BREATH" to .26f, "MOTION" to .63f, "BODY" to .24f),

        // A dry, fixed closed vowel: rounded weight without the original basses' talking motion.
        p(MurmurVoice.BASS, "STILL CURRENT", "MOUTH" to .04f, "EDGE" to .16f,
            "BREATH" to .02f, "MOTION" to 0f, "BODY" to .94f).copy(midi = 48, velocity = .88f),
        // A broad, firmly excited vowel answer; BODY keeps the moving upper bands grounded.
        p(MurmurVoice.BASS, "TALKBACK", "MOUTH" to .52f, "EDGE" to .62f,
            "BREATH" to .06f, "MOTION" to .88f, "BODY" to .66f).copy(midi = 48, velocity = .92f),
        // Open, supported low mids with little travel and a gentler excitation.
        p(MurmurVoice.BASS, "CHEST VOICE", "MOUTH" to .65f, "EDGE" to .24f,
            "BREATH" to .04f, "MOTION" to .08f, "BODY" to 1f).copy(midi = 48, velocity = .82f),
        // The contrasting thin bass: a nearly fixed narrow vowel and exposed upper carrier.
        p(MurmurVoice.BASS, "WIRE THROAT", "MOUTH" to .91f, "EDGE" to .78f,
            "BREATH" to .05f, "MOTION" to .06f, "BODY" to .20f).copy(midi = 48, velocity = .94f),
        // A held, rounded phrase; the saved slower reverse cycle makes space between vowel turns.
        p(MurmurVoice.BASS, "UNDERCURRENT", "MOUTH" to .32f, "EDGE" to .30f,
            "BREATH" to .04f, "MOTION" to .58f, "BODY" to .86f, "HOLD" to 1f).copy(
            midi = 48, velocity = .85f,
            config = Murmur.configurationFor(MurmurVoice.BASS, model = 2).copy(
                trajectory = MurmurTrajectory.CYCLE, direction = -1, referenceSeconds = 1.75)),
        // A clipped closed syllable: the explicit short envelope and contour replace a long bass tail.
        p(MurmurVoice.BASS, "DRY SYLLABLE", "MOUTH" to .18f, "EDGE" to .55f,
            "BREATH" to 0f, "MOTION" to .30f, "BODY" to .55f).copy(
            midi = 48, velocity = .90f,
            config = Murmur.configurationFor(MurmurVoice.BASS, model = 2).copy(
                attack = .004, decay = .11, sustain = .12, release = .16, referenceSeconds = .30)),

        // A bright, static held tine; no vowel travel competes with its sustained third carrier.
        p(MurmurVoice.KEY, "STILL TINE", "MOUTH" to .16f, "EDGE" to .76f,
            "BREATH" to .02f, "MOTION" to 0f, "BODY" to .24f, "HOLD" to 1f).copy(velocity = .92f),
        // A soft, supported answer with a deliberately shorter felt-like strike and release.
        p(MurmurVoice.KEY, "FELT REPLY", "MOUTH" to .48f, "EDGE" to .16f,
            "BREATH" to .08f, "MOTION" to .72f, "BODY" to .91f).copy(
            velocity = .72f,
            config = Murmur.configurationFor(MurmurVoice.KEY, model = 2).copy(
                attack = .012, decay = .40, sustain = .14, release = .45)),

        // Hard and close: a dry upper-carrier pluck with an explicitly abbreviated decay.
        p(MurmurVoice.PLUCK, "BONE CLICK", "MOUTH" to .12f, "EDGE" to .83f,
            "BREATH" to .02f, "MOTION" to .08f, "BODY" to .20f).copy(
            velocity = .95f,
            config = Murmur.configurationFor(MurmurVoice.PLUCK, model = 2).copy(
                attack = .0015, decay = .12, sustain = .04, release = .16, referenceSeconds = .30)),
        // The open, supported fourth-carrier sound sustained as a quiet, almost stationary loop.
        p(MurmurVoice.PLUCK, "OPEN STRING", "MOUTH" to .84f, "EDGE" to .22f,
            "BREATH" to .04f, "MOTION" to .02f, "BODY" to .88f, "HOLD" to 1f).copy(velocity = .86f),

        // A dry nasal line with a fixed narrow vowel and strong sustained reed excitation.
        p(MurmurVoice.LEAD, "REED LINE", "MOUTH" to .88f, "EDGE" to .73f,
            "BREATH" to .06f, "MOTION" to 0f, "BODY" to .30f).copy(velocity = .90f),
        // Intimate, rounded and soft; restrained motion leaves the lower vowel clearly audible.
        p(MurmurVoice.LEAD, "SOFT PALATE", "MOUTH" to .18f, "EDGE" to .12f,
            "BREATH" to .09f, "MOTION" to .08f, "BODY" to .90f).copy(velocity = .72f),
        // Broad, quick reverse vowel cycles articulate a flowing phrase without extra effects.
        p(MurmurVoice.LEAD, "VOWEL RUN", "MOUTH" to .52f, "EDGE" to .52f,
            "BREATH" to .08f, "MOTION" to .94f, "BODY" to .58f).copy(
            velocity = .87f,
            config = Murmur.configurationFor(MurmurVoice.LEAD, model = 2).copy(direction = -1, referenceSeconds = .78)),
        // A held, supported reed with an explicitly long vowel cycle instead of rapid articulation.
        p(MurmurVoice.LEAD, "LONG WORD", "MOUTH" to .62f, "EDGE" to .37f,
            "BREATH" to .14f, "MOTION" to .68f, "BODY" to .72f, "HOLD" to 1f).copy(
            velocity = .80f,
            config = Murmur.configurationFor(MurmurVoice.LEAD, model = 2).copy(referenceSeconds = 2.60)),
        // A closed, thin signal exposes the reed's odd harmonics rather than another narrow call.
        p(MurmurVoice.LEAD, "HOLLOW SIGNAL", "MOUTH" to .04f, "EDGE" to .62f,
            "BREATH" to .03f, "MOTION" to .02f, "BODY" to .20f).copy(velocity = .92f),
        // The bank's airy lead: an open moving vowel with audible breath and moderate support.
        p(MurmurVoice.LEAD, "BREATH MARK", "MOUTH" to .68f, "EDGE" to .28f,
            "BREATH" to .40f, "MOTION" to .45f, "BODY" to .46f).copy(velocity = .78f),

        // An intimate, almost dry closed hum keeps the near-unison carriers in the foreground.
        p(MurmurVoice.CHOIR, "CLOSE HARMONY", "MOUTH" to .09f, "EDGE" to .10f,
            "BREATH" to .08f, "MOTION" to 0f, "BODY" to .92f).copy(velocity = .76f),
        // A restrained open choir: modest air and travel, with full low-mid support.
        p(MurmurVoice.CHOIR, "OPEN ENSEMBLE", "MOUTH" to .69f, "EDGE" to .28f,
            "BREATH" to .18f, "MOTION" to .18f, "BODY" to .74f).copy(velocity = .84f),
        // A gently airy held choir; a slow forward cycle makes the vowel journey the main movement.
        p(MurmurVoice.CHOIR, "VOWEL HALO", "MOUTH" to .52f, "EDGE" to .16f,
            "BREATH" to .24f, "MOTION" to .78f, "BODY" to .45f, "HOLD" to 1f).copy(
            velocity = .76f,
            config = Murmur.configurationFor(MurmurVoice.CHOIR, model = 2).copy(direction = 1, referenceSeconds = 2.70)),
        // A clear, narrow unison with little movement; thin BODY contrasts with the supported choirs.
        p(MurmurVoice.CHOIR, "THIN UNISON", "MOUTH" to .91f, "EDGE" to .38f,
            "BREATH" to .12f, "MOTION" to .02f, "BODY" to .16f).copy(velocity = .88f),

        // A rounded, dry bell body with a fixed closed vowel and an audible sustained ring.
        p(MurmurVoice.METAL, "BELL CORE", "MOUTH" to .13f, "EDGE" to .48f,
            "BREATH" to .02f, "MOTION" to 0f, "BODY" to .82f).copy(velocity = .92f),
        // A quick forward metal syllable; the saved contour reverses the base voice's falling gesture.
        p(MurmurVoice.METAL, "TIN LANGUAGE", "MOUTH" to .88f, "EDGE" to .66f,
            "BREATH" to .08f, "MOTION" to .88f, "BODY" to .48f).copy(
            velocity = .85f,
            config = Murmur.configurationFor(MurmurVoice.METAL, model = 2).copy(direction = 1, referenceSeconds = .42)),
        // A bright, exposed ring: nearly static, narrow and entirely dry.
        p(MurmurVoice.METAL, "NEEDLE RING", "MOUTH" to .95f, "EDGE" to 1f,
            "BREATH" to 0f, "MOTION" to .06f, "BODY" to .08f).copy(velocity = .93f),
        // A supported held alloy with a slower cyclic mouth path instead of a struck one-way gesture.
        p(MurmurVoice.METAL, "SLOW ALLOY", "MOUTH" to .43f, "EDGE" to .58f,
            "BREATH" to .05f, "MOTION" to .46f, "BODY" to .64f, "HOLD" to 1f).copy(
            velocity = .82f,
            config = Murmur.configurationFor(MurmurVoice.METAL, model = 2).copy(
                trajectory = MurmurTrajectory.CYCLE, referenceSeconds = 2.40)),
    )

    private val selectedPresets = listOf(0f, .5f, .75f, 1f).map { motion ->
        MurmurPatch("KEY MOTION $motion", MurmurVoice.KEY,
            mapOf("MOUTH" to .40f, "EDGE" to .40f, "BREATH" to .12f,
                "MOTION" to motion, "BODY" to .55f, "HOLD" to 0f),
            midi = 60, config = Murmur.configurationFor(MurmurVoice.KEY, model = 3))
    } + listOf(36, 48).map { midi ->
        MurmurPatch("CHOIR C${midi / 12 - 1} BREATH 0.1", MurmurVoice.CHOIR,
            mapOf("MOUTH" to .45f, "EDGE" to .20f, "BREATH" to .10f,
                "MOTION" to .30f, "BODY" to .65f, "HOLD" to 1f),
            midi = midi, config = Murmur.configurationFor(MurmurVoice.CHOIR, model = 3))
    }

    /** The published 36-recipe bank, retained for historical auditions and saved kits. */
    fun original(): List<MurmurPatch> = presets

    /** Four heard KEY settings and the lower-air C2/C3 CHOIR refinement. */
    fun selected(): List<MurmurPatch> = selectedPresets

    fun forVoice(voice: MurmurVoice): List<MurmurPatch> = all().filter { it.voice == voice }

    fun all(): List<MurmurPatch> = presets + selectedPresets
}
