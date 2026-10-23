package com.snipsnap.synth

/** Provisional listening recipes, kept outside the factory roster until owner listening. */
object SkirlPresets {
    private val presets = listOf(
        SkirlPatch("Rolling Rim", SkirlVoice.ROLL, Skirl.defaults(SkirlVoice.ROLL)),
        SkirlPatch("Closing Whirr", SkirlVoice.WHIRR, Skirl.defaults(SkirlVoice.WHIRR)),
    )

    fun forVoice(voice: SkirlVoice): List<SkirlPatch> = presets.filter { it.voice == voice }
    fun all(): List<SkirlPatch> = presets
}
