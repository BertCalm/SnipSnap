package com.snipsnap.synth

/**
 * PASSAGE's three are provisional references: the aligned sound and MATCH are approved; LOSS awaits listening. Each voice
 * kept on 21 Nov 2026 has one recipe, its default settings at C3 and full touch: the note the owner heard and kept.
 */
object FerryPresets {
    private val kept = mapOf(
        FerryVoice.TINE to "Kalimba",
        FerryVoice.TWIN to "Double String",
        FerryVoice.SWELL to "Bowed Swell",
        FerryVoice.TARAB to "Sitar",
        FerryVoice.PEAL to "Handbell",
        FerryVoice.BATON to "Wood Wire Glass",
        FerryVoice.VANE to "Vibraphone",
    )

    fun forVoice(voice: FerryVoice): List<FerryPatch> = when (voice) {
        FerryVoice.PASSAGE -> listOf(
            FerryPatch("Passage", voice, Ferry.defaults(voice)),
            FerryPatch("Slow passage", voice, Ferry.defaults(voice) + ("HANDOFF" to .25f)),
            FerryPatch("Fast passage", voice, Ferry.defaults(voice) + ("HANDOFF" to 1f)),
        )
        else -> kept[voice]?.let { listOf(FerryPatch(it, voice, Ferry.defaults(voice))) } ?: emptyList()
    }
    fun all(): List<FerryPatch> = Ferry.KEPT_VOICES.flatMap(::forVoice)
}
