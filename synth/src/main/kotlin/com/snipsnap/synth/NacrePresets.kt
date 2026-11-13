package com.snipsnap.synth

/** Three retained starting sounds and two owner-kept model-4 contact settings of one PEARL voice. */
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

    private val presets = listOf(
        p("Pearl Close", 0f, .15f),
        p("Coral Reply", 0f, .35f),
        p("Kelp Passage", 1f, .35f),
        contact("Pearl Moderate Contact", .35f),
        contact("Pearl Strong Contact", .8f),
    )

    fun forVoice(voice: NacreVoice): List<NacrePatch> = presets.filter { it.voice == voice }
    fun all(): List<NacrePatch> = presets
}
