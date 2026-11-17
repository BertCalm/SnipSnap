package com.snipsnap.synth

/** Provisional native recipes for audition and kit verification; outside the factory roster. */
object SambaPresets {
    fun all(): List<SambaPatch> = SambaKits.patches()
    fun forVoice(voice: ParadeSamba.Voice) = all().filter { it.voice == voice }
}
