package com.snipsnap.synth

/** Eight dry starting points; expansion waits for the owner's sonic verdict. */
object PrismPresets {
    private fun p(voice: PrismVoice, name: String, vararg macros: Pair<String, Float>) =
        PrismPatch(name, voice, Prism.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(PrismVoice.KEY, "Soft Facets", "GRAIN" to .22f, "DRIFT" to .28f, "STRIKE" to .25f, "BODY" to .68f),
        p(PrismVoice.KEY, "Wooden Spectrum", "GRAIN" to .36f, "DRIFT" to .42f, "SHAPE" to .18f, "BODY" to .85f),
        p(PrismVoice.PLUCK, "Partial Tines", "GRAIN" to .48f, "DRIFT" to .35f, "SHAPE" to .62f, "STRIKE" to .75f),
        p(PrismVoice.ORGAN, "Hollow Register", "GRAIN" to .28f, "DRIFT" to .25f, "SHAPE" to .42f, "BODY" to .70f),
        p(PrismVoice.BASS, "Root Glass", "GRAIN" to .32f, "DRIFT" to .18f, "SHAPE" to .58f, "STRIKE" to .58f, "BODY" to .76f),
        p(PrismVoice.VEIL, "Slow Separation", "GRAIN" to .58f, "DRIFT" to .80f, "SHAPE" to .65f, "BODY" to .55f),
        p(PrismVoice.VEIL, "Amber Veil", "GRAIN" to .35f, "DRIFT" to .65f, "SHAPE" to .24f, "STRIKE" to .20f, "BODY" to .72f, "HOLD" to 1f),
        p(PrismVoice.GLASS, "Small Prism", "GRAIN" to .70f, "DRIFT" to .48f, "SHAPE" to .72f, "STRIKE" to .62f, "BODY" to .30f),
    )

    fun forVoice(voice: PrismVoice): List<PrismPatch> = presets.filter { it.voice == voice }
    fun all(): List<PrismPatch> = presets
}
