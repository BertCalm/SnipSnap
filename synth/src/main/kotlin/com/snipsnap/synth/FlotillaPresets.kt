package com.snipsnap.synth

/**
 * FLOTILLA's factory roster: fourteen sounds, authored from the measured engine
 * and not yet listened to. The audition (`./gradlew :synth:generateFlotillaAudition`)
 * is where a human decides which of these is a wake, a hull or a held current
 * and which is only a tone that measures like one.
 *
 * Every preset names all seven knobs. Pitch stays at C4 unless a kit pad moves
 * it: the note is not a macro. Names are plain words.
 */
object FlotillaPresets {

    private fun p(voice: FlotillaVoice, name: String, vararg macros: Pair<String, Float>) =
        FlotillaPatch(name, voice, macros.toMap())

    fun forVoice(voice: FlotillaVoice): List<FlotillaPatch> = when (voice) {
        FlotillaVoice.RIPPLE -> ripple
        FlotillaVoice.KNOCK -> knock
        FlotillaVoice.HOLLOW -> hollow
        FlotillaVoice.CROSSWAVE -> crosswave
        FlotillaVoice.DRIFT -> drift
        FlotillaVoice.GATHER -> gather
    }

    fun all(): List<FlotillaPatch> = FlotillaVoice.entries.flatMap { forVoice(it) }

    private val ripple = listOf(
        p(FlotillaVoice.RIPPLE, "Small Wake", "PULSE" to 0.20f, "CROSSING" to 0.15f, "FLOTILLA" to 0.30f, "VESSEL" to 0.35f, "SURFACE" to 0.25f, "SKIN" to 0.40f, "HOLD" to 0f),
        p(FlotillaVoice.RIPPLE, "Low Texture", "PULSE" to 0.12f, "CROSSING" to 0.10f, "FLOTILLA" to 0.20f, "VESSEL" to 0.30f, "SURFACE" to 0.05f, "SKIN" to 0.28f, "HOLD" to 0f),
    )

    private val knock = listOf(
        p(FlotillaVoice.KNOCK, "Open Wood", "PULSE" to 0.55f, "CROSSING" to 0.20f, "FLOTILLA" to 0.28f, "VESSEL" to 0.40f, "SURFACE" to 0.40f, "SKIN" to 0.32f, "HOLD" to 0f),
        p(FlotillaVoice.KNOCK, "Strong Pulse", "PULSE" to 0.95f, "CROSSING" to 0.35f, "FLOTILLA" to 0.50f, "VESSEL" to 0.35f, "SURFACE" to 0.62f, "SKIN" to 0.30f, "HOLD" to 0.10f),
        p(FlotillaVoice.KNOCK, "Brighter Wood", "PULSE" to 0.80f, "CROSSING" to 0.22f, "FLOTILLA" to 0.36f, "VESSEL" to 0.12f, "SURFACE" to 0.48f, "SKIN" to 0.22f, "HOLD" to 0f),
    )

    private val hollow = listOf(
        p(FlotillaVoice.HOLLOW, "Deep Cavity", "PULSE" to 0.28f, "CROSSING" to 0.32f, "FLOTILLA" to 0.38f, "VESSEL" to 0.92f, "SURFACE" to 0.36f, "SKIN" to 0.66f, "HOLD" to 0.15f),
        p(FlotillaVoice.HOLLOW, "Wide Bowl", "PULSE" to 0.22f, "CROSSING" to 0.18f, "FLOTILLA" to 0.30f, "VESSEL" to 1f, "SURFACE" to 0.22f, "SKIN" to 0.50f, "HOLD" to 0f),
    )

    private val crosswave = listOf(
        p(FlotillaVoice.CROSSWAVE, "Crossing Paths", "PULSE" to 0.45f, "CROSSING" to 0.80f, "FLOTILLA" to 0.50f, "VESSEL" to 0.45f, "SURFACE" to 0.60f, "SKIN" to 0.45f, "HOLD" to 0f),
        p(FlotillaVoice.CROSSWAVE, "Split Wake", "PULSE" to 0.55f, "CROSSING" to 1f, "FLOTILLA" to 0.55f, "VESSEL" to 0.40f, "SURFACE" to 0.75f, "SKIN" to 0.40f, "HOLD" to 0f),
    )

    private val drift = listOf(
        p(FlotillaVoice.DRIFT, "Gentle Current", "PULSE" to 0.08f, "CROSSING" to 0.40f, "FLOTILLA" to 0.28f, "VESSEL" to 0.50f, "SURFACE" to 0.22f, "SKIN" to 0.72f, "HOLD" to 0.35f),
        p(FlotillaVoice.DRIFT, "Warm Canopy", "PULSE" to 0.18f, "CROSSING" to 0.30f, "FLOTILLA" to 0.32f, "VESSEL" to 0.48f, "SURFACE" to 0.28f, "SKIN" to 0.95f, "HOLD" to 0.20f),
        p(FlotillaVoice.DRIFT, "Held Sparse", "PULSE" to 0.12f, "CROSSING" to 0.42f, "FLOTILLA" to 0.18f, "VESSEL" to 0.50f, "SURFACE" to 0.24f, "SKIN" to 0.78f, "HOLD" to 1f),
    )

    private val gather = listOf(
        p(FlotillaVoice.GATHER, "Gathered Vessels", "PULSE" to 0.70f, "CROSSING" to 0.65f, "FLOTILLA" to 0.80f, "VESSEL" to 0.60f, "SURFACE" to 0.70f, "SKIN" to 0.60f, "HOLD" to 0f),
        p(FlotillaVoice.GATHER, "Held Dense", "PULSE" to 0.72f, "CROSSING" to 0.70f, "FLOTILLA" to 0.95f, "VESSEL" to 0.62f, "SURFACE" to 0.75f, "SKIN" to 0.58f, "HOLD" to 1f),
    )
}
