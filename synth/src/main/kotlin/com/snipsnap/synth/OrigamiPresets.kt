package com.snipsnap.synth

/** Accepted model-4 references alongside opt-in fold gestures, all dry finite notes. */
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

    private fun expressive(voice: OrigamiVoice, name: String, gesture: OrigamiGesture,
                           fold: Float, motion: Float) =
        p(voice, name, "FOLD" to fold, "MOTION" to motion).copy(
            gesture = gesture, expression = OrigamiExpression.FOLD,
        )

    // Each pair shares geometry and timing so its direction is the only authored difference.
    // These travel times leave a stable plateau before each voice's finite release begins.
    private val performancePresets = listOf(
        expressive(OrigamiVoice.CRANE, "Paper Bloom", OrigamiGesture.BLOOM, .65f, .35f),
        expressive(OrigamiVoice.CRANE, "Paper Unfold", OrigamiGesture.UNFOLD, .65f, .35f),
        expressive(OrigamiVoice.FAN, "Fifth Bloom", OrigamiGesture.BLOOM, .65f, .55f),
        expressive(OrigamiVoice.FAN, "Fifth Unfold", OrigamiGesture.UNFOLD, .65f, .55f),
        expressive(OrigamiVoice.STILL, "Soft Bloom", OrigamiGesture.BLOOM, .62f, .70f),
        expressive(OrigamiVoice.STILL, "Soft Unfold", OrigamiGesture.UNFOLD, .62f, .70f),
    )

    /** The original six accepted STEADY/LEVEL references and gentle variations, in saved order. */
    fun references(): List<OrigamiPatch> = presets

    /** One BLOOM/UNFOLD pair per voice; velocity chooses color beneath the authored fold ceiling. */
    fun performance(): List<OrigamiPatch> = performancePresets

    fun forVoice(voice: OrigamiVoice): List<OrigamiPatch> = all().filter { it.voice == voice }
    fun all(): List<OrigamiPatch> = references() + performance()
}
