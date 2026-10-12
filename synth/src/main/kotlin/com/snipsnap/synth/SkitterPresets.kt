package com.snipsnap.synth

/**
 * Thirty-six dry circuit recipes, from a clean oscillator body to repeated
 * recovery and noise-provoked flutter. The names and settings are provisional
 * until the published audition receives the owner's listening verdict.
 */
object SkitterPresets {
    private fun p(voice: SkitterVoice, name: String, vararg macros: Pair<String, Float>) =
        SkitterPatch(name, voice, Skitter.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(SkitterVoice.BASS, "Recovering Edge"),
        p(SkitterVoice.BASS, "Quiet Circuit", "BUZZ" to .20f, "SKITTER" to 0f, "AGITATE" to .12f, "STATIC" to 0f),
        p(SkitterVoice.BASS, "Unequal Return", "BUZZ" to .45f, "SKITTER" to .48f, "AGITATE" to .42f, "STATIC" to .06f),
        p(SkitterVoice.BASS, "Paper Buzz", "BUZZ" to .72f, "SKITTER" to .16f, "AGITATE" to .24f, "VENT" to .18f, "STATIC" to .04f),
        p(SkitterVoice.BASS, "Soft Sputter", "BUZZ" to .30f, "SKITTER" to .30f, "AGITATE" to .52f, "STATIC" to .24f),
        p(SkitterVoice.BASS, "Low Aftershock", "SKITTER" to .68f, "AGITATE" to .72f, "VENT" to .16f, "STATIC" to .18f),
        p(SkitterVoice.BASS, "Bare Current", "BUZZ" to .12f, "SKITTER" to 0f, "AGITATE" to 0f, "VENT" to 0f, "STATIC" to 0f),
        p(SkitterVoice.BASS, "Held Circuit", "BUZZ" to .38f, "SKITTER" to .24f, "AGITATE" to .35f, "STATIC" to .08f, "HOLD" to 1f),

        p(SkitterVoice.LEAD, "Tearing Tone"),
        p(SkitterVoice.LEAD, "Clean Wire", "BUZZ" to .48f, "SKITTER" to .08f, "AGITATE" to .12f, "STATIC" to 0f),
        p(SkitterVoice.LEAD, "Nervous Wire", "BUZZ" to .76f, "SKITTER" to .62f, "AGITATE" to .67f, "STATIC" to .17f),
        p(SkitterVoice.LEAD, "Bright Return", "BUZZ" to .92f, "SKITTER" to .35f, "AGITATE" to .32f, "VENT" to .34f, "STATIC" to .06f),
        p(SkitterVoice.LEAD, "Open Vent", "BUZZ" to .58f, "SKITTER" to .28f, "AGITATE" to .44f, "VENT" to .85f, "STATIC" to .13f),
        p(SkitterVoice.LEAD, "Bent Signal", "SKITTER" to .85f, "AGITATE" to .56f, "VENT" to .20f, "STATIC" to .12f),
        p(SkitterVoice.LEAD, "Hot Contact", "BUZZ" to 1f, "SKITTER" to .72f, "AGITATE" to .88f, "VENT" to .44f, "STATIC" to .35f),
        p(SkitterVoice.LEAD, "Held Wire", "BUZZ" to .62f, "SKITTER" to .40f, "AGITATE" to .50f, "STATIC" to .12f, "HOLD" to 1f),

        p(SkitterVoice.HOLLOW, "Open Interval"),
        p(SkitterVoice.HOLLOW, "Empty Coil", "BUZZ" to .16f, "SKITTER" to .08f, "AGITATE" to .10f, "VENT" to .44f, "STATIC" to 0f),
        p(SkitterVoice.HOLLOW, "Nasal Current", "BUZZ" to .52f, "SKITTER" to .28f, "AGITATE" to .34f, "VENT" to .62f, "STATIC" to .07f),
        p(SkitterVoice.HOLLOW, "Uneven Chamber", "SKITTER" to .62f, "AGITATE" to .56f, "VENT" to .48f, "STATIC" to .18f),
        p(SkitterVoice.HOLLOW, "Window Static", "BUZZ" to .38f, "SKITTER" to .24f, "AGITATE" to .64f, "VENT" to .74f, "STATIC" to .48f),
        p(SkitterVoice.HOLLOW, "Held Interval", "SKITTER" to .26f, "AGITATE" to .32f, "STATIC" to .12f, "HOLD" to 1f),

        p(SkitterVoice.FLUTTER, "Repeated Departure"),
        p(SkitterVoice.FLUTTER, "Small Tremble", "BUZZ" to .32f, "SKITTER" to .22f, "AGITATE" to .35f, "STATIC" to .05f),
        p(SkitterVoice.FLUTTER, "Aftershock", "BUZZ" to .62f, "SKITTER" to .48f, "AGITATE" to .88f, "VENT" to .34f, "STATIC" to .15f),
        p(SkitterVoice.FLUTTER, "Unequal Pulse", "BUZZ" to .75f, "SKITTER" to .80f, "AGITATE" to .62f, "VENT" to .48f, "STATIC" to .12f),
        p(SkitterVoice.FLUTTER, "Restless Vent", "BUZZ" to .64f, "SKITTER" to .72f, "AGITATE" to .90f, "VENT" to .80f, "STATIC" to .40f),
        p(SkitterVoice.FLUTTER, "Held Flutter", "SKITTER" to .55f, "AGITATE" to .70f, "STATIC" to .20f, "HOLD" to 1f),

        p(SkitterVoice.CHIRP, "Pitched Chirp"),
        p(SkitterVoice.CHIRP, "Quiet Resonance", "BUZZ" to .28f, "SKITTER" to .10f, "AGITATE" to .36f, "VENT" to .56f, "STATIC" to .08f),
        p(SkitterVoice.CHIRP, "Provoked Signal", "BUZZ" to .62f, "SKITTER" to .45f, "AGITATE" to .90f, "VENT" to .76f, "STATIC" to .56f),
        p(SkitterVoice.CHIRP, "Held Chirp", "SKITTER" to .32f, "AGITATE" to .66f, "STATIC" to .30f, "HOLD" to 1f),

        p(SkitterVoice.FRAY, "Agitated Texture"),
        p(SkitterVoice.FRAY, "Soft Fray", "BUZZ" to .52f, "SKITTER" to .32f, "AGITATE" to .48f, "VENT" to .35f, "STATIC" to .26f),
        p(SkitterVoice.FRAY, "Overloaded Nerves", "BUZZ" to 1f, "SKITTER" to 1f, "AGITATE" to 1f, "VENT" to .68f, "STATIC" to 1f),
        p(SkitterVoice.FRAY, "Frayed Sustain", "BUZZ" to .82f, "SKITTER" to .58f, "AGITATE" to .78f, "STATIC" to .55f, "HOLD" to 1f),
    )

    fun forVoice(voice: SkitterVoice): List<SkitterPatch> = presets.filter { it.voice == voice }

    fun all(): List<SkitterPatch> = presets
}
