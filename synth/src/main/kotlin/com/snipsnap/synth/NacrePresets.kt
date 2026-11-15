package com.snipsnap.synth

/**
 * PEARL: three retained starting sounds and two owner-kept model-4 contact settings. HARMONICS and
 * SHELL: four each on their own saved models (5 and 6).
 */
object NacrePresets {
    private fun p(name: String, venue: Float, bloom: Float) = NacrePatch(
        name, NacreVoice.PEARL,
        mapOf("TOUCH" to .30f, "DUET" to .15f, "CURRENT" to .20f,
            "VENUE" to venue, "BLOOM" to bloom, "HOLD" to 0f),
        model = 3,
    )

    private fun contact(name: String, loss: Float): NacrePatch {
        val reference = p(name, 0f, .15f)
        return reference.copy(model = NacreModels.CONTACT_MODEL_VERSION,
            macros = reference.macros + (NacreModels.CONTACT_MACRO to loss))
    }

    /** A HARMONICS or SHELL recipe on its own saved model; unset controls keep the voice's defaults. */
    private fun voice(name: String, voice: NacreVoice, touch: Float = .30f, current: Float = .20f,
                      venue: Float = .15f, bloom: Float = .35f) = NacrePatch(
        name, voice,
        mapOf("TOUCH" to touch, "DUET" to .15f, "CURRENT" to current, "VENUE" to venue, "BLOOM" to bloom, "HOLD" to 0f),
    )

    private val presets = listOf(
        p("Pearl Close", 0f, .15f),
        p("Coral Reply", 0f, .35f),
        p("Kelp Passage", 1f, .35f),
        contact("Pearl Moderate Contact", .35f),
        contact("Pearl Strong Contact", .8f),
        // HARMONICS and SHELL: the first of each is the default sound the owner kept in nacre-voices-1.
        voice("Glass Harmonic", NacreVoice.HARMONICS),
        voice("Soft Harmonic", NacreVoice.HARMONICS, touch = .10f, bloom = .45f),
        voice("Harmonic Kelp", NacreVoice.HARMONICS, current = .35f, venue = 1f, bloom = .50f),
        voice("Harmonic Ring", NacreVoice.HARMONICS, touch = .70f, bloom = .70f),
        voice("Shell Tap", NacreVoice.SHELL),
        voice("Deep Shell", NacreVoice.SHELL, touch = .15f, bloom = .60f),
        voice("Shell Reef", NacreVoice.SHELL, current = .30f, venue = 0f, bloom = .55f),
        voice("Shell Bloom", NacreVoice.SHELL, touch = .60f, venue = .60f, bloom = .80f),
    )

    fun forVoice(voice: NacreVoice): List<NacrePatch> = presets.filter { it.voice == voice }
    fun all(): List<NacrePatch> = presets
}
