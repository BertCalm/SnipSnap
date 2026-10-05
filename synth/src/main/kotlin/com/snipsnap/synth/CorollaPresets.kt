package com.snipsnap.synth

/**
 * Twelve complete COROLLA recipes: two per voice, including two settled held textures.
 * The plain names fit the factory strip's fourteen-character limit. The roster is provisional
 * until the owner hears the audition; measurements cannot choose the musical settings.
 */
object CorollaPresets {
    private fun p(voice: CorollaVoice, name: String, vararg macros: Pair<String, Float>) =
        CorollaPatch(name, voice, macros.toMap())

    fun forVoice(voice: CorollaVoice): List<CorollaPatch> = when (voice) {
        CorollaVoice.TONGUE -> tongue
        CorollaVoice.BLOSSOM -> blossom
        CorollaVoice.CHOIR -> choir
        CorollaVoice.CHATTER -> chatter
        CorollaVoice.ORBIT -> orbit
        CorollaVoice.HUSK -> husk
    }

    fun all(): List<CorollaPatch> = CorollaVoice.entries.flatMap { forVoice(it) }

    private val tongue = listOf(
        p(CorollaVoice.TONGUE, "FIRST PETAL", "TUNE" to 0.5f, "PULL" to 0.45f, "BLOOM" to 0.25f, "FIELD" to 0.10f, "CONTACT" to 0.10f, "CHAMBER" to 0.30f, "HOLD" to 0f),
        p(CorollaVoice.TONGUE, "SOFT ALLOY", "TUNE" to 0.5f, "PULL" to 0.18f, "BLOOM" to 0.42f, "FIELD" to 0f, "CONTACT" to 0f, "CHAMBER" to 0.48f, "HOLD" to 0f),
    )
    private val blossom = listOf(
        p(CorollaVoice.BLOSSOM, "OPENING BELL", "TUNE" to 0.5f, "PULL" to 0.65f, "BLOOM" to 0.82f, "FIELD" to 0.28f, "CONTACT" to 0.18f, "CHAMBER" to 0.58f, "HOLD" to 0f),
        p(CorollaVoice.BLOSSOM, "FOLDED CHAMBER", "TUNE" to 0.5f, "PULL" to 0.48f, "BLOOM" to 0.12f, "FIELD" to 0.18f, "CONTACT" to 0.32f, "CHAMBER" to 0.88f, "HOLD" to 0f),
    )
    private val choir = listOf(
        p(CorollaVoice.CHOIR, "MAGNETIC CHOIR", "TUNE" to 0.5f, "PULL" to 0.32f, "BLOOM" to 0.58f, "FIELD" to 0.62f, "CONTACT" to 0.12f, "CHAMBER" to 0.72f, "HOLD" to 0f),
        p(CorollaVoice.CHOIR, "PETAL CLOUD", "TUNE" to 0.5f, "PULL" to 0.24f, "BLOOM" to 0.76f, "FIELD" to 0.48f, "CONTACT" to 0.08f, "CHAMBER" to 0.64f, "HOLD" to 1f),
    )
    private val chatter = listOf(
        p(CorollaVoice.CHATTER, "QUIET CHATTER", "TUNE" to 0.5f, "PULL" to 0.38f, "BLOOM" to 0.56f, "FIELD" to 0.32f, "CONTACT" to 0.38f, "CHAMBER" to 0.54f, "HOLD" to 0f),
        p(CorollaVoice.CHATTER, "BRIGHT CONTACT", "TUNE" to 0.5f, "PULL" to 0.78f, "BLOOM" to 0.22f, "FIELD" to 0.62f, "CONTACT" to 0.90f, "CHAMBER" to 0.24f, "HOLD" to 0f),
    )
    private val orbit = listOf(
        p(CorollaVoice.ORBIT, "SLOW ORBIT", "TUNE" to 0.5f, "PULL" to 0.30f, "BLOOM" to 0.72f, "FIELD" to 0.28f, "CONTACT" to 0.08f, "CHAMBER" to 0.58f, "HOLD" to 0f),
        p(CorollaVoice.ORBIT, "FAST ORBIT", "TUNE" to 0.5f, "PULL" to 0.52f, "BLOOM" to 0.46f, "FIELD" to 0.92f, "CONTACT" to 0.14f, "CHAMBER" to 0.32f, "HOLD" to 0f),
    )
    private val husk = listOf(
        p(CorollaVoice.HUSK, "HOLLOW HUSK", "TUNE" to 0.25f, "PULL" to 0.40f, "BLOOM" to 0.16f, "FIELD" to 0.26f, "CONTACT" to 0.28f, "CHAMBER" to 0.94f, "HOLD" to 0f),
        p(CorollaVoice.HUSK, "CLOSED CIRCUIT", "TUNE" to 0.25f, "PULL" to 0.32f, "BLOOM" to 0.10f, "FIELD" to 0.66f, "CONTACT" to 0.48f, "CHAMBER" to 0.88f, "HOLD" to 1f),
    )
}
