package com.snipsnap.synth

/**
 * Eight provisional WICK recipes for the first listening review. They stay
 * outside [Presets], the phone picker and FRESH TAPE until that review.
 * The dry engine supplies the identity; none needs a landing FX chain.
 */
object WickPresets {
    private fun p(voice: WickVoice, name: String, vararg macros: Pair<String, Float>) =
        WickPatch(name, voice, Wick.defaults(voice) + macros)

    private val presets = listOf(
        p(WickVoice.PLUCK, "Small Spark"),
        p(WickVoice.PLUCK, "Soft Wick", "SPARK" to .20f, "FEED" to 0f,
            "CASCADE" to .12f, "TIMBRE" to .18f, "RECOVERY" to .40f),
        p(WickVoice.PLUCK, "Dry Chain", "SPARK" to .60f, "FEED" to .10f,
            "CASCADE" to .60f, "TIMBRE" to .42f, "RECOVERY" to .30f),
        p(WickVoice.PLUCK, "Held Glow", "SPARK" to .40f, "FEED" to .35f,
            "CASCADE" to .40f, "TIMBRE" to .30f, "RECOVERY" to .45f, "HOLD" to 1f),
        p(WickVoice.BUBBLE, "Bubble Chain"),
        p(WickVoice.BUBBLE, "Slow Feed", "SPARK" to .30f, "FEED" to .35f,
            "CASCADE" to .65f, "TIMBRE" to .30f, "RECOVERY" to .65f),
        p(WickVoice.BUBBLE, "Spent Bubbles", "SPARK" to .60f, "FEED" to .85f,
            "CASCADE" to .85f, "TIMBRE" to .60f, "RECOVERY" to .80f),
        p(WickVoice.BUBBLE, "Held Bubbles", "SPARK" to .45f, "FEED" to .65f,
            "CASCADE" to .75f, "TIMBRE" to .45f, "RECOVERY" to .60f, "HOLD" to 1f),
    )

    fun forVoice(voice: WickVoice): List<WickPatch> = presets.filter { it.voice == voice }

    fun all(): List<WickPatch> = presets
}
