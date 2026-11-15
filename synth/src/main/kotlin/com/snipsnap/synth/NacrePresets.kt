package com.snipsnap.synth

/**
 * PEARL: three retained starting sounds and two owner-kept model-4 contact settings. HARMONICS, SHELL
 * and BUBBLE: four each on their own saved models (5, 6 and 7). GROTTO: four on saved model 8, each a
 * setting the owner kept in round nacre-grotto-1.
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

    /** A HARMONICS, SHELL or BUBBLE recipe on its own saved model; unset controls keep the voice's defaults. */
    private fun voice(name: String, voice: NacreVoice, touch: Float = .30f, current: Float = .20f,
                      venue: Float = .15f, bloom: Float = .35f, duet: Float = .15f) = NacrePatch(
        name, voice,
        mapOf("TOUCH" to touch, "DUET" to duet, "CURRENT" to current, "VENUE" to venue, "BLOOM" to bloom, "HOLD" to 0f),
    )

    /**
     * A GROTTO recipe as round nacre-grotto-1 played it: C2, velocity .8, seed 7, with [hold] either a 4 s finite bow
     * (the desk's finite clips) or the held loop (HOLD 1, the held recipe of D4 and D5).
     */
    private fun grotto(name: String, hold: Float, touch: Float = .30f, duet: Float = .15f, venue: Float = .15f) = NacrePatch(
        name, NacreVoice.GROTTO,
        mapOf("TOUCH" to touch, "DUET" to duet, "CURRENT" to .20f, "VENUE" to venue, "BLOOM" to .35f, "HOLD" to hold),
        midi = NacreGrotto.DEFAULT_MIDI, velocity = .8f, seed = Nacre.DEFAULT_SEED,
    )

    /** The 4 s finite bow of round nacre-grotto-1's finite clips. */
    private val fourSecondBow = NacreGrotto.holdFor(4f)

    private val presets = listOf(
        p("Pearl Close", 0f, .15f),
        p("Coral Reply", 0f, .35f),
        p("Kelp Passage", 1f, .35f),
        contact("Pearl Moderate Contact", .35f),
        contact("Pearl Strong Contact", .8f),
        // HARMONICS and SHELL: the owner kept all eight in nacre-voices-2; the first of each is the
        // default sound kept in nacre-voices-1.
        voice("Glass Harmonic", NacreVoice.HARMONICS),
        voice("Soft Harmonic", NacreVoice.HARMONICS, touch = .10f, bloom = .45f),
        voice("Harmonic Kelp", NacreVoice.HARMONICS, current = .35f, venue = 1f, bloom = .50f),
        voice("Harmonic Ring", NacreVoice.HARMONICS, touch = .70f, bloom = .70f),
        voice("Shell Tap", NacreVoice.SHELL),
        voice("Deep Shell", NacreVoice.SHELL, touch = .15f, bloom = .60f),
        voice("Shell Reef", NacreVoice.SHELL, current = .30f, venue = 0f, bloom = .55f),
        voice("Shell Bloom", NacreVoice.SHELL, touch = .60f, venue = .60f, bloom = .80f),
        // BUBBLE: Bubble Drop is the default sound the owner kept in nacre-voices-2 (U1 to U3). The
        // other three are unheard.
        voice("Bubble Drop", NacreVoice.BUBBLE),
        voice("Soft Bubble", NacreVoice.BUBBLE, touch = .10f, bloom = .45f),
        voice("Bubble Stream", NacreVoice.BUBBLE, duet = .55f, current = .35f, venue = .60f, bloom = .50f),
        voice("Bubble Burst", NacreVoice.BUBBLE, touch = .80f, bloom = .70f),
        // GROTTO: every one is a render the owner kept in nacre-grotto-1 (15 Nov 2026, D1 to D10 all KEEP).
        // Grotto Bow is D1, the default controls; Soft Grotto is D2's light bow (TOUCH .15); Grotto Glints is
        // D6's most glints (DUET .55, held); Grotto Kelp is D7's far seaweed (VENUE 1, held).
        grotto("Grotto Bow", fourSecondBow),
        grotto("Soft Grotto", fourSecondBow, touch = .15f),
        grotto("Grotto Glints", 1f, duet = .55f),
        grotto("Grotto Kelp", 1f, venue = 1f),
    )

    fun forVoice(voice: NacreVoice): List<NacrePatch> = presets.filter { it.voice == voice }
    fun all(): List<NacrePatch> = presets
}
