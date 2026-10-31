package com.snipsnap.synth

/** Six dry, finite factory encounters with complete note, velocity, seed and model recipes. */
object TidalPresets {
    private fun p(voice: TidalVoice, name: String, vararg macros: Pair<String, Float>) =
        TidalPatch(name, voice, Tidal.defaults(voice) + macros)

    private val presets = listOf(
        p(TidalVoice.PAIR, "TWO BODIES"),
        p(TidalVoice.PAIR, "DISTANT PAIR", "PASSAGE" to .05f, "BIND" to .85f, "TAIL" to .25f),
        p(TidalVoice.PAIR, "SHARED PASSAGE", "PASSAGE" to .75f, "BIND" to .45f, "TAIL" to .45f),
        p(TidalVoice.STREAM, "COUNTERARM"),
        p(TidalVoice.STREAM, "TILTED THREADS", "TILT" to .85f, "PASSAGE" to .70f, "TAIL" to .85f),
        p(TidalVoice.STREAM, "AFTER PASSAGE", "APPROACH" to .25f, "PASSAGE" to .80f, "BIND" to .20f, "TAIL" to .95f),
    )

    fun forVoice(voice: TidalVoice): List<TidalPatch> = presets.filter { it.voice == voice }
    fun all(): List<TidalPatch> = presets
}
