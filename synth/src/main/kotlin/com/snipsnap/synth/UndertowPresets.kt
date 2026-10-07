package com.snipsnap.synth

/**
 * Twelve dry suction-shell gestures, two for each voice. Every recipe names all seven controls;
 * the shell itself supplies the ceramic catch, breath and chamber response. HELD SUCTION contains
 * settled powered material without the first catch. These settings await the owner's audition.
 */
object UndertowPresets {
    // SAVE AS PRESET emits this helper shape for adding an auditioned recipe to the roster.
    private fun p(voice: UndertowVoice, name: String, vararg macros: Pair<String, Float>) =
        UndertowPatch(name, voice, macros.toMap())

    private fun sound(
        voice: UndertowVoice,
        name: String,
        semitone: Int,
        draw: Float,
        flap: Float,
        weight: Float,
        spiral: Float,
        leak: Float,
        hold: Float = 0f,
    ) = p(voice, name,
        "TUNE" to semitone / Undertow.TUNE_SEMITONES.toFloat(),
        "DRAW" to draw,
        "FLAP" to flap,
        "WEIGHT" to weight,
        "SPIRAL" to spiral,
        "LEAK" to leak,
        "HOLD" to hold,
    )

    fun forVoice(voice: UndertowVoice): List<UndertowPatch> = when (voice) {
        UndertowVoice.KNOCK -> knock
        UndertowVoice.BREATH -> breath
        UndertowVoice.FLUTTER -> flutter
        UndertowVoice.SEAL -> seal
        UndertowVoice.HOLLOW -> hollow
        UndertowVoice.SURGE -> surge
    }

    fun all(): List<UndertowPatch> = UndertowVoice.entries.flatMap { forVoice(it) }

    private val knock = listOf(
        sound(UndertowVoice.KNOCK, "FIRST DRAW", 0, .45f, .30f, .60f, .35f, .55f),
        sound(UndertowVoice.KNOCK, "CERAMIC RIM", 12, .62f, .24f, .72f, .40f, .38f),
    )
    private val breath = listOf(
        sound(UndertowVoice.BREATH, "SOFT INLET", 7, .38f, .40f, .28f, .42f, .58f),
        sound(UndertowVoice.BREATH, "HOLLOW BREATH", 0, .55f, .40f, .35f, .70f, .40f),
    )
    private val flutter = listOf(
        sound(UndertowVoice.FLUTTER, "LOOSE LEATHER", 5, .55f, .90f, .30f, .45f, .42f),
        sound(UndertowVoice.FLUTTER, "FLUTTER CHAMBER", 12, .70f, .80f, .50f, .68f, .28f),
    )
    private val seal = listOf(
        sound(UndertowVoice.SEAL, "ALTERNATING SEAL", 7, .70f, .62f, .60f, .65f, .16f),
        sound(UndertowVoice.SEAL, "GENTLE BYPASS", 12, .42f, .45f, .42f, .50f, .78f),
    )
    private val hollow = listOf(
        sound(UndertowVoice.HOLLOW, "DEEP SPIRAL", 0, .50f, .45f, .65f, .90f, .30f),
        sound(UndertowVoice.HOLLOW, "HEAVY CATCH", 3, .65f, .38f, .88f, .75f, .40f),
    )
    private val surge = listOf(
        sound(UndertowVoice.SURGE, "RETURNING AIR", 5, .85f, .65f, .70f, .65f, .35f),
        sound(UndertowVoice.SURGE, "HELD SUCTION", 0, .68f, .58f, .55f, .72f, .35f, 1f),
    )
}
