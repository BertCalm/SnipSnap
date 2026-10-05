package com.snipsnap.synth

/**
 * MURK's provisional factory sounds: wood, traveling fog and finite animal answers, with two
 * explicit recurring groves. Every knob is written into each recipe and every preset lands dry.
 * These are starting points for the audition gate, with no claim of a completed listening review.
 */
object MurkPresets {
    // SAVE AS PRESET exports this exact helper shape for pasting a player's recipe into the roster.
    private fun p(voice: MurkVoice, name: String, vararg macros: Pair<String, Float>) =
        MurkPatch(name, voice, macros.toMap())

    private fun sound(
        voice: MurkVoice,
        name: String,
        semitone: Int,
        strike: Float,
        trunk: Float,
        fog: Float,
        agitation: Float,
        grove: Float,
        hold: Float = 0f,
    ) = p(voice, name,
        "TUNE" to semitone / Murk.TUNE_SEMITONES.toFloat(),
        "STRIKE" to strike,
        "TRUNK" to trunk,
        "FOG" to fog,
        "AGITATION" to agitation,
        "GROVE" to grove,
        "HOLD" to hold,
    )

    fun forVoice(voice: MurkVoice): List<MurkPatch> = when (voice) {
        MurkVoice.CLUNK -> clunk
        MurkVoice.THWACK -> thwack
        MurkVoice.FRONT -> front
        MurkVoice.HOOT -> hoot
        MurkVoice.GROVE -> grove
        MurkVoice.ALARM -> alarm
    }

    fun all(): List<MurkPatch> = MurkVoice.entries.flatMap { forVoice(it) }

    private val clunk = listOf(
        sound(MurkVoice.CLUNK, "HOLLOW BAT", 12, 0.15f, 0.65f, 0.35f, 0.20f, 0.30f),
        sound(MurkVoice.CLUNK, "DEEP TRUNK", 0, 0.10f, 0.90f, 0.55f, 0.15f, 0.45f),
        sound(MurkVoice.CLUNK, "HELD TREES", 7, 0.24f, 0.72f, 0.48f, 0.25f, 0.65f, 1f),
    )
    private val thwack = listOf(
        sound(MurkVoice.THWACK, "TONAL AXE", 12, 0.92f, 0.35f, 0.25f, 0.35f, 0.40f),
        sound(MurkVoice.THWACK, "SOFT CHOP", 19, 0.60f, 0.70f, 0.40f, 0.12f, 0.25f),
    )
    private val front = listOf(
        sound(MurkVoice.FRONT, "FIRST PULSE", 12, 0.45f, 0.60f, 0.85f, 0.25f, 0.65f),
        sound(MurkVoice.FRONT, "HEAVY AIR", 5, 0.35f, 0.80f, 0.95f, 0.40f, 0.85f),
    )
    private val hoot = listOf(
        sound(MurkVoice.HOOT, "DISTANT CALL", 12, 0.25f, 0.50f, 0.45f, 0.50f, 0.85f),
        sound(MurkVoice.HOOT, "WARY SILENCE", 17, 0.20f, 0.40f, 0.30f, 0.06f, 0.40f),
        sound(MurkVoice.HOOT, "HELD OWLS", 12, 0.32f, 0.50f, 0.55f, 0.65f, 0.70f, 1f),
    )
    private val grove = listOf(
        sound(MurkVoice.GROVE, "ANSWERING WOOD", 12, 0.40f, 0.55f, 0.60f, 0.60f, 0.80f),
        sound(MurkVoice.GROVE, "CLOSE TREES", 15, 0.25f, 0.80f, 0.25f, 0.45f, 0.10f),
    )
    private val alarm = listOf(
        sound(MurkVoice.ALARM, "SOFT BARK", 12, 0.60f, 0.45f, 0.50f, 0.62f, 0.45f),
        sound(MurkVoice.ALARM, "SHARP RETURN", 24, 0.95f, 0.35f, 0.85f, 0.95f, 0.72f),
    )
}
