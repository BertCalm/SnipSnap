package com.snipsnap.synth

/** Forty dry instruments. The original thirty-six remain first, with their saved recipes intact. */
object PrismPresets {
    private fun p(voice: PrismVoice, name: String, vararg macros: Pair<String, Float>) =
        PrismPatch(name, voice, Prism.defaults(voice) + macros.toMap())

    private val roles = linkedMapOf<String, String>()

    /** Returns a plain patch so promoted `p(...)` recipes can also be pasted directly into the table. */
    private fun e(role: String, patch: PrismPatch): PrismPatch {
        roles[patch.name] = role
        return patch
    }

    /** A complete bank is deliberate: unspecified high partials must not return through template defaults. */
    private fun partials(vararg levels: Float): List<Float> =
        List(Prism.PARTIALS) { levels.getOrElse(it) { 0f } }

    private fun model(
        spectrum: PrismSpectrum,
        contour: PrismContour,
        vararg levels: Float,
        attack: Float,
        decay: Float,
        groups: List<PrismGroupProfile> = emptyList(),
        loop: Float = 2f,
    ) = PrismTemplate(spectrum, contour, amplitudeOverrides = partials(*levels),
        groupProfiles = groups, attackSeconds = attack, decaySeconds = decay, loopSeconds = loop)

    /** All glass variants start from the approved softer factory model; none restores the old bright bell map. */
    private fun glass(
        attack: Float? = null,
        decay: Float? = null,
        groups: List<PrismGroupProfile> = emptyList(),
        loop: Float = 2f,
        scale: (Int) -> Float = { 1f },
    ): PrismTemplate {
        val base = Prism.templateFor(PrismVoice.GLASS)
        return base.copy(amplitudeOverrides = base.amplitudeOverrides.mapIndexed { i, a -> a * scale(i + 1) },
            groupProfiles = groups, attackSeconds = attack, decaySeconds = decay, loopSeconds = loop)
    }

