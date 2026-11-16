package com.snipsnap.synth

/** Accepted A/B anchors plus the six source voices the owner chose to keep. */
object BristlePresets {
    /** Paste complete UserPresets roster lines here; filter them by their saved voice. */
    private val promoted: List<BristlePatch> = listOf()

    fun forVoice(voice: Bristle.Route): List<BristlePatch> = listOf(BristlePatch(
        when (voice) {
            Bristle.Route.A -> "Filtered Edge"
            Bristle.Route.B -> "Driven Edge"
            Bristle.Route.ROUND -> "Round Floor"
            Bristle.Route.PULSE -> "Pulse Bite"
            Bristle.Route.HOLLOW -> "Hollow Box"
            Bristle.Route.REED -> "Reed Throat"
            Bristle.Route.WIRE -> "Wire Knock"
            Bristle.Route.FOLD -> "Fold Rasp"
        }, voice, Bristle.defaults)) + promoted.filter { it.voice == voice }
    fun all(): List<BristlePatch> = Bristle.Route.entries.flatMap(::forVoice)
}
