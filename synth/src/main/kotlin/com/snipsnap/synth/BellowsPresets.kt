package com.snipsnap.synth

/** Eight original dry starting points, two per instrument-inspired voice. */
object BellowsPresets {
    /** Keep the canonical promotion helper accepted by UserPresets' generated source lines. */
    private fun p(voice: BellowsVoice, name: String, vararg macros: Pair<String, Float>) =
        BellowsPatch(name, voice, Bellows.defaults(voice) + macros.toMap())
    private fun p(voice: BellowsVoice, name: String, gesture: BellowsGesture, vararg macros: Pair<String, Float>) =
        p(voice, name, *macros).copy(gesture = gesture)
    private val presets = listOf(
        p(BellowsVoice.CHAMBER, "Amber Breath", BellowsGesture.SWELL,
            "BODY" to .76f, "REGISTER" to .24f, "BEAT" to .24f, "HOLD" to .56f),
        p(BellowsVoice.CHAMBER, "Silver Ranks", BellowsGesture.STEADY,
            "BODY" to .34f, "REGISTER" to .82f, "BEAT" to .68f, "MOTION" to .18f, "HOLD" to .42f),
        p(BellowsVoice.COUNTER, "Midnight Pull", BellowsGesture.SWELL,
            "DIRECTION" to 0f, "REGISTER" to .26f, "PRESSURE" to .40f, "HOLD" to .47f),
        p(BellowsVoice.COUNTER, "Forward Push", BellowsGesture.ACCENT,
            "DIRECTION" to 1f, "REGISTER" to .73f, "PRESSURE" to .68f, "HOLD" to .28f),
        p(BellowsVoice.FOLK, "Village Stab", BellowsGesture.ACCENT,
            "PRESSURE" to .72f, "REGISTER" to .78f, "BODY" to .56f, "HOLD" to .10f),
        p(BellowsVoice.FOLK, "Dancing Reeds", BellowsGesture.PULSE,
            "MOTION" to .76f, "BEAT" to .74f, "BODY" to .32f, "HOLD" to .53f),
        p(BellowsVoice.WHEEL, "Quiet Wheel", BellowsGesture.STEADY,
            "PRESSURE" to .36f, "DRONE" to .72f, "BUZZ" to .28f, "MOTION" to .16f, "HOLD" to .58f),
        p(BellowsVoice.WHEEL, "Wrist Sparks", BellowsGesture.PULSE,
            "PRESSURE" to .84f, "DRONE" to .42f, "BUZZ" to .88f, "MOTION" to .66f, "HOLD" to .61f),
    )
    fun forVoice(voice: BellowsVoice): List<BellowsPatch> = presets.filter { it.voice == voice }
    fun all(): List<BellowsPatch> = presets
}