    // Profiles are ordered anchor, low middle, upper middle, sheen and transient.
    private val shortDamping = listOf(
        PrismGroupProfile(.9f, 1f, 0f, 1, .05f),
        PrismGroupProfile(.8f, .72f, 0f, 1, .35f),
        PrismGroupProfile(.9f, .40f, .006f, 2, .55f),
        PrismGroupProfile(.6f, .24f, 0f, 3, .35f),
        PrismGroupProfile(.5f, .15f, 0f, 2, .20f),
    )
    private val reedDevelopment = listOf(
        PrismGroupProfile(1f, 1.15f, 0f, 1, .08f),
        PrismGroupProfile(.85f, .95f, .005f, 2, .55f),
        PrismGroupProfile(1.8f, .70f, .03f, 3, .65f),
        PrismGroupProfile(2.5f, .50f, .06f, 1, .40f),
        PrismGroupProfile(.8f, .35f, 0f, 2, .20f),
    )
    private val tineDevelopment = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .06f),
        PrismGroupProfile(.8f, 1.15f, .012f, 2, .60f),
        PrismGroupProfile(1.8f, .62f, .045f, 3, .70f),
        PrismGroupProfile(.6f, .35f, 0f, 1, .35f),
        PrismGroupProfile(.5f, .20f, 0f, 2, .20f),
    )
    private val steadyRanks = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .02f),
        PrismGroupProfile(1.1f, 1f, .01f, 1, .12f),
        PrismGroupProfile(1.4f, .90f, .02f, 2, .18f),
        PrismGroupProfile(1.8f, .75f, .03f, 1, .12f),
        PrismGroupProfile(.8f, .50f, 0f, 2, .08f),
    )
    private val turningRanks = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .02f),
        PrismGroupProfile(1.2f, 1f, .02f, 2, .45f),
        PrismGroupProfile(1.8f, .95f, .06f, 3, .85f),
        PrismGroupProfile(2.4f, .80f, .10f, 4, 1f),
        PrismGroupProfile(.9f, .55f, .015f, 2, .60f),
    )
    private val risingThreads = listOf(
        PrismGroupProfile(.65f, 1f, 0f, 1, .04f),
        PrismGroupProfile(1.4f, .90f, .05f, 2, .55f),
        PrismGroupProfile(2.8f, 1.20f, .20f, 3, .85f),
        PrismGroupProfile(4f, 1.10f, .38f, 1, .75f),
        PrismGroupProfile(3f, .60f, .25f, 2, .40f),
    )
    private val wovenMotion = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .03f),
        PrismGroupProfile(1.7f, 1f, .04f, 2, .75f),
        PrismGroupProfile(2.5f, 1.15f, .12f, 3, .90f),
        PrismGroupProfile(3f, .85f, .20f, 1, .45f),
        PrismGroupProfile(1.5f, .55f, .05f, 2, .25f),
    )
    private val latticeMotion = listOf(
        PrismGroupProfile(1f, 1.15f, 0f, 1, .02f),
        PrismGroupProfile(1.5f, 1f, .04f, 2, .55f),
        PrismGroupProfile(2.2f, 1.10f, .10f, 3, .90f),
        PrismGroupProfile(3.2f, .85f, .18f, 5, .50f),
        PrismGroupProfile(1.7f, .60f, .07f, 4, .30f),
    )
    private val glassMotion = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .02f),
        PrismGroupProfile(1.5f, 1.05f, .025f, 1, .35f),
        PrismGroupProfile(2.5f, .90f, .08f, 2, .65f),
        PrismGroupProfile(3f, .70f, .15f, 3, .45f),
        PrismGroupProfile(1.2f, .45f, .025f, 2, .20f),
    )
    private val crystalMotion = listOf(
        PrismGroupProfile(1f, 1.10f, 0f, 1, .02f),
        PrismGroupProfile(1.6f, 1.15f, .035f, 1, .75f),
        PrismGroupProfile(2.7f, .85f, .10f, 2, .65f),
        PrismGroupProfile(3.5f, .70f, .18f, 4, .35f),
        PrismGroupProfile(1.4f, .45f, .03f, 3, .15f),
    )

    private val presets: List<PrismPatch> = listOf(
        // Compatibility: keep these eight recipes and their relative order unchanged.
        e("A rounded, gentle key for soft chords and unhurried melodies.",
            p(PrismVoice.KEY, "Soft Facets", "GRAIN" to .22f, "DRIFT" to .28f, "STRIKE" to .25f, "BODY" to .68f)),
        e("A dense wooden key with a warm middle and a firmer harmonic edge.",
            p(PrismVoice.KEY, "Wooden Spectrum", "GRAIN" to .36f, "DRIFT" to .42f, "SHAPE" to .18f, "BODY" to .85f)),
        e("A clear, articulated tine pluck whose bright bands leave a pitched body.",
            p(PrismVoice.PLUCK, "Partial Tines", "GRAIN" to .48f, "DRIFT" to .35f, "SHAPE" to .62f, "STRIKE" to .75f)),
        e("A hollow organ registration rendered as a finite note with a natural release.",
            p(PrismVoice.ORGAN, "Hollow Register", "GRAIN" to .28f, "DRIFT" to .25f, "SHAPE" to .42f, "BODY" to .70f)),
        e("A rounded bass anchor with a clear upper glint for melodic lines.",
            p(PrismVoice.BASS, "Root Glass", "GRAIN" to .32f, "DRIFT" to .18f, "SHAPE" to .58f, "STRIKE" to .58f, "BODY" to .76f)),
        e("A finite pad whose low, middle and upper harmonics unfold at different speeds.",
            p(PrismVoice.VEIL, "Slow Separation", "GRAIN" to .58f, "DRIFT" to .80f, "SHAPE" to .65f, "BODY" to .55f)),
        e("A warm held pad with gently recurring harmonic movement.",
            p(PrismVoice.VEIL, "Amber Veil", "GRAIN" to .35f, "DRIFT" to .65f, "SHAPE" to .24f, "STRIKE" to .20f, "BODY" to .72f, "HOLD" to 1f)),
        e("A small, coherent glass strike with a quiet upper shimmer.",
            p(PrismVoice.GLASS, "Small Prism", "GRAIN" to .70f, "DRIFT" to .48f, "SHAPE" to .72f, "STRIKE" to .62f, "BODY" to .30f)),

        // KEY: muted felt, an odd-harmonic reed, and a ceramic harmonic ring.
        e("A muted key with a rounded onset and a short, low harmonic body.",
            p(PrismVoice.KEY, "Felt Harmonics", "GRAIN" to .08f, "DRIFT" to .22f, "SHAPE" to .08f, "STRIKE" to .12f, "BODY" to .86f)
                .copy(template = model(PrismSpectrum.ROUNDED, PrismContour.STRUCK,
                    1f, .42f, .16f, .08f, .04f, .02f, .012f, .006f,
                    attack = .045f, decay = 1f, groups = shortDamping))),
        e("A hollow reed key with strong odd harmonics and a longer middle-band tail.",
            p(PrismVoice.KEY, "Reed Cabinet", "GRAIN" to .42f, "DRIFT" to .58f, "SHAPE" to .50f, "STRIKE" to .34f, "BODY" to .66f)
                .copy(template = model(PrismSpectrum.ROUNDED, PrismContour.STRUCK,
                    1f, .09f, .68f, .06f, .36f, .04f, .20f, .025f, .12f, .015f, .065f, .008f,
                    attack = .022f, decay = 2.35f, groups = reedDevelopment))),
        e("A bright ceramic key: harmonic tines ring above a definite, playable onset.",
            p(PrismVoice.KEY, "Porcelain Key", "GRAIN" to .46f, "DRIFT" to .50f, "SHAPE" to .65f, "STRIKE" to .55f, "BODY" to .42f)
                .copy(template = model(PrismSpectrum.PLUCKED, PrismContour.RINGING,
                    1f, .28f, .58f, .42f, .14f, .20f, .06f, .12f, .03f, .04f,
                    attack = .011f, decay = 1.85f, groups = tineDevelopment))),

        // PLUCK: four different resonant bodies and loss profiles, rather than four brightness settings.
        e("A compact, damped wire pluck for short repeated patterns.",
            p(PrismVoice.PLUCK, "Mute Wire", "GRAIN" to .25f, "DRIFT" to .42f, "SHAPE" to .18f, "STRIKE" to .82f, "BODY" to .72f)
                .copy(template = model(PrismSpectrum.PLUCKED, PrismContour.PLUCKED,
                    1f, .60f, .28f, .08f, .06f, .02f,
                    attack = .004f, decay = .62f, groups = shortDamping))),
        e("A warm thumb pluck with a woody odd-harmonic middle and little upper spray.",
            p(PrismVoice.PLUCK, "Cedar Pin", "GRAIN" to .33f, "DRIFT" to .30f, "SHAPE" to .08f, "STRIKE" to .46f, "BODY" to .83f)
                .copy(template = model(PrismSpectrum.ROUNDED, PrismContour.PLUCKED,
                    1f, .35f, .52f, .12f, .27f, .05f, .11f, .02f, .04f,
                    attack = .014f, decay = 1.15f, groups = reedDevelopment))),
        e("An open, odd-harmonic plectrum sound with a long anchor beneath the pick.",
            p(PrismVoice.PLUCK, "Hollow Plectrum", "GRAIN" to .52f, "DRIFT" to .52f, "SHAPE" to .42f, "STRIKE" to .68f, "BODY" to .56f)
                .copy(template = model(PrismSpectrum.PLUCKED, PrismContour.PLUCKED,
                    1f, .04f, .70f, .03f, .34f, .02f, .22f, .01f, .14f, .01f, .09f, 0f, .06f, 0f, .03f,
                    attack = .006f, decay = 1.80f, groups = reedDevelopment))),
        e("A crisp bead-like pluck with octave-rich upper harmonics and a clear short ring.",
            p(PrismVoice.PLUCK, "Bright Bead", "GRAIN" to .72f, "DRIFT" to .22f, "SHAPE" to .84f, "STRIKE" to .67f, "BODY" to .44f)
                .copy(template = model(PrismSpectrum.PLUCKED, PrismContour.RINGING,
                    1f, .45f, .38f, .55f, .12f, .28f, .08f, .18f, .02f, .08f, 0f, .04f,
                    attack = .004f, decay = 1.45f, groups = tineDevelopment))),

        // ORGAN: four held registrations and two finite keys, including the original Hollow Register.
        e("A held foundation registration with a steady root and restrained upper ranks.",
            p(PrismVoice.ORGAN, "Chapel Core", "GRAIN" to .10f, "DRIFT" to .10f, "SHAPE" to .14f, "STRIKE" to .20f, "BODY" to .90f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.DRAWBAR, PrismContour.HELD,
                    1f, .65f, .35f, .20f, .08f, .06f, .04f, .03f,
                    attack = .045f, decay = 3f, groups = steadyRanks, loop = 2.3f))),
        e("A held, open registration with dense harmonic ranks for clear sustained chords.",
            p(PrismVoice.ORGAN, "Open Ranks", "GRAIN" to .12f, "DRIFT" to .20f, "SHAPE" to .88f, "STRIKE" to .42f, "BODY" to .72f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.DRAWBAR, PrismContour.HELD,
                    1f, .80f, .65f, .58f, .43f, .30f, .22f, .18f, .14f, .11f, .09f, .07f,
                    attack = .020f, decay = 3.2f, groups = steadyRanks, loop = 1.7f))),
        e("A held reed registration built around odd harmonics, with almost no even ranks.",
            p(PrismVoice.ORGAN, "Odd Register", "GRAIN" to .34f, "DRIFT" to .22f, "SHAPE" to .50f, "STRIKE" to .32f, "BODY" to .60f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.DRAWBAR, PrismContour.HELD,
                    1f, .04f, .65f, .02f, .34f, .015f, .18f, .01f, .13f, .008f, .07f, .005f, .04f,
                    attack = .028f, decay = 3.4f, groups = steadyRanks, loop = 2.1f))),
        e("A soft finite organ key whose rounded entrance settles into a long, dark body.",
            p(PrismVoice.ORGAN, "Soft Diapason", "GRAIN" to .20f, "DRIFT" to .15f, "SHAPE" to .10f, "STRIKE" to .12f, "BODY" to .83f, "HOLD" to .35f)
                .copy(template = model(PrismSpectrum.DRAWBAR, PrismContour.HELD,
                    1f, .45f, .28f, .16f, .06f, .04f, .025f, .015f,
                    attack = .09f, decay = 4.1f, groups = steadyRanks))),
        e("A held organ whose upper registrations turn at different speeds above a steady anchor.",
            p(PrismVoice.ORGAN, "Turning Ranks", "GRAIN" to .50f, "DRIFT" to .85f, "SHAPE" to .70f, "STRIKE" to .40f, "BODY" to .60f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.DRAWBAR, PrismContour.HELD,
                    1f, .58f, .48f, .36f, .30f, .25f, .22f, .18f, .15f, .12f, .10f, .08f, .06f, .04f, .03f, .02f,
                    attack = .035f, decay = 3.6f, groups = turningRanks, loop = 3.2f))),

        // BASS: pure foundation, rounded sustain, reed, thumb articulation and a bright step.
        e("A nearly pure low anchor with a gentle onset for clean sub-bass lines.",
            p(PrismVoice.BASS, "Cellar Sine", "GRAIN" to .10f, "DRIFT" to .10f, "SHAPE" to .08f, "STRIKE" to .12f, "BODY" to .95f)
                .copy(template = model(PrismSpectrum.ROOTED, PrismContour.LOW,
                    1f, .025f, .008f,
                    attack = .020f, decay = 2.8f, groups = steadyRanks))),
        e("A rounded, sustained bass with enough low harmonics to read on smaller speakers.",
            p(PrismVoice.BASS, "Round Relay", "GRAIN" to .15f, "DRIFT" to .25f, "SHAPE" to .18f, "STRIKE" to .45f, "BODY" to .90f, "HOLD" to .22f)
                .copy(template = model(PrismSpectrum.ROOTED, PrismContour.LOW,
                    1f, .72f, .38f, .23f, .13f, .08f, .05f, .03f,
                    attack = .018f, decay = 3.0f, groups = steadyRanks))),
        e("A hollow reed bass with a nasal middle and a stable fundamental beneath it.",
            p(PrismVoice.BASS, "Narrow Reed", "GRAIN" to .55f, "DRIFT" to .35f, "SHAPE" to .65f, "STRIKE" to .20f, "BODY" to .62f)
                .copy(template = model(PrismSpectrum.ROOTED, PrismContour.LOW,
                    1f, .06f, .62f, .025f, .32f, .015f, .16f, .01f, .08f,
                    attack = .025f, decay = 2.3f, groups = reedDevelopment))),
        e("A definite thumbed bass note whose upper contact damps before its rounded root.",
            p(PrismVoice.BASS, "Thumb Anchor", "GRAIN" to .25f, "DRIFT" to .45f, "SHAPE" to .22f, "STRIKE" to .78f, "BODY" to .85f)
                .copy(template = model(PrismSpectrum.ROOTED, PrismContour.LOW,
                    1f, .60f, .28f, .10f, .05f, .025f,
                    attack = .004f, decay = 1.15f, groups = shortDamping))),
        e("An articulated bass with a bright harmonic step for busier melodic patterns.",
            p(PrismVoice.BASS, "Copper Step", "GRAIN" to .65f, "DRIFT" to .45f, "SHAPE" to .82f, "STRIKE" to .72f, "BODY" to .58f)
                .copy(template = model(PrismSpectrum.ROOTED, PrismContour.PLUCKED,
                    1f, .90f, .60f, .42f, .25f, .28f, .12f, .14f, .08f, .06f, .04f, .025f,
                    attack = .011f, decay = 1.8f, groups = tineDevelopment))),

        // VEIL: four finite textures and four held ones; all movement remains in harmonic groups.
        e("A broad, gentle finite bloom with a low, close-knit harmonic fabric.",
            p(PrismVoice.VEIL, "Linen Bloom", "GRAIN" to .15f, "DRIFT" to .55f, "SHAPE" to .12f, "STRIKE" to .15f, "BODY" to .78f, "HOLD" to .15f)
                .copy(template = model(PrismSpectrum.ROUNDED, PrismContour.BLOOM,
                    1f, .58f, .40f, .29f, .20f, .15f, .11f, .08f, .06f, .045f, .03f, .02f,
                    attack = .22f, decay = 3.5f, groups = wovenMotion))),
        e("A light finite halo with thin odd harmonics arriving above a soft low anchor.",
            p(PrismVoice.VEIL, "Paper Halo", "GRAIN" to .65f, "DRIFT" to .70f, "SHAPE" to .88f, "STRIKE" to .18f, "BODY" to .28f)
                .copy(template = model(PrismSpectrum.VEILED, PrismContour.BLOOM,
                    1f, .10f, .38f, .08f, .28f, .06f, .23f, .05f, .18f, .04f, .10f, .02f, .07f, .015f, .04f,
                    attack = .12f, decay = 3f, groups = risingThreads))),
        e("A held open texture whose third and sixth harmonics weave around the root.",
            p(PrismVoice.VEIL, "Woven Fifths", "GRAIN" to .68f, "DRIFT" to .62f, "SHAPE" to .48f, "STRIKE" to .24f, "BODY" to .50f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.VEILED, PrismContour.BLOOM,
                    1f, .08f, .72f, .04f, .04f, .32f, .03f, .02f, .18f, .01f, .01f, .08f,
                    attack = .16f, decay = 3.5f, groups = wovenMotion, loop = 2.8f))),
        e("A held, sparse set of soft harmonic bands with restrained recurring motion.",
            p(PrismVoice.VEIL, "Quiet Bands", "GRAIN" to .86f, "DRIFT" to .35f, "SHAPE" to .30f, "STRIKE" to .14f, "BODY" to .72f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.VEILED, PrismContour.BLOOM,
                    1f, .20f, .08f, .16f, .45f, .04f, .04f, .30f, .02f, .02f, .01f, .16f, 0f, 0f, 0f, .06f,
                    attack = .18f, decay = 3.5f, groups = wovenMotion, loop = 3.8f))),
        e("A finite evolving pad whose upper threads gather well after the lower body arrives.",
            p(PrismVoice.VEIL, "Rising Threads", "GRAIN" to .36f, "DRIFT" to .95f, "SHAPE" to .76f, "STRIKE" to .16f, "BODY" to .60f, "HOLD" to .20f)
                .copy(template = model(PrismSpectrum.VEILED, PrismContour.BLOOM,
                    1f, .35f, .28f, .24f, .16f, .28f, .08f, .22f, .05f, .13f, .03f, .10f, .02f, .015f, .01f, .07f,
                    attack = .14f, decay = 3.9f, groups = risingThreads))),
        e("A dark held texture with interlocking middle-band cycles over a nearly still root.",
            p(PrismVoice.VEIL, "Night Lattice", "GRAIN" to .72f, "DRIFT" to .82f, "SHAPE" to .12f, "STRIKE" to .18f, "BODY" to .82f, "HOLD" to 1f)
                .copy(template = model(PrismSpectrum.VEILED, PrismContour.BLOOM,
                    1f, .45f, .18f, .26f, .06f, .17f, .04f, .12f, .025f, .02f, .01f, .06f, 0f, 0f, 0f, .025f,
                    attack = .18f, decay = 3.8f, groups = latticeMotion, loop = 3.6f))),

        // GLASS: no ratio or weight exceeds the approved softer model's upper-partial recipe.
        e("A warm, rounded chime with a soft entrance and a modest harmonic ring.",
            p(PrismVoice.GLASS, "Rounded Chime", "GRAIN" to .36f, "DRIFT" to .30f, "SHAPE" to .22f, "STRIKE" to .28f, "BODY" to .56f)
                .copy(template = glass(attack = .032f, decay = 2.3f, groups = tineDevelopment,
                    scale = { n -> when { n <= 3 -> 1f; n <= 6 -> .72f; else -> .40f } }))),
        e("A small, delicate glass drop with a short tail and quickly damped upper contact.",
            p(PrismVoice.GLASS, "Pearl Drop", "GRAIN" to .74f, "DRIFT" to .50f, "SHAPE" to .56f, "STRIKE" to .50f, "BODY" to .44f)
                .copy(template = glass(attack = .007f, decay = 1.35f, groups = shortDamping,
                    scale = { n -> when (n) { 1 -> 1f; 2 -> .70f; 3 -> .85f; 4 -> .60f; 5, 6 -> .65f; else -> .35f } }))),
        e("A gentle dry bell with a distant-feeling onset and a long, subdued ring.",
            p(PrismVoice.GLASS, "Distant Bell", "GRAIN" to .56f, "DRIFT" to .65f, "SHAPE" to .38f, "STRIKE" to .12f, "BODY" to .48f)
                .copy(template = glass(attack = .055f, decay = 4f, groups = reedDevelopment,
                    scale = { n -> if (n <= 4) 1f else if (n <= 6) .70f else .38f }))),
        e("A held glass mantle with gentle upper-band breathing and a coherent low body.",
            p(PrismVoice.GLASS, "Clear Mantle", "GRAIN" to .62f, "DRIFT" to .60f, "SHAPE" to .48f, "STRIKE" to .20f, "BODY" to .45f, "HOLD" to 1f)
                .copy(template = glass(attack = .04f, decay = 3f, groups = glassMotion, loop = 3.2f,
                    scale = { n -> if (n <= 4) 1f else if (n <= 8) .70f else .38f }))),
        e("A held, hollow crystal tone whose sparse upper harmonics move slowly around the root.",
            p(PrismVoice.GLASS, "Slow Crystal", "GRAIN" to .78f, "DRIFT" to .82f, "SHAPE" to .62f, "STRIKE" to .26f, "BODY" to .60f, "HOLD" to 1f)
                .copy(template = glass(attack = .05f, decay = 3.5f, groups = crystalMotion, loop = 3.6f,
                    scale = { n -> when (n) { 1 -> 1f; 2 -> .18f; 3 -> .92f; 4 -> .20f; 5 -> .48f; 6 -> .38f; else -> .25f } }))),
    ) + PrismOpeningPresets.all().map { patch ->
        e(requireNotNull(PrismOpeningPresets.descriptionFor(patch.name)), patch)
    }

    fun forVoice(voice: PrismVoice): List<PrismPatch> = presets.filter { it.voice == voice }
    fun all(): List<PrismPatch> = presets
    fun descriptionFor(name: String): String? = roles[name]
}
