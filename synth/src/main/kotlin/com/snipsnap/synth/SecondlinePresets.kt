package com.snipsnap.synth

/** Provisional recipes held outside the factory dispatcher while native kits are verified. */
object SecondlinePresets {
    private val promoted: List<SecondlinePatch> = emptyList()
    fun all() = SecondlineKits.Kit.entries.flatMap { SecondlineKits.patches(it) }.distinctBy { it.voice } + promoted
    fun forVoice(voice: ParadeSecondLine.Voice) = all().filter { it.voice==voice }
}
