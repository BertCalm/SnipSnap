package com.snipsnap.synth

/**
 * Six dry starting recordings for the first listening review. The proposed 36-preset library
 * waits for the owner's approval of these four clear families and two experimental gestures.
 */
object SpoolPresets {
    private fun p(voice: SpoolVoice, name: String, vararg macros: Pair<String, Float>) =
        SpoolPatch(name, voice, Spool.defaults(voice) + macros.toMap())

    private val presets by lazy {
        listOf(
            p(SpoolVoice.STRING, "SOFT FRAME", "TUNE" to 0.5f, "HEAD" to 0.15f, "STRAIN" to 0.10f, "RETURN" to 0.20f, "IMPRINT" to 0.15f, "SPLICE" to 0.05f, "HOLD" to 0f),
            p(SpoolVoice.CHOIR, "RETURNING CHOIR", "TUNE" to 0.5f, "HEAD" to 0.25f, "STRAIN" to 0.15f, "RETURN" to 0.30f, "IMPRINT" to 0.20f, "SPLICE" to 0.05f, "HOLD" to 0f),
            p(SpoolVoice.FLUTE, "FLUTE MEMORY", "TUNE" to 0.5f, "HEAD" to 0.20f, "STRAIN" to 0.10f, "RETURN" to 0.25f, "IMPRINT" to 0.15f, "SPLICE" to 0.10f, "HOLD" to 0f),
            p(SpoolVoice.BRASS, "WORN BRASS", "TUNE" to 0.5f, "HEAD" to 0.20f, "STRAIN" to 0.15f, "RETURN" to 0.20f, "IMPRINT" to 0.15f, "SPLICE" to 0.10f, "HOLD" to 0f),
            p(SpoolVoice.PALIMPSEST, "GHOST COPY", "TUNE" to 0.5f, "HEAD" to 0.65f, "STRAIN" to 0.40f, "RETURN" to 0.65f, "IMPRINT" to 0.70f, "SPLICE" to 0.25f, "HOLD" to 0f),
            p(SpoolVoice.CUT, "SHORT SPLICE", "TUNE" to 0.5f, "HEAD" to 0.55f, "STRAIN" to 0.35f, "RETURN" to 0.50f, "IMPRINT" to 0.45f, "SPLICE" to 0.75f, "HOLD" to 0f),
        )
    }

    fun forVoice(voice: SpoolVoice): List<SpoolPatch> = presets.filter { it.voice == voice }
    fun all(): List<SpoolPatch> = presets
}
