package com.snipsnap.synth

/** Dry provisional routing voices plus the unchanged first and second listening recipes. */
object FilamentPresets {
    private fun p(
        voice: FilamentVoice,
        name: String,
        vararg macros: Pair<String, Float>,
        config: FilamentConfig = FilamentConfig(),
    ) = FilamentPatch(name, voice, Filament.defaults(voice) + macros, config = config)

    // These complete macro snapshots freeze the model-one listening contract;
    // future default voicing must not change an original recipe by inheritance.
    private fun originalPatch(
        voice: FilamentVoice,
        name: String,
        vararg macros: Pair<String, Float>,
        config: FilamentConfig = FilamentConfig(),
    ): FilamentPatch {
        val values = when (voice) {
            FilamentVoice.THREAD -> listOf(.25f, .15f, .25f, 0f, .20f, 0f)
            FilamentVoice.PULSE -> listOf(.65f, .30f, .65f, .20f, .35f, 0f)
            FilamentVoice.CURL -> listOf(.45f, .65f, .45f, .15f, .50f, 0f)
        }
        val names = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD")
        return FilamentPatch(name, voice, names.zip(values).toMap() + macros,
            midi = 48, velocity = 1f, config = config, model = Filament.ORIGINAL_MODEL)
    }

    private val originalPresets = listOf(
        originalPatch(FilamentVoice.THREAD, "Glass Thread"),
        originalPatch(FilamentVoice.THREAD, "Narrow Arrival",
            "INJECT" to .12f, "BEND" to .12f, "LENGTH" to .18f, "BUNDLE" to 0f, "COLLECT" to .10f),
        originalPatch(FilamentVoice.THREAD, "Four Routes",
            "INJECT" to .45f, "BEND" to .35f, "LENGTH" to .60f, "BUNDLE" to 1f, "COLLECT" to .35f,
            config = FilamentConfig(seed = 17)),
        originalPatch(FilamentVoice.THREAD, "Held Thread",
            "BEND" to .30f, "LENGTH" to .40f, "BUNDLE" to .35f, "COLLECT" to .40f, "HOLD" to 1f),
        originalPatch(FilamentVoice.PULSE, "Pulse Passage"),
        originalPatch(FilamentVoice.PULSE, "Long Passage",
            "INJECT" to .75f, "BEND" to .40f, "LENGTH" to .95f, "BUNDLE" to .35f, "COLLECT" to .35f,
            config = FilamentConfig(profile = FilamentProfile.LATE)),
        originalPatch(FilamentVoice.PULSE, "Early Glints",
            "INJECT" to .65f, "BEND" to .70f, "LENGTH" to .65f, "BUNDLE" to .25f, "COLLECT" to .80f,
            config = FilamentConfig(profile = FilamentProfile.EARLY)),
        originalPatch(FilamentVoice.PULSE, "Held Procession",
            "INJECT" to .55f, "BEND" to .45f, "LENGTH" to .70f, "BUNDLE" to .50f, "COLLECT" to .55f, "HOLD" to 1f),
        originalPatch(FilamentVoice.CURL, "Loose Curl"),
        originalPatch(FilamentVoice.CURL, "Bend Glow",
            "INJECT" to .50f, "BEND" to .85f, "LENGTH" to .55f, "BUNDLE" to .45f, "COLLECT" to .75f,
            config = FilamentConfig(profile = FilamentProfile.COIL)),
        originalPatch(FilamentVoice.CURL, "Spilled Silver",
            "INJECT" to .75f, "BEND" to .90f, "LENGTH" to .80f, "BUNDLE" to .90f, "COLLECT" to .90f,
            config = FilamentConfig(seed = 29, profile = FilamentProfile.LATE)),
        originalPatch(FilamentVoice.CURL, "Held Curl",
            "BEND" to .65f, "LENGTH" to .60f, "BUNDLE" to .65f, "COLLECT" to .65f, "HOLD" to 1f),
    )

