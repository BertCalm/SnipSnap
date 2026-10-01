package com.snipsnap.synth

/**
 * MERCURY's factory roster: eight per voice, authored from the R1 measurements and
 * **provisional**: nothing here was listened to. The audition gate
 * (`./gradlew :synth:generateMercuryAudition`, then `testkit/mercury-audition/index.html`)
 * is where a human decides which of these is a glass, a bowl or a bent blade and which is
 * only a tone that measures like one; a name that does not survive it is renamed or dropped.
 *
 * Every preset names all seven knobs, so the roster is the full knob and not a default in
 * disguise. TUNE is k over 24 ([Mercury.TUNE_SEMITONES]), snapped to a semitone; the comment names the note.
 * The helper is the house's `p(voice, name, macros…)`, the shape `UserPresets.rosterLine` pastes into.
 *
 * Names are plain words: no maker, no model, no engine's, voice's or rack section's name.
 */
object MercuryPresets {

    private fun p(voice: MercuryVoice, name: String, vararg macros: Pair<String, Float>) =
        MercuryPatch(name, voice, macros.toMap())

    fun forVoice(voice: MercuryVoice): List<MercuryPatch> = when (voice) {
        MercuryVoice.PING -> pingPresets
        MercuryVoice.SING -> singPresets
        MercuryVoice.BLADE -> bladePresets
    }

    fun all(): List<MercuryPatch> = MercuryVoice.entries.flatMap { forVoice(it) }

    // PING's root is C4.
    private val pingPresets = listOf(
        p(MercuryVoice.PING, "CLEAR RIM", "TUNE" to 12 / 24f, "BEND" to 0.5f, "RUB" to 0.05f, "WATER" to 0.05f, "GLASS" to 0.85f, "COUPLE" to 0.15f, "HOLD" to 0.2f), // C5
        p(MercuryVoice.PING, "SOFT MALLET", "TUNE" to 7 / 24f, "BEND" to 0.5f, "RUB" to 0.15f, "WATER" to 0.1f, "GLASS" to 0.35f, "COUPLE" to 0.2f, "HOLD" to 0.3f), // G4
        p(MercuryVoice.PING, "STILL WATER", "TUNE" to 12 / 24f, "BEND" to 0.5f, "RUB" to 0.1f, "WATER" to 0f, "GLASS" to 0.7f, "COUPLE" to 0.1f, "HOLD" to 0.4f), // C5
        p(MercuryVoice.PING, "COLD GLASS", "TUNE" to 19 / 24f, "BEND" to 0.55f, "RUB" to 0.05f, "WATER" to 0.1f, "GLASS" to 1f, "COUPLE" to 0.3f, "HOLD" to 0.15f), // G5
        p(MercuryVoice.PING, "WARPED PLATE", "TUNE" to 10 / 24f, "BEND" to 0.85f, "RUB" to 0.1f, "WATER" to 0.2f, "GLASS" to 0.6f, "COUPLE" to 0.45f, "HOLD" to 0.3f), // A#4
        p(MercuryVoice.PING, "FLOATING MASS", "TUNE" to 12 / 24f, "BEND" to 0.5f, "RUB" to 0.1f, "WATER" to 0.7f, "GLASS" to 0.7f, "COUPLE" to 0.35f, "HOLD" to 0.5f), // C5
        p(MercuryVoice.PING, "PAIRED MODES", "TUNE" to 9 / 24f, "BEND" to 0.5f, "RUB" to 0.1f, "WATER" to 0.15f, "GLASS" to 0.75f, "COUPLE" to 0.9f, "HOLD" to 0.3f), // A4
        p(MercuryVoice.PING, "LOW BELL", "TUNE" to 0 / 24f, "BEND" to 0.45f, "RUB" to 0.2f, "WATER" to 0.2f, "GLASS" to 0.55f, "COUPLE" to 0.5f, "HOLD" to 0.45f), // C4
    )

