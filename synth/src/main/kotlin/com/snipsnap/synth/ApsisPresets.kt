package com.snipsnap.synth

/**
 * Six frozen PASSAGE recipes plus three starters each for GRAIN and ANCHOR.
 * The new voices begin at the study's native .65 strike level, with a distinct
 * movement variation and a held version. ANCHOR starts one octave lower.
 */
object ApsisPresets {
    private fun p(name: String, stretch: Float, distance: Float, bodies: Float, align: Float,
        couple: Float, hold: Float = 0f) = ApsisPatch(name, ApsisVoice.PASSAGE, mapOf(
        "STRETCH" to stretch, "DISTANCE" to distance, "BODIES" to bodies,
        "ALIGN" to align, "COUPLE" to couple, "HOLD" to hold,
    ))

    private val passage = listOf(
        p("Slow Apse", .88f, .95f, .0f, .25f, .10f),
        p("Close Rush", .98f, .05f, .0f, .85f, .40f),
        p("Ringing Bodies", .55f, .35f, 1f, .45f, .85f),
        p("Gathered Bloom", .80f, .40f, .80f, 1f, .65f),
        p("Spaced Replies", .80f, .40f, .80f, .0f, .65f),
        p("Held Return", .75f, .65f, .60f, .65f, .65f, hold = 1f),
    )

    private fun population(name: String, voice: ApsisVoice, overrides: Map<String, Float> = emptyMap(),
        midi: Int = Apsis.DEFAULT_MIDI) = ApsisPatch(name, voice, Apsis.defaults(voice) + overrides,
            midi = midi, velocity = .65f)

    private val grain = listOf(
        population("Grain Passage", ApsisVoice.GRAIN),
        population("Scattered Grains", ApsisVoice.GRAIN, mapOf("STRETCH" to .85f, "ALIGN" to .15f,
            "BODIES" to .7f, "COUPLE" to .65f)),
        population("Held Grains", ApsisVoice.GRAIN, mapOf("HOLD" to 1f)),
    )
    private val anchor = listOf(
        population("Anchor Bass", ApsisVoice.ANCHOR, midi = 48),
        population("Hollow Surge", ApsisVoice.ANCHOR, mapOf("STRETCH" to .8f, "DISTANCE" to .65f,
            "BODIES" to .2f, "COUPLE" to .65f), midi = 48),
        population("Held Anchor", ApsisVoice.ANCHOR, mapOf("HOLD" to 1f), midi = 48),
    )

    fun forVoice(voice: ApsisVoice): List<ApsisPatch> = when (voice) {
        ApsisVoice.PASSAGE -> passage
        ApsisVoice.GRAIN -> grain
        ApsisVoice.ANCHOR -> anchor
    }
    fun all(): List<ApsisPatch> = passage + grain + anchor
}
