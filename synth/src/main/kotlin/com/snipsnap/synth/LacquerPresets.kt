package com.snipsnap.synth

/** Eight dry starting recipes for audition. The 36-preset bank awaits owner sonic approval. */
object LacquerPresets {
    private fun p(voice: LacquerVoice, name: String, vararg macros: Pair<String, Float>) =
        LacquerPatch(name, voice, Lacquer.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(LacquerVoice.GLASS, "Glass Coat", "RELIEF" to .40f, "SHEEN" to .74f, "BODY" to .43f, "CONTOUR" to .48f, "GLUE" to .18f),
        p(LacquerVoice.KEY, "Warm Underlay", "RELIEF" to .24f, "SHEEN" to .30f, "BODY" to .78f, "CONTOUR" to .54f, "GLUE" to .34f),
        p(LacquerVoice.KEY, "Three Arrivals", "RELIEF" to .92f, "SHEEN" to .58f, "BODY" to .58f, "CONTOUR" to .64f, "GLUE" to .18f),
        p(LacquerVoice.BRASS, "Amber Brass", "RELIEF" to .40f, "SHEEN" to .56f, "BODY" to .76f, "CONTOUR" to .65f, "GLUE" to .38f),
        p(LacquerVoice.BASS, "Soft Surface", "RELIEF" to .20f, "SHEEN" to .24f, "BODY" to .86f, "CONTOUR" to .42f, "GLUE" to .30f),
        p(LacquerVoice.PLUCK, "Delayed Tine", "RELIEF" to .80f, "SHEEN" to .68f, "BODY" to .44f, "CONTOUR" to .38f, "GLUE" to .16f),
        p(LacquerVoice.SILK, "Silk Block", "RELIEF" to .74f, "SHEEN" to .42f, "BODY" to .64f, "CONTOUR" to .85f, "GLUE" to .32f, "HOLD" to 1f),
        p(LacquerVoice.BRASS, "Close Finish", "RELIEF" to .08f, "SHEEN" to .48f, "BODY" to .64f, "CONTOUR" to .24f, "GLUE" to .62f),
    )

    fun forVoice(voice: LacquerVoice): List<LacquerPatch> = presets.filter { it.voice == voice }
    fun all(): List<LacquerPatch> = presets
}
