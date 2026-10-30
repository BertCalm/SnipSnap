package com.snipsnap.synth

/** Three playable starting points around the owner-preferred harp and timed venue responses. */
object NacrePresets {
    private fun p(name: String, venue: Float, bloom: Float) = NacrePatch(
        name, NacreVoice.PEARL,
        mapOf("TOUCH" to .30f, "DUET" to .15f, "CURRENT" to .20f,
            "VENUE" to venue, "BLOOM" to bloom, "HOLD" to 0f),
        model = 3,
    )

    private val presets = listOf(
        p("Pearl Close", 0f, .15f),
        p("Coral Reply", 0f, .35f),
        p("Kelp Passage", 1f, .35f),
    )

    fun forVoice(voice: NacreVoice): List<NacrePatch> = presets.filter { it.voice == voice }
    fun all(): List<NacrePatch> = presets
}
