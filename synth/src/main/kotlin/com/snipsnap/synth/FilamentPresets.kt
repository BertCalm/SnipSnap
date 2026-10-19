package com.snipsnap.synth

/** Twelve dry first-listen recipes, held outside the factory roster and phone picker. */
object FilamentPresets {
    private fun p(
        voice: FilamentVoice,
        name: String,
        vararg macros: Pair<String, Float>,
        config: FilamentConfig = FilamentConfig(),
    ) = FilamentPatch(name, voice, Filament.defaults(voice) + macros, config = config)

    private val presets = listOf(
        p(FilamentVoice.THREAD, "Glass Thread"),
        p(FilamentVoice.THREAD, "Narrow Arrival",
            "INJECT" to .12f, "BEND" to .12f, "LENGTH" to .18f, "BUNDLE" to 0f, "COLLECT" to .10f),
        p(FilamentVoice.THREAD, "Four Routes",
            "INJECT" to .45f, "BEND" to .35f, "LENGTH" to .60f, "BUNDLE" to 1f, "COLLECT" to .35f,
            config = FilamentConfig(seed = 17)),
        p(FilamentVoice.THREAD, "Held Thread",
            "BEND" to .30f, "LENGTH" to .40f, "BUNDLE" to .35f, "COLLECT" to .40f, "HOLD" to 1f),
        p(FilamentVoice.PULSE, "Pulse Passage"),
        p(FilamentVoice.PULSE, "Long Passage",
            "INJECT" to .75f, "BEND" to .40f, "LENGTH" to .95f, "BUNDLE" to .35f, "COLLECT" to .35f,
            config = FilamentConfig(profile = FilamentProfile.LATE)),
        p(FilamentVoice.PULSE, "Early Glints",
            "INJECT" to .65f, "BEND" to .70f, "LENGTH" to .65f, "BUNDLE" to .25f, "COLLECT" to .80f,
            config = FilamentConfig(profile = FilamentProfile.EARLY)),
        p(FilamentVoice.PULSE, "Held Procession",
            "INJECT" to .55f, "BEND" to .45f, "LENGTH" to .70f, "BUNDLE" to .50f, "COLLECT" to .55f, "HOLD" to 1f),
        p(FilamentVoice.CURL, "Loose Curl"),
        p(FilamentVoice.CURL, "Bend Glow",
            "INJECT" to .50f, "BEND" to .85f, "LENGTH" to .55f, "BUNDLE" to .45f, "COLLECT" to .75f,
            config = FilamentConfig(profile = FilamentProfile.COIL)),
        p(FilamentVoice.CURL, "Spilled Silver",
            "INJECT" to .75f, "BEND" to .90f, "LENGTH" to .80f, "BUNDLE" to .90f, "COLLECT" to .90f,
            config = FilamentConfig(seed = 29, profile = FilamentProfile.LATE)),
        p(FilamentVoice.CURL, "Held Curl",
            "BEND" to .65f, "LENGTH" to .60f, "BUNDLE" to .65f, "COLLECT" to .65f, "HOLD" to 1f),
    )

    fun forVoice(voice: FilamentVoice): List<FilamentPatch> = presets.filter { it.voice == voice }
    fun all(): List<FilamentPatch> = presets
}
