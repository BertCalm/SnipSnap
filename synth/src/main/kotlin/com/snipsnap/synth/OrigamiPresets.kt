package com.snipsnap.synth

/** Accepted model-4 voice references and gentle variations, all dry finite notes. */
object OrigamiPresets {
    private val reference = mapOf(
        "TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
        "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f,
    )

    private fun p(voice: OrigamiVoice, name: String, vararg macros: Pair<String, Float>) =
        OrigamiPatch(
            name, voice, reference + macros.toMap(),
            midi = null, velocity = .85f, seed = 20261012L, model = 4,
        )

    private val presets = listOf(
        p(OrigamiVoice.CRANE, "Paper Key"),
        p(OrigamiVoice.CRANE, "Light Crease", "FOLD" to .48f, "MOTION" to .08f),
        p(OrigamiVoice.FAN, "Warm Fan"),
        p(OrigamiVoice.FAN, "Soft Fifth", "FOLD" to .42f, "MOTION" to .12f),
        p(OrigamiVoice.STILL, "Tenth Bloom"),
        p(OrigamiVoice.STILL, "Quiet Paper", "FOLD" to .46f, "MOTION" to .12f),
    )

    fun forVoice(voice: OrigamiVoice): List<OrigamiPatch> = presets.filter { it.voice == voice }
    fun all(): List<OrigamiPatch> = presets
}
