package com.snipsnap.synth

/** Eight provisional dry recipes for the GLASS/RIPPLE first audition. */
object ShoalPresets {
    private fun p(voice: ShoalVoice, name: String, vararg macros: Pair<String, Float>) =
        ShoalPatch(name, voice, Shoal.defaults(voice) + macros.toMap())

    private val recipes: List<ShoalPatch> by lazy {
        listOf(
            p(ShoalVoice.GLASS, "Glass Gathering"),
            p(ShoalVoice.GLASS, "Quiet School", "DENSITY" to .20f, "CURRENT" to .18f,
                "COHESION" to .72f, "STRAY" to 0f, "GLOW" to .28f),
            p(ShoalVoice.GLASS, "Stray Light", "DENSITY" to .62f, "CURRENT" to .52f,
                "COHESION" to .42f, "STRAY" to .65f, "GLOW" to .72f),
            p(ShoalVoice.GLASS, "Glass Lantern", "DENSITY" to .48f, "CURRENT" to .42f,
                "COHESION" to .70f, "STRAY" to .25f, "GLOW" to .70f, "HOLD" to 1f),
            p(ShoalVoice.RIPPLE, "Rising Shoal"),
            p(ShoalVoice.RIPPLE, "Narrow Current", "DENSITY" to .25f, "CURRENT" to .72f,
                "COHESION" to .80f, "STRAY" to .12f, "GLOW" to .48f),
            p(ShoalVoice.RIPPLE, "Open Water", "DENSITY" to .78f, "CURRENT" to .58f,
                "COHESION" to .12f, "STRAY" to .38f, "GLOW" to .78f),
            p(ShoalVoice.RIPPLE, "Harmonic Tide", "DENSITY" to .58f, "CURRENT" to .62f,
                "COHESION" to .64f, "STRAY" to .20f, "GLOW" to .68f, "HOLD" to 1f),
        )
    }

    fun forVoice(voice: ShoalVoice): List<ShoalPatch> = recipes.filter { it.voice == voice }
    fun all(): List<ShoalPatch> = recipes.toList()
}
