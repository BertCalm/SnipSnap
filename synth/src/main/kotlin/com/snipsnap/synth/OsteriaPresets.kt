package com.snipsnap.synth

/**
 * The accepted six-voice factory, retaining the three original dry Note recipes.
 * The last two differ only in MEMORY, so remembered color can be compared on one note.
 * Model 2 has one fixed root and a shared attack and release across all four layers.
 * These are finite notes; HOLD extends their duration and does not select a loop.
 */
object OsteriaPresets {
    private fun p(voice: OsteriaVoice, name: String, vararg macros: Pair<String, Float>) = OsteriaPatch(
        name = name,
        voice = voice,
        macros = Osteria.defaults(voice) + mapOf(
            "SHORE" to .42f,
            "ROOM" to 0f,
            "HOLD" to 0f,
        ) + macros.toMap(),
        midi = 48,
        velocity = .8f,
        config = Osteria.configurationFor(voice).copy(seed = 173),
    )

    private val presets = listOf(
        p(OsteriaVoice.QUARTET, "Anchored Note", "TENSION" to .88f, "WANDER" to .08f, "MEMORY" to 0f),
        p(OsteriaVoice.QUARTET, "Evolving Note", "TENSION" to .20f, "WANDER" to .85f, "MEMORY" to 0f),
        p(OsteriaVoice.QUARTET, "Lingering Color", "TENSION" to .20f, "WANDER" to .85f, "MEMORY" to .88f),
    )

    /** Keep the three original recipes and their established names/audio unchanged. */
    fun original(): List<OsteriaPatch> = presets

    /** Exact twelve recipes from the owner-approved bank, before register/touch placement. */
    fun selected(): List<OsteriaPatch> = OsteriaSixVoiceCandidates.all()

    // Voice-local chip labels leave the already-published YOURS import names
    // available. The two selected Note recipes already equal original recipes.
    // Only the display name changes; every accepted performance field is kept.
    private val factory by lazy {
        val labels = mapOf(
            "Anchor Bass" to "Anchor", "Roaming Bass" to "Roaming",
            "Rounded Pluck" to "Rounded", "Bright Pluck" to "Bright",
            "Still Reed" to "Still", "Remembered Reed" to "Remembered",
            "Warm Swell" to "Warm", "Memory Drift" to "Drift",
            "Warm Bow" to "Warm", "Rosin Bow" to "Rosin",
        )
        original() + selected().filter { it.voice != OsteriaVoice.QUARTET }
            .map { it.copy(name = labels.getValue(it.name)) }
    }
    fun forVoice(voice: OsteriaVoice): List<OsteriaPatch> = all().filter { it.voice == voice }
    fun all(): List<OsteriaPatch> = factory
}
