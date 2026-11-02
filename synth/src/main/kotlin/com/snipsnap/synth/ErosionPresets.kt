package com.snipsnap.synth

/** One provisional starting point per voice for listening, deliberately absent from the factory roster. */
object ErosionPresets {
    fun forVoice(voice: ErosionVoice): List<ErosionPatch> = listOf(
        ErosionPatch("${voice.name.lowercase().replaceFirstChar { it.uppercase() }} starting point", voice, Erosion.defaults(voice))
    )
    fun all() = ErosionVoice.entries.flatMap(::forVoice)
}
