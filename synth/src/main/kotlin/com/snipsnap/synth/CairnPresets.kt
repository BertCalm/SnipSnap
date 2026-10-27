package com.snipsnap.synth

/** Two provisional starting points for listening, deliberately absent from the factory roster. */
object CairnPresets {
    fun forVoice(voice: CairnVoice): List<CairnPatch> = listOf(
        CairnPatch("${voice.name.lowercase().replaceFirstChar { it.uppercase() }} starting point", voice, Cairn.defaults(voice))
    )
    fun all() = CairnVoice.entries.flatMap(::forVoice)
}
