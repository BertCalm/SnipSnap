package com.snipsnap.synth

/** Provisional references. The aligned Kotlin sound is approved; MATCH awaits listening. */
object FerryPresets {
    fun forVoice(voice: FerryVoice): List<FerryPatch> = listOf(
        FerryPatch("Passage", voice, Ferry.defaults(voice)),
        FerryPatch("Slow passage", voice, Ferry.defaults(voice) + ("HANDOFF" to .25f)),
        FerryPatch("Fast passage", voice, Ferry.defaults(voice) + ("HANDOFF" to 1f)),
    )
    fun all(): List<FerryPatch> = FerryVoice.entries.flatMap(::forVoice)
}
