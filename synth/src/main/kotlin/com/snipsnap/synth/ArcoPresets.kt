package com.snipsnap.synth

/**
 * ARCO's factory roster. Provisional: placeholders while the engine is drawn; the real eight per voice
 * are authored by ear against the built engine and are provisional until the audition gate.
 */
object ArcoPresets {

    private fun p(voice: ArcoVoice, name: String, vararg macros: Pair<String, Float>) =
        ArcoPatch(name, voice, macros.toMap())

    fun forVoice(voice: ArcoVoice): List<ArcoPatch> = when (voice) {
        ArcoVoice.CELLO -> celloPresets
        ArcoVoice.ERHU -> erhuPresets
    }

    fun all(): List<ArcoPatch> = ArcoVoice.entries.flatMap { forVoice(it) }

    private val celloPresets = listOf(
        p(ArcoVoice.CELLO, "SLOW BOW", "TUNE" to 0.5f, "BOW" to 0.0f, "GRIP" to 0.6f, "BODY" to 0.5f, "HOLD" to 0.6f),
        p(ArcoVoice.CELLO, "SHORT STAB", "TUNE" to 0.5f, "BOW" to 1.0f, "GRIP" to 0.7f, "BODY" to 0.5f, "HOLD" to 0.0f),
    )

    private val erhuPresets = listOf(
        p(ArcoVoice.ERHU, "NASAL LINE", "TUNE" to 0.5f, "BOW" to 0.5f, "GRIP" to 0.6f, "BODY" to 0.6f, "HOLD" to 0.5f),
        p(ArcoVoice.ERHU, "HIGH CRY", "TUNE" to 0.8f, "BOW" to 0.6f, "GRIP" to 0.8f, "BODY" to 0.4f, "HOLD" to 0.3f),
    )
}