    // SING's root is G3.
    private val singPresets = listOf(
        p(MercuryVoice.SING, "LONG RUB", "TUNE" to 12 / 24f, "BEND" to 0.5f, "RUB" to 0.9f, "WATER" to 0.1f, "GLASS" to 0.9f, "COUPLE" to 0.25f, "HOLD" to 0.8f), // G4
        p(MercuryVoice.SING, "SINGING EDGE", "TUNE" to 14 / 24f, "BEND" to 0.5f, "RUB" to 0.85f, "WATER" to 0.1f, "GLASS" to 0.95f, "COUPLE" to 0.2f, "HOLD" to 0.5f), // A4
        p(MercuryVoice.SING, "GLASS CURRENT", "TUNE" to 12 / 24f, "BEND" to 0.5f, "RUB" to 0.8f, "WATER" to 0.5f, "GLASS" to 0.85f, "COUPLE" to 0.5f, "HOLD" to 0.7f), // G4
        p(MercuryVoice.SING, "SLOW CURRENT", "TUNE" to 9 / 24f, "BEND" to 0.5f, "RUB" to 0.75f, "WATER" to 0.65f, "GLASS" to 0.7f, "COUPLE" to 0.6f, "HOLD" to 0.9f), // E4
        p(MercuryVoice.SING, "HUSHED GLASS", "TUNE" to 11 / 24f, "BEND" to 0.5f, "RUB" to 0.55f, "WATER" to 0.1f, "GLASS" to 0.6f, "COUPLE" to 0.3f, "HOLD" to 0.45f), // F#4
        p(MercuryVoice.SING, "WET FINGER", "TUNE" to 13 / 24f, "BEND" to 0.45f, "RUB" to 1f, "WATER" to 0.25f, "GLASS" to 0.8f, "COUPLE" to 0.3f, "HOLD" to 0.6f), // G#4
        p(MercuryVoice.SING, "UPPER RIM", "TUNE" to 21 / 24f, "BEND" to 0.5f, "RUB" to 0.85f, "WATER" to 0.15f, "GLASS" to 1f, "COUPLE" to 0.4f, "HOLD" to 0.4f), // E5
        p(MercuryVoice.SING, "LOW HUM", "TUNE" to 2 / 24f, "BEND" to 0.5f, "RUB" to 0.85f, "WATER" to 0.2f, "GLASS" to 0.75f, "COUPLE" to 0.35f, "HOLD" to 0.7f), // A3
    )

    // BLADE's root is G3.
    private val bladePresets = listOf(
        p(MercuryVoice.BLADE, "BENT RIBBON", "TUNE" to 12 / 24f, "BEND" to 0.7f, "RUB" to 0.85f, "WATER" to 0.15f, "GLASS" to 0.5f, "COUPLE" to 0.3f, "HOLD" to 0.6f), // G4
        p(MercuryVoice.BLADE, "BOWED STEEL", "TUNE" to 14 / 24f, "BEND" to 0.65f, "RUB" to 0.9f, "WATER" to 0.1f, "GLASS" to 0.45f, "COUPLE" to 0.3f, "HOLD" to 0.5f), // A4
        p(MercuryVoice.BLADE, "WHISTLE BEND", "TUNE" to 19 / 24f, "BEND" to 0.85f, "RUB" to 0.8f, "WATER" to 0.2f, "GLASS" to 0.55f, "COUPLE" to 0.25f, "HOLD" to 0.45f), // D5
        p(MercuryVoice.BLADE, "SLOW GLIDE", "TUNE" to 9 / 24f, "BEND" to 0.75f, "RUB" to 0.75f, "WATER" to 0.25f, "GLASS" to 0.45f, "COUPLE" to 0.35f, "HOLD" to 0.8f), // E4
        p(MercuryVoice.BLADE, "DOWN BEND", "TUNE" to 12 / 24f, "BEND" to 0.2f, "RUB" to 0.8f, "WATER" to 0.15f, "GLASS" to 0.45f, "COUPLE" to 0.3f, "HOLD" to 0.5f), // G4
        p(MercuryVoice.BLADE, "WOBBLE STEEL", "TUNE" to 12 / 24f, "BEND" to 0.65f, "RUB" to 0.8f, "WATER" to 0.7f, "GLASS" to 0.45f, "COUPLE" to 0.45f, "HOLD" to 0.6f), // G4
        p(MercuryVoice.BLADE, "LOW STEEL", "TUNE" to 2 / 24f, "BEND" to 0.6f, "RUB" to 0.85f, "WATER" to 0.15f, "GLASS" to 0.35f, "COUPLE" to 0.3f, "HOLD" to 0.7f), // A3
        p(MercuryVoice.BLADE, "TAPPED STEEL", "TUNE" to 14 / 24f, "BEND" to 0.65f, "RUB" to 0.3f, "WATER" to 0.1f, "GLASS" to 0.5f, "COUPLE" to 0.3f, "HOLD" to 0.35f), // A4
    )
}
