package com.snipsnap.synth

/** Provisional listening recipes, deliberately outside the factory roster and phone picker. */
object TidalPresets {
    private fun p(voice: TidalVoice, name: String, vararg macros: Pair<String, Float>) =
        TidalPatch(name, voice, Tidal.defaults(voice) + macros)

    private val presets = listOf(
        p(TidalVoice.PAIR, "Two Bodies"),
        p(TidalVoice.PAIR, "Distant Company", "PASSAGE" to .05f, "BIND" to .85f, "TAIL" to .25f),
        p(TidalVoice.PAIR, "Shared Passage", "PASSAGE" to .75f, "BIND" to .45f, "TAIL" to .45f),
        p(TidalVoice.STREAM, "Long Counterarm"),
        p(TidalVoice.STREAM, "Inclined Threads", "TILT" to .85f, "PASSAGE" to .70f, "TAIL" to .85f),
        p(TidalVoice.STREAM, "After the Encounter", "APPROACH" to .25f, "PASSAGE" to .80f, "BIND" to .20f, "TAIL" to .95f),
    )

    fun forVoice(voice: TidalVoice): List<TidalPatch> = presets.filter { it.voice == voice }
    fun all(): List<TidalPatch> = presets
}
