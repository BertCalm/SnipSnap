package com.snipsnap.synth

/** Eight preserved player identities and two experimental BRASS GROW entry points. */
object EmbouchurePresets {
    private fun p(voice: EmbouchureVoice, name: String, vararg macros: Pair<String, Float>) =
        EmbouchurePatch(name, voice, Embouchure.defaults(voice, 2) + macros.toMap(), model = 2)

    private val presets = listOf(
        p(EmbouchureVoice.REED, "Leaning Reed").copy(gesture = EmbouchureGesture.LEANING_SWELL),
        p(EmbouchureVoice.BRASS, "Muted Bell", "SHAPE" to .62f).copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
        p(EmbouchureVoice.BASS, "Breath Pocket", "GRAIN" to .32f, "LENGTH" to .35f),
        p(EmbouchureVoice.SOFT, "Soft Entry").copy(gesture = EmbouchureGesture.SOFT_ENTRY),
        p(EmbouchureVoice.REED, "Short Overblow", "EFFORT" to .75f, "REED" to .65f, "LENGTH" to .25f)
            .copy(gesture = EmbouchureGesture.OVERPRESSURE),
        p(EmbouchureVoice.BOW, "Relaxed Bow", "EFFORT" to .35f, "REED" to .35f, "GRAIN" to .18f)
            .copy(gesture = EmbouchureGesture.BOWED_CAPTURE),
        p(EmbouchureVoice.WHISTLE, "Narrow Air", "SHAPE" to .65f, "GRAIN" to .08f),
        p(EmbouchureVoice.SOFT, "Held Effort", "EFFORT" to .45f, "LENGTH" to .65f, "HOLD" to 1f)
            .copy(gesture = EmbouchureGesture.LEANING_SWELL),
    )

    private fun grow(name: String, hold: Float) = EmbouchurePatch(
        name, EmbouchureVoice.BRASS,
        mapOf("TUNE" to .5f, "GROW" to 1f, "LENGTH" to .5f, "HOLD" to hold),
        velocity = .75f, model = Embouchure.BRASS_GROW_MODEL,
    )
    private val growPresets = listOf(grow("Brass GROW", 0f), grow("Brass GROW Held", 1f))

    /** The original bank and sixteen-pad starter retain their exact recipes and order. */
    fun legacy(): List<EmbouchurePatch> = presets
    fun forVoice(voice: EmbouchureVoice): List<EmbouchurePatch> = all().filter { it.voice == voice }
    fun all(): List<EmbouchurePatch> = presets + growPresets
}
