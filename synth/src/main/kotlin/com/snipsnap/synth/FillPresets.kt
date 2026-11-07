package com.snipsnap.synth

/**
 * FILL's provisional starting points, four per voice, one at each LENGTH. Every fill is cut for 120 BPM
 * apart from the two-bar ones, cut for 90. These stay outside the factory roster ([Presets]) until the
 * owner has heard R1.
 */
object FillPresets {
    private fun p(voice: FillVoice, name: String, bpm: Int, beats: Int, density: Float, rise: Float, land: Float) =
        FillPatch(name, voice, Fill.defaults(voice) + mapOf(
            "TEMPO" to Fill.tempoFor(bpm), "LENGTH" to Fill.lengthFor(beats),
            "DENSITY" to density, "RISE" to rise, "LAND" to land,
        ))

    private val snare = listOf(
        p(FillVoice.SNARE, "PICKUP ROLL", 120, 1, .5f, .4f, .6f),
        p(FillVoice.SNARE, "HALF BAR ROLL", 120, 2, .5f, .6f, .7f),
        p(FillVoice.SNARE, "BAR ROLL", 120, 4, .5f, .6f, .7f),
        p(FillVoice.SNARE, "LONG BUZZ", 90, 8, 1f, .9f, .9f),
    )
    private val toms = listOf(
        p(FillVoice.TOMS, "TOM PICKUP", 120, 1, 1f, .3f, .6f),
        p(FillVoice.TOMS, "HALF BAR TOMS", 120, 2, .5f, .5f, .7f),
        p(FillVoice.TOMS, "TOM WALK", 120, 4, .5f, .6f, .7f),
        p(FillVoice.TOMS, "SLOW TUMBLE", 90, 8, 0f, .8f, .9f),
    )

    fun forVoice(voice: FillVoice): List<FillPatch> = when (voice) {
        FillVoice.SNARE -> snare
        FillVoice.TOMS -> toms
    }

    fun all(): List<FillPatch> = snare + toms
}
