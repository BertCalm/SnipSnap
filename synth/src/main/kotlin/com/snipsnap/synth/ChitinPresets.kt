package com.snipsnap.synth

/** Six candidate colors per voice after the owner's approval of the core audition.
 * These remain outside the factory roster until the focused bank is heard. */
object ChitinPresets {
    private fun p(v: ChitinVoice, name: String, bite: Float, color: Float, phase: Float,
                  morph: Float, pace: Float, hold: Float) = ChitinPatch(name, v,
        mapOf("TUNE" to .5f, "BITE" to bite, "COLOR" to color, "PHASE" to phase,
              "MORPH" to morph, "PACE" to pace, "HOLD" to hold))
    private val shell = listOf(
        p(ChitinVoice.SHELL,"FOUNDATION",.18f,.25f,.12f,.12f,.35f,.25f),
        p(ChitinVoice.SHELL,"HOLLOW STEP",.42f,.40f,.85f,.32f,.68f,.15f),
        p(ChitinVoice.SHELL,"SLOW ARMOR",.30f,.33f,.65f,.85f,.12f,.72f),
        p(ChitinVoice.SHELL,"RUBBER PULSE",.55f,.36f,.58f,.70f,.45f,.22f),
        p(ChitinVoice.SHELL,"EDGE CRAWL",.82f,.60f,.32f,.60f,.72f,.20f),
        p(ChitinVoice.SHELL,"PHASE RUN",.58f,.66f,1f,.92f,.95f,.35f),
    )
    private val wing = listOf(
        p(ChitinVoice.WING,"CLEAR SIGNAL",.28f,.52f,.15f,.22f,.55f,.18f),
        p(ChitinVoice.WING,"SYNC CRY",.92f,.68f,.42f,.80f,.75f,.20f),
        p(ChitinVoice.WING,"GLASS TURN",.62f,.80f,.80f,.50f,.45f,.32f),
        p(ChitinVoice.WING,"SLOW WINGS",.40f,.45f,.72f,.90f,.15f,.72f),
        p(ChitinVoice.WING,"METAL FLUTTER",.95f,.72f,.95f,1f,.98f,.48f),
        p(ChitinVoice.WING,"SOFT LANDING",.18f,.25f,.50f,.42f,.25f,.65f),
    )
    fun forVoice(voice: ChitinVoice): List<ChitinPatch> = when(voice) {
        ChitinVoice.SHELL -> shell
        ChitinVoice.WING -> wing
    }
    fun all(): List<ChitinPatch> = shell + wing
}
