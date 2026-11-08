package com.snipsnap.synth

/** Only the sixteen heard and MPC-approved settings; no speculative preset bank. */
object FiredBronzePresets {
    // Saved user sounds can be promoted here without changing the approved defaults.
    private val promoted: List<FiredBronzePatch> = listOf()

    fun forVoice(voice: FiredBronzeDryKit.Pad): List<FiredBronzePatch> =
        listOf(p(voice, voice.label)) + promoted.filter { it.voice == voice }

    fun all(): List<FiredBronzePatch> = FiredBronzeDryKit.Pad.entries.flatMap(::forVoice)

    private fun p(voice: FiredBronzeDryKit.Pad, name: String, vararg macros: Pair<String, Float>) =
        FiredBronzePatch(name, voice, FiredBronzeInstrument.defaults() + macros.toMap())
}
