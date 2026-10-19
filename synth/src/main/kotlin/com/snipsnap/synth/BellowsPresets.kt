package com.snipsnap.synth

/** Twenty-four dry model-2 recipes: six musical roles per instrument-inspired voice. */
object BellowsPresets {
    /** Keep the canonical promotion helper accepted by UserPresets' generated source lines. */
    private fun p(voice: BellowsVoice, name: String, vararg macros: Pair<String, Float>) =
        BellowsPatch(name, voice, Bellows.defaults(voice) + macros.toMap(), model = 2)
    private fun p(voice: BellowsVoice, name: String, gesture: BellowsGesture, vararg macros: Pair<String, Float>) =
        p(voice, name, *macros).copy(gesture = gesture)
    private val presets = listOf(
        // Preserve the first audition's eight names, recipes and global/per-voice indices.
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

        // Direct soft reeds, a deeply enclosed note, a concise accent and a long swell.
        p(BellowsVoice.CHAMBER, "Bare Reed", BellowsGesture.STEADY,
            "PRESSURE" to .28f, "BODY" to 0f, "MOTION" to .06f, "HOLD" to .48f,
            "REGISTER" to .18f, "BEAT" to 0f),
        p(BellowsVoice.CHAMBER, "Chest Tone", BellowsGesture.STEADY,
            "PRESSURE" to .46f, "BODY" to .92f, "MOTION" to .12f, "HOLD" to .65f,
            "REGISTER" to .30f, "BEAT" to .12f),
        p(BellowsVoice.CHAMBER, "Reed Tap", BellowsGesture.ACCENT,
            "PRESSURE" to .76f, "BODY" to .20f, "MOTION" to .06f, "HOLD" to .08f,
            "REGISTER" to .72f, "BEAT" to .12f),
        p(BellowsVoice.CHAMBER, "Slow Bloom", BellowsGesture.SWELL,
            "PRESSURE" to .58f, "BODY" to .55f, "MOTION" to .48f, "HOLD" to .82f,
            "REGISTER" to .48f, "BEAT" to .46f),

        // DIRECTION selects a timbre throughout the note; PULSE moves pressure, not direction.
        p(BellowsVoice.COUNTER, "Paper Reed", BellowsGesture.STEADY,
            "PRESSURE" to .26f, "BODY" to .16f, "MOTION" to .08f, "HOLD" to .64f,
            "REGISTER" to .18f, "DIRECTION" to .10f),
        p(BellowsVoice.COUNTER, "Round Push", BellowsGesture.STEADY,
            "PRESSURE" to .38f, "BODY" to .84f, "MOTION" to .10f, "HOLD" to .55f,
            "REGISTER" to .38f, "DIRECTION" to .85f),
        p(BellowsVoice.COUNTER, "Reed Knock", BellowsGesture.ACCENT,
            "PRESSURE" to .67f, "BODY" to .28f, "MOTION" to .12f, "HOLD" to .08f,
            "REGISTER" to .54f, "DIRECTION" to .40f),
        p(BellowsVoice.COUNTER, "Reed Tread", BellowsGesture.PULSE,
            "PRESSURE" to .58f, "BODY" to .36f, "MOTION" to .64f, "HOLD" to .76f,
            "REGISTER" to .56f, "DIRECTION" to .52f),

        // Folk reeds range from a quiet rounded note through an enclosed body to open phrases.
        p(BellowsVoice.FOLK, "Soft Grain", BellowsGesture.STEADY,
            "PRESSURE" to .28f, "BODY" to .20f, "MOTION" to .08f, "HOLD" to .48f,
            "REGISTER" to .26f, "BEAT" to .10f),
        p(BellowsVoice.FOLK, "Wooden Chest", BellowsGesture.STEADY,
            "PRESSURE" to .52f, "BODY" to .90f, "MOTION" to .12f, "HOLD" to .50f,
            "REGISTER" to .32f, "BEAT" to .18f),
        p(BellowsVoice.FOLK, "Reed Reply", BellowsGesture.ACCENT,
            "PRESSURE" to .56f, "BODY" to .12f, "MOTION" to .10f, "HOLD" to .24f,
            "REGISTER" to .36f, "BEAT" to .18f),
        p(BellowsVoice.FOLK, "Harvest Air", BellowsGesture.SWELL,
            "PRESSURE" to .62f, "BODY" to .46f, "MOTION" to .40f, "HOLD" to .80f,
            "REGISTER" to .58f, "BEAT" to .58f),

        // A clean solo makes the wheel melody usable without an accompanying drone or buzz.
        p(BellowsVoice.WHEEL, "Solo Thread", BellowsGesture.STEADY,
            "PRESSURE" to .48f, "BODY" to .20f, "MOTION" to .08f, "HOLD" to .72f,
            "DRONE" to 0f, "BUZZ" to 0f),
        p(BellowsVoice.WHEEL, "Hollow Bow", BellowsGesture.STEADY,
            "PRESSURE" to .45f, "BODY" to .92f, "MOTION" to .10f, "HOLD" to .50f,
            "DRONE" to .22f, "BUZZ" to .10f),
        p(BellowsVoice.WHEEL, "Fifth Breath", BellowsGesture.SWELL,
            "PRESSURE" to .55f, "BODY" to .40f, "MOTION" to .30f, "HOLD" to .84f,
            "DRONE" to .90f, "BUZZ" to .12f),
        p(BellowsVoice.WHEEL, "Bridge Tap", BellowsGesture.ACCENT,
            "PRESSURE" to .70f, "BODY" to .22f, "MOTION" to .22f, "HOLD" to .15f,
            "DRONE" to .12f, "BUZZ" to .78f),
    )
    fun forVoice(voice: BellowsVoice): List<BellowsPatch> = presets.filter { it.voice == voice }
    fun all(): List<BellowsPatch> = presets
}
