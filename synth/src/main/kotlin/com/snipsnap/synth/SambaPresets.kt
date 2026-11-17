package com.snipsnap.synth

/** Native recipes from the auditioned Samba kit, retaining model and default controls. */
object SambaPresets {
    fun all(): List<SambaPatch> = SambaKits.patches()
    fun forVoice(voice: ParadeSamba.Voice) = all().filter { it.voice == voice }
}
