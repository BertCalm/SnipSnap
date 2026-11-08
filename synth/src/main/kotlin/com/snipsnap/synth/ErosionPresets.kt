package com.snipsnap.synth

/** The eight accepted contrast recipes, shared with the playing bank without retuning. */
object ErosionPresets {
    /** Paste complete UserPresets roster lines here; the accepted kit stays fixed. */
    private val promoted: List<ErosionPatch> = listOf()

    fun forVoice(voice: ErosionVoice): List<ErosionPatch> = all().filter { it.voice == voice }
    fun all(): List<ErosionPatch> = ErosionBank.entries().map { it.patch } + promoted
}