    /** Complete model-two snapshots, including the finite HOLD zero, with no inherited defaults. */
    private fun characterPatch(
        voice: FilamentVoice,
        name: String,
        vararg macros: Pair<String, Float>,
        config: FilamentConfig = FilamentConfig(),
    ): FilamentPatch {
        val saved = macros.toMap()
        val names = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD")
        require(names.dropLast(1).all { it in saved }) { "a model-two snapshot must name every timbral control" }
        return FilamentPatch(name, voice, names.associateWith { saved[it] ?: 0f },
            midi = 48, velocity = 1f, config = config, model = Filament.CHARACTER_MODEL)
    }

    private val revision2Presets = listOf(
        // Bright point, soft wood, a related bundle, and a focused held line.
        characterPatch(FilamentVoice.THREAD, "Glass Thread",
            "INJECT" to .82f, "BEND" to .24f, "LENGTH" to .22f, "BUNDLE" to .12f, "COLLECT" to .32f),
        characterPatch(FilamentVoice.THREAD, "Narrow Arrival",
            "INJECT" to .16f, "BEND" to .38f, "LENGTH" to .46f, "BUNDLE" to 0f, "COLLECT" to .24f,
            config = FilamentConfig(seed = 11, profile = FilamentProfile.EARLY)),
        characterPatch(FilamentVoice.THREAD, "Four Routes",
            "INJECT" to .60f, "BEND" to .42f, "LENGTH" to .72f, "BUNDLE" to 1f, "COLLECT" to .42f,
            config = FilamentConfig(seed = 17, profile = FilamentProfile.COIL)),
        characterPatch(FilamentVoice.THREAD, "Held Thread",
            "INJECT" to .42f, "BEND" to .18f, "LENGTH" to .30f, "BUNDLE" to .06f, "COLLECT" to .18f, "HOLD" to 1f),
        // Three finite articulations separate attack, procession and body weight.
        characterPatch(FilamentVoice.PULSE, "Pulse Passage",
            "INJECT" to .88f, "BEND" to .22f, "LENGTH" to .34f, "BUNDLE" to .08f, "COLLECT" to .24f),
        characterPatch(FilamentVoice.PULSE, "Long Passage",
            "INJECT" to .70f, "BEND" to .48f, "LENGTH" to .92f, "BUNDLE" to .62f, "COLLECT" to .48f,
            config = FilamentConfig(seed = 23, profile = FilamentProfile.LATE)),
        characterPatch(FilamentVoice.PULSE, "Early Glints",
            "INJECT" to .28f, "BEND" to .54f, "LENGTH" to .58f, "BUNDLE" to .30f, "COLLECT" to .54f,
            config = FilamentConfig(seed = 31, profile = FilamentProfile.EARLY)),
        characterPatch(FilamentVoice.PULSE, "Held Procession",
            "INJECT" to .76f, "BEND" to .58f, "LENGTH" to .78f, "BUNDLE" to .82f, "COLLECT" to .56f, "HOLD" to 1f,
            config = FilamentConfig(seed = 41, profile = FilamentProfile.COIL)),
        // Dark hollowing, a late bloom, a brighter edge, and the held escaped body.
        characterPatch(FilamentVoice.CURL, "Loose Curl",
            "INJECT" to .14f, "BEND" to .68f, "LENGTH" to .64f, "BUNDLE" to .24f, "COLLECT" to .62f,
            config = FilamentConfig(seed = 47)),
        characterPatch(FilamentVoice.CURL, "Bend Glow",
            "INJECT" to .48f, "BEND" to .86f, "LENGTH" to .88f, "BUNDLE" to .58f, "COLLECT" to .82f,
            config = FilamentConfig(seed = 53, profile = FilamentProfile.LATE)),
        characterPatch(FilamentVoice.CURL, "Spilled Silver",
            "INJECT" to .94f, "BEND" to .76f, "LENGTH" to .42f, "BUNDLE" to .72f, "COLLECT" to .72f,
            config = FilamentConfig(seed = 29, profile = FilamentProfile.COIL)),
        characterPatch(FilamentVoice.CURL, "Held Curl",
            "INJECT" to .36f, "BEND" to .82f, "LENGTH" to .76f, "BUNDLE" to .92f, "COLLECT" to .88f, "HOLD" to 1f,
            config = FilamentConfig(seed = 61, profile = FilamentProfile.LATE)),
    )

