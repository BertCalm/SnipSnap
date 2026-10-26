package com.snipsnap.synth

/**
 * Six dry current-model listening recipes at one root and strike level. The bank contrasts
 * orbital gestures before pitch or velocity changes: one distant body, a close rush,
 * a ringing population, gathered/staggered arrivals, and a recurring return.
 * The gathered pair differs only in ALIGN, so the timing comparison stays attributable.
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

    fun forVoice(@Suppress("UNUSED_PARAMETER") voice: ApsisVoice): List<ApsisPatch> = passage
    fun all(): List<ApsisPatch> = passage
}
