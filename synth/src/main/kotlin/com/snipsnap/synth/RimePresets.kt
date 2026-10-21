package com.snipsnap.synth

/** Provisional listening starters, deliberately outside the phone factory roster. */
object RimePresets {
    fun forVoice(voice: RimeVoice): List<RimePatch> = listOf(RimePatch(when (voice) {
        RimeVoice.LOOP -> "Round Seed"
        RimeVoice.GLINT -> "Fine Frost"
        RimeVoice.VESSEL -> "Open Vessel"
    }, voice))
    fun all(): List<RimePatch> = RimeVoice.entries.flatMap(::forVoice)
}
