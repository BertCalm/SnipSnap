package com.snipsnap.synth

/** Three complete, editable starting points. Every point and non-macro setting stays with the patch. */
object ScannedChainPresets {
    private val presets = listOf(
        ScannedChainPatch("Spring Pluck", ScannedChainVoice.PLUCK),
        ScannedChainPatch("Metallic Trace", ScannedChainVoice.METAL),
        ScannedChainPatch("Evolving Bloom", ScannedChainVoice.BLOOM),
    )

    fun all(): List<ScannedChainPatch> = presets
    fun forVoice(voice: ScannedChainVoice): List<ScannedChainPatch> = presets.filter { it.voice == voice }
}
