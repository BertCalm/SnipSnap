package com.snipsnap.synth

/** Native recipes from the auditioned Samba kit, retaining model and default controls. */
object SambaPresets {
    private val promoted: List<SambaPatch> = emptyList()
    fun all(): List<SambaPatch> = SambaKits.patches() + promoted
    fun forVoice(voice: ParadeSamba.Voice) = all().filter { it.voice == voice }
}
