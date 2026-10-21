package com.snipsnap.synth

/** The audition's two endpoints and middle blend, dry and otherwise unchanged. */
object OnsetPresets {
    private fun p(voice: OnsetVoice, name: String, blend: Float) =
        OnsetPatch(name, voice, Onset.defaults(voice) + ("BLEND" to blend))

    private val presets: List<OnsetPatch> by lazy {
        listOf(
            p(OnsetVoice.KICK, "PULSE KICK", 0f),
            p(OnsetVoice.KICK, "HYBRID KICK", .5f),
            p(OnsetVoice.KICK, "MEMBRANE KICK", 1f),
            p(OnsetVoice.SNARE, "MEMBRANE SNARE", 0f),
            p(OnsetVoice.SNARE, "HYBRID SNARE", .5f),
            p(OnsetVoice.SNARE, "FM SNARE", 1f),
            p(OnsetVoice.HAT_CLOSED, "SQUARE CLOSED", 0f),
            p(OnsetVoice.HAT_CLOSED, "HYBRID CLOSED", .5f),
            p(OnsetVoice.HAT_CLOSED, "RESONANT CL", 1f),
            p(OnsetVoice.HAT_OPEN, "SQUARE OPEN", 0f),
            p(OnsetVoice.HAT_OPEN, "HYBRID OPEN", .5f),
            p(OnsetVoice.HAT_OPEN, "RESONANT OPEN", 1f),
        )
    }

    fun forVoice(voice: OnsetVoice): List<OnsetPatch> = presets.filter { it.voice == voice }

    fun all(): List<OnsetPatch> = presets.toList()
}
