package com.snipsnap.synth

/**
 * The factory bank (round K2): every recipe is a setting the owner heard in the accepted round-3 audition
 * (docs/ETCH.md), each voice's default note and its
 * line loop, plus the audition's two chain lines, line B's busy settings on SAW and line A's sparse, dark settings on
 * SQUARE. Names describe the sound; "acid" and the box's model number are on the preset blocklist.
 */
object EtchPresets {
    /** Paste complete UserPresets roster lines here; the heard round-3 settings stay fixed. */
    private val promoted: List<EtchPatch> = listOf()

    fun forVoice(voice: EtchVoice): List<EtchPatch> = factory(voice) + promoted.filter { it.voice == voice }

    private fun factory(voice: EtchVoice): List<EtchPatch> {
        val d = Etch.defaults(voice)
        val base = listOf(
            EtchPatch("Note", voice, d),
            EtchPatch("Line", voice, d + ("HOLD" to 1f)),
        )
        return base + when (voice) {
            EtchVoice.SAW -> listOf(EtchPatch("Busy line", voice,
                d + mapOf("CUTOFF" to .3f, "PEAK" to .8f, "SWEEP" to .6f, "BITE" to .5f, "SPACE" to .32f, "HOLD" to 1f)))
            EtchVoice.SQUARE -> listOf(EtchPatch("Sparse line", voice,
                d + mapOf("CUTOFF" to .32f, "PEAK" to .62f, "SWEEP" to .45f, "BITE" to .4f, "SPACE" to .55f, "HOLD" to 1f)))
            else -> emptyList()
        }
    }

    fun all(): List<EtchPatch> = EtchVoice.entries.flatMap(::forVoice)
}
