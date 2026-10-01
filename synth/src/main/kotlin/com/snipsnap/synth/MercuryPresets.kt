package com.snipsnap.synth

/**
 * MERCURY's factory roster: eight per voice, authored from the R1 measurements and
 * **provisional**: nothing here was listened to. The audition gate
 * (`./gradlew :synth:generateMercuryAudition`, then `testkit/mercury-audition/index.html`)
 * is where a human decides which of these is a glass, a bowl or a bent blade and which is
 * only a tone that measures like one; a name that does not survive it is renamed or dropped.
 *
 * Every preset names all seven knobs, so the roster is the full knob and not a default in
 * disguise. TUNE is k over 24, snapped to a semitone; the comment names the note.
 *
 * Names are plain words: no maker, no model, no engine's, voice's or rack section's name.
 */
object MercuryPresets {

    private fun p(voice: MercuryVoice, name: String, tune: Int, bend: Float, rub: Float, water: Float, glass: Float, couple: Float, hold: Float) =
        MercuryPatch(
            name, voice,
            mapOf(
                "TUNE" to tune / Mercury.TUNE_SEMITONES.toFloat(), "BEND" to bend, "RUB" to rub, "WATER" to water,
                "GLASS" to glass, "COUPLE" to couple, "HOLD" to hold,
            ),
        )

    fun forVoice(voice: MercuryVoice): List<MercuryPatch> = when (voice) {
        MercuryVoice.PING -> pingPresets
        MercuryVoice.SING -> singPresets
        MercuryVoice.BLADE -> bladePresets
    }

    fun all(): List<MercuryPatch> = MercuryVoice.entries.flatMap { forVoice(it) }

    // PING's root is C4.
    private val pingPresets = listOf(
        p(MercuryVoice.PING, "CLEAR RIM", 12, 0.5f, 0.05f, 0.05f, 0.85f, 0.15f, 0.2f), // C5
        p(MercuryVoice.PING, "SOFT MALLET", 7, 0.5f, 0.15f, 0.1f, 0.35f, 0.2f, 0.3f), // G4
        p(MercuryVoice.PING, "STILL WATER", 12, 0.5f, 0.1f, 0f, 0.7f, 0.1f, 0.4f), // C5
        p(MercuryVoice.PING, "COLD GLASS", 19, 0.55f, 0.05f, 0.1f, 1f, 0.3f, 0.15f), // G5
        p(MercuryVoice.PING, "WARPED PLATE", 10, 0.85f, 0.1f, 0.2f, 0.6f, 0.45f, 0.3f), // A#4
        p(MercuryVoice.PING, "FLOATING MASS", 12, 0.5f, 0.1f, 0.7f, 0.7f, 0.35f, 0.5f), // C5
        p(MercuryVoice.PING, "PAIRED MODES", 9, 0.5f, 0.1f, 0.15f, 0.75f, 0.9f, 0.3f), // A4
        p(MercuryVoice.PING, "LOW BELL", 0, 0.45f, 0.2f, 0.2f, 0.55f, 0.5f, 0.45f), // C4
    )

    // SING's root is G3.
    private val singPresets = listOf(
        p(MercuryVoice.SING, "LONG RUB", 12, 0.5f, 0.9f, 0.1f, 0.9f, 0.25f, 0.8f), // G4
        p(MercuryVoice.SING, "SINGING EDGE", 14, 0.5f, 0.85f, 0.1f, 0.95f, 0.2f, 0.5f), // A4
        p(MercuryVoice.SING, "GLASS CURRENT", 12, 0.5f, 0.8f, 0.5f, 0.85f, 0.5f, 0.7f), // G4
        p(MercuryVoice.SING, "SLOW CURRENT", 9, 0.5f, 0.75f, 0.65f, 0.7f, 0.6f, 0.9f), // E4
        p(MercuryVoice.SING, "HUSHED GLASS", 11, 0.5f, 0.55f, 0.1f, 0.6f, 0.3f, 0.45f), // F#4
        p(MercuryVoice.SING, "WET FINGER", 13, 0.45f, 1f, 0.25f, 0.8f, 0.3f, 0.6f), // G#4
        p(MercuryVoice.SING, "UPPER RIM", 21, 0.5f, 0.85f, 0.15f, 1f, 0.4f, 0.4f), // E5
        p(MercuryVoice.SING, "LOW HUM", 2, 0.5f, 0.85f, 0.2f, 0.75f, 0.35f, 0.7f), // A3
    )

    // BLADE's root is G3.
    private val bladePresets = listOf(
        p(MercuryVoice.BLADE, "BENT RIBBON", 12, 0.7f, 0.85f, 0.15f, 0.5f, 0.3f, 0.6f), // G4
        p(MercuryVoice.BLADE, "BOWED STEEL", 14, 0.65f, 0.9f, 0.1f, 0.45f, 0.3f, 0.5f), // A4
        p(MercuryVoice.BLADE, "WHISTLE BEND", 19, 0.85f, 0.8f, 0.2f, 0.55f, 0.25f, 0.45f), // D5
        p(MercuryVoice.BLADE, "SLOW GLIDE", 9, 0.75f, 0.75f, 0.25f, 0.45f, 0.35f, 0.8f), // E4
        p(MercuryVoice.BLADE, "DOWN BEND", 12, 0.2f, 0.8f, 0.15f, 0.45f, 0.3f, 0.5f), // G4
        p(MercuryVoice.BLADE, "WOBBLE STEEL", 12, 0.65f, 0.8f, 0.7f, 0.45f, 0.45f, 0.6f), // G4
        p(MercuryVoice.BLADE, "LOW STEEL", 2, 0.6f, 0.85f, 0.15f, 0.35f, 0.3f, 0.7f), // A3
        p(MercuryVoice.BLADE, "TAPPED STEEL", 14, 0.65f, 0.3f, 0.1f, 0.5f, 0.3f, 0.35f), // A4
    )
}
