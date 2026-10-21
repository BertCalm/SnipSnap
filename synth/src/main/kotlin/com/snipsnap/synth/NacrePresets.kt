package com.snipsnap.synth

/** Six provisional PEARL gestures for listening, outside the factory roster and phone picker. */
object NacrePresets {
    private fun p(name: String, vararg macros: Pair<String, Float>) =
        NacrePatch(name, NacreVoice.PEARL, Nacre.defaults(NacreVoice.PEARL) + macros)

    private val presets = listOf(
        p("Pearl Duet", "TOUCH" to .30f, "DUET" to .15f, "CURRENT" to .20f,
            "VENUE" to 0f, "BLOOM" to .35f),
        p("Still Coral", "TOUCH" to .35f, "DUET" to .10f, "CURRENT" to 0f,
            "VENUE" to 0f, "BLOOM" to .45f),
        p("Coral Answer", "TOUCH" to .40f, "DUET" to .55f, "CURRENT" to .55f,
            "VENUE" to 0f, "BLOOM" to .60f),
        p("Kelp Curtain", "TOUCH" to .25f, "DUET" to .45f, "CURRENT" to .65f,
            "VENUE" to 1f, "BLOOM" to .45f),
        p("Still Kelp", "TOUCH" to .30f, "DUET" to .15f, "CURRENT" to 0f,
            "VENUE" to 1f, "BLOOM" to .40f),
        p("Crossing Tides", "TOUCH" to .60f, "DUET" to .90f, "CURRENT" to .70f,
            "VENUE" to 1f, "BLOOM" to .65f),
    )

    fun forVoice(voice: NacreVoice): List<NacrePatch> = presets.filter { it.voice == voice }

    fun all(): List<NacrePatch> = presets
}
