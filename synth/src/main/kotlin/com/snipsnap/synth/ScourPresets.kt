package com.snipsnap.synth

/**
 * Two provisional starting points, one per voice at its defaults, for the R1 listen. They sit outside
 * the factory roster and the phone picker; named recipes wait until the voices have been heard.
 */
object ScourPresets {
    private val presets = ScourVoice.entries.map { voice ->
        ScourPatch("First ${voice.name.lowercase().replaceFirstChar { it.uppercase() }}", voice, Scour.defaults(voice))
    }

    fun forVoice(voice: ScourVoice): List<ScourPatch> = presets.filter { it.voice == voice }

    fun all(): List<ScourPatch> = presets
}
