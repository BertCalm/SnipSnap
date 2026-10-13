package com.snipsnap.synth

/** Eight original dry identities for the first audition. Expansion waits for the owner's ears. */
object EmbouchurePresets {
    private fun p(voice: EmbouchureVoice, name: String, vararg macros: Pair<String, Float>) =
        EmbouchurePatch(name, voice, Embouchure.defaults(voice) + macros.toMap())

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

    fun forVoice(voice: EmbouchureVoice): List<EmbouchurePatch> = presets.filter { it.voice == voice }
    fun all(): List<EmbouchurePatch> = presets
}
