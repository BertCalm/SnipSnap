package com.snipsnap.synth

/**
 * The accepted acoustic anchors, with the same saved mechanical history.
 * BRUSH is the default. The four TINE choices remain separate profiles: no
 * winner or combined profile was selected during the Python listening work.
 * The first six were kept natively in accord-native-1. REED, COURSE, ESCAPEMENT
 * and BELL follow, appended: each is its voice's default recipe at C3, normal
 * touch, the render kept in accord-voices-1 (19 Nov 2026).
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
            AccordPatch("REED", AccordVoice.REED, Accord.defaults(AccordVoice.REED)),
            AccordPatch("COURSE", AccordVoice.COURSE, Accord.defaults(AccordVoice.COURSE)),
            AccordPatch("ESCAPEMENT", AccordVoice.ESCAPEMENT, Accord.defaults(AccordVoice.ESCAPEMENT)),
            AccordPatch("BELL", AccordVoice.BELL, Accord.defaults(AccordVoice.BELL)),
        )
    }

    fun all(): List<AccordPatch> = bank

    fun forVoice(voice: AccordVoice): List<AccordPatch> = bank.filter { it.voice == voice }

    fun default(): AccordPatch = bank.first()
}
