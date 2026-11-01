package com.snipsnap.synth

/**
 * The six accepted acoustic anchors, with the same saved mechanical history.
 * BRUSH is the default. The four TINE choices remain separate profiles: no
 * winner or combined profile was selected during the Python listening work.
 * Native rendering and the proposed control domains still require listening.
 */
object AccordPresets {
    private val bank: List<AccordPatch> by lazy {
        listOf(
            AccordPatch("BRUSH", AccordVoice.BRUSH, Accord.defaults(AccordVoice.BRUSH)),
            AccordPatch("WOOD", AccordVoice.WOOD, Accord.defaults(AccordVoice.WOOD)),
            AccordPatch("REFERENCE", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
                acousticProfile = AccordAcousticProfile.REFERENCE),
            AccordPatch("SOFT ATTACK", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
                acousticProfile = AccordAcousticProfile.SOFT_ATTACK),
            AccordPatch("BRIGHT EDGE", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
                acousticProfile = AccordAcousticProfile.BRIGHT_EDGE),
            AccordPatch("LONG RING", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
                acousticProfile = AccordAcousticProfile.LONG_RING),
        )
    }

    fun all(): List<AccordPatch> = bank

    fun forVoice(voice: AccordVoice): List<AccordPatch> = bank.filter { it.voice == voice }

    fun default(): AccordPatch = bank.first()
}