    private val presets = listOf(
        // Clear arrivals with restrained answers, plus the coordinated four-route line.
        p(FilamentVoice.THREAD, "Glass Thread",
            "INJECT" to .66f, "BEND" to .30f, "LENGTH" to .38f, "BUNDLE" to .38f, "COLLECT" to .36f),
        p(FilamentVoice.THREAD, "Narrow Arrival",
            "INJECT" to .28f, "BEND" to .36f, "LENGTH" to .52f, "BUNDLE" to .46f, "COLLECT" to .40f,
            config = FilamentConfig(seed = 11, profile = FilamentProfile.EARLY)),
        p(FilamentVoice.THREAD, "Four Routes",
            "INJECT" to .58f, "BEND" to .48f, "LENGTH" to .72f, "BUNDLE" to .78f, "COLLECT" to .50f,
            config = FilamentConfig(seed = 17, profile = FilamentProfile.COIL)),
        p(FilamentVoice.THREAD, "Held Thread",
            "INJECT" to .48f, "BEND" to .40f, "LENGTH" to .56f, "BUNDLE" to .50f, "COLLECT" to .42f, "HOLD" to 1f),
        // Ordinary pulse settings retain energy at the junction so related routes can answer.
        p(FilamentVoice.PULSE, "Pulse Passage",
            "INJECT" to .72f, "BEND" to .40f, "LENGTH" to .64f, "BUNDLE" to .68f, "COLLECT" to .52f),
        p(FilamentVoice.PULSE, "Long Passage",
            "INJECT" to .78f, "BEND" to .58f, "LENGTH" to .90f, "BUNDLE" to .88f, "COLLECT" to .62f,
            config = FilamentConfig(seed = 23, profile = FilamentProfile.LATE)),
        p(FilamentVoice.PULSE, "Early Glints",
            "INJECT" to .50f, "BEND" to .32f, "LENGTH" to .48f, "BUNDLE" to .46f, "COLLECT" to .42f,
            config = FilamentConfig(seed = 31, profile = FilamentProfile.EARLY)),
        p(FilamentVoice.PULSE, "Held Procession",
            "INJECT" to .68f, "BEND" to .54f, "LENGTH" to .76f, "BUNDLE" to .76f, "COLLECT" to .56f, "HOLD" to 1f,
            config = FilamentConfig(seed = 41, profile = FilamentProfile.COIL)),
        // Longer shared travel and late bends expose the slower exchanged bloom.
        p(FilamentVoice.CURL, "Loose Curl",
            "INJECT" to .38f, "BEND" to .50f, "LENGTH" to .68f, "BUNDLE" to .55f, "COLLECT" to .58f,
            config = FilamentConfig(seed = 47)),
        p(FilamentVoice.CURL, "Bend Glow",
            "INJECT" to .56f, "BEND" to .66f, "LENGTH" to .92f, "BUNDLE" to .86f, "COLLECT" to .68f,
            config = FilamentConfig(seed = 53, profile = FilamentProfile.LATE)),
        p(FilamentVoice.CURL, "Spilled Silver",
            "INJECT" to .82f, "BEND" to .60f, "LENGTH" to .68f, "BUNDLE" to .72f, "COLLECT" to .64f,
            config = FilamentConfig(seed = 29, profile = FilamentProfile.COIL)),
        p(FilamentVoice.CURL, "Held Curl",
            "INJECT" to .48f, "BEND" to .68f, "LENGTH" to .86f, "BUNDLE" to .82f, "COLLECT" to .70f, "HOLD" to 1f,
            config = FilamentConfig(seed = 61, profile = FilamentProfile.LATE)),
    )

    /** Exact model-one starting points for before/after listening and saved-recipe replay. */
    fun original(): List<FilamentPatch> = originalPresets
    /** Exact model-two character recipes before the reactive routing revision. */
    fun revision2(): List<FilamentPatch> = revision2Presets
    fun forVoice(voice: FilamentVoice): List<FilamentPatch> = presets.filter { it.voice == voice }
    fun all(): List<FilamentPatch> = presets
}
