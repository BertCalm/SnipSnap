package com.snipsnap.synth

/**
 * BALLAST's factory roster: eight per voice, authored from the R1 measurements and **provisional**: nothing here was
 * listened to. The audition gate (`./gradlew :synth:generateBallastAudition`, then `testkit/ballast-audition/index.html`)
 * is where a human decides which of these is a bass that shakes a room and which is only a tone that measures like one;
 * a name that does not survive it is renamed or dropped.
 *
 * Every preset names all seven knobs, so the roster is the full knob and not a default in disguise. TUNE is k over 36
 * ([Ballast.TUNE_SEMITONES]), snapped to a semitone; the comment names the note. The helper is the house's
 * `p(voice, name, macros...)`, the shape `UserPresets.rosterLine` pastes into.
 *
 * Names are plain words: no maker, no model, no engine's, voice's or rack section's name.
 */
object BallastPresets {

    private fun p(voice: BallastVoice, name: String, vararg macros: Pair<String, Float>) =
        BallastPatch(name, voice, macros.toMap())

    fun forVoice(voice: BallastVoice): List<BallastPatch> = when (voice) {
        BallastVoice.ROOT -> rootPresets
        BallastVoice.WIRE -> wirePresets
        BallastVoice.GLINT -> glintPresets
        BallastVoice.DEEP -> deepPresets
        BallastVoice.BLOOM -> bloomPresets
        BallastVoice.SWARM -> swarmPresets
    }

    fun all(): List<BallastPatch> = BallastVoice.entries.flatMap { forVoice(it) }

    // Every voice's root is C1 (MIDI 24).
    private val rootPresets = listOf(
        p(BallastVoice.ROOT, "TIGHT BASE", "TUNE" to 0 / 36f, "DRIVE" to 0.35f, "SYMPATHY" to 0.2f, "SPAN" to 0.25f, "GLASS" to 0.05f, "FRAME" to 0.25f, "HOLD" to 0f), // C1
        p(BallastVoice.ROOT, "LOW FRAME", "TUNE" to 7 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.3f, "SPAN" to 0.3f, "GLASS" to 0.1f, "FRAME" to 0.4f, "HOLD" to 0.2f), // G1
        p(BallastVoice.ROOT, "STONE FLOOR", "TUNE" to 12 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.25f, "SPAN" to 0.35f, "GLASS" to 0.1f, "FRAME" to 0.3f, "HOLD" to 0.3f), // C2
        p(BallastVoice.ROOT, "SHORT DRAW", "TUNE" to 12 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.2f, "SPAN" to 0.25f, "GLASS" to 0.15f, "FRAME" to 0.2f, "HOLD" to 0f), // C2
        p(BallastVoice.ROOT, "HEAVY MOUNT", "TUNE" to 5 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.35f, "SPAN" to 0.4f, "GLASS" to 0.15f, "FRAME" to 0.6f, "HOLD" to 0.35f), // F1
        p(BallastVoice.ROOT, "QUIET GLASS", "TUNE" to 12 / 36f, "DRIVE" to 0.3f, "SYMPATHY" to 0.3f, "SPAN" to 0.3f, "GLASS" to 0.3f, "FRAME" to 0.4f, "HOLD" to 0.25f), // C2
        p(BallastVoice.ROOT, "PLAIN OCTAVES", "TUNE" to 19 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.5f, "SPAN" to 0.55f, "GLASS" to 0.1f, "FRAME" to 0.35f, "HOLD" to 0.3f), // G2
        p(BallastVoice.ROOT, "NIGHT DRIVE", "TUNE" to 0 / 36f, "DRIVE" to 0.7f, "SYMPATHY" to 0.3f, "SPAN" to 0.35f, "GLASS" to 0.2f, "FRAME" to 0.45f, "HOLD" to 0.45f), // C1
    )

    private val wirePresets = listOf(
        p(BallastVoice.WIRE, "LONG STRING", "TUNE" to 12 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.85f, "SPAN" to 0.5f, "GLASS" to 0.2f, "FRAME" to 0.5f, "HOLD" to 0.5f), // C2
        p(BallastVoice.WIRE, "WIDE OCTAVES", "TUNE" to 12 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.7f, "SPAN" to 0.85f, "GLASS" to 0.2f, "FRAME" to 0.5f, "HOLD" to 0.4f), // C2
        p(BallastVoice.WIRE, "HELD FRAME", "TUNE" to 7 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.7f, "SPAN" to 0.45f, "GLASS" to 0.15f, "FRAME" to 0.65f, "HOLD" to 0.7f), // G1
        p(BallastVoice.WIRE, "TAUT WIRES", "TUNE" to 19 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.6f, "SPAN" to 0.4f, "GLASS" to 0.2f, "FRAME" to 0.3f, "HOLD" to 0.3f), // G2
        p(BallastVoice.WIRE, "UPPER HALO", "TUNE" to 12 / 36f, "DRIVE" to 0.35f, "SYMPATHY" to 0.75f, "SPAN" to 0.75f, "GLASS" to 0.25f, "FRAME" to 0.55f, "HOLD" to 0.45f), // C2
        p(BallastVoice.WIRE, "BRIDGE HUM", "TUNE" to 0 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.8f, "SPAN" to 0.35f, "GLASS" to 0.15f, "FRAME" to 0.45f, "HOLD" to 0.5f), // C1
        p(BallastVoice.WIRE, "THIN CABLE", "TUNE" to 24 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.65f, "SPAN" to 0.6f, "GLASS" to 0.25f, "FRAME" to 0.35f, "HOLD" to 0.25f), // C3
        p(BallastVoice.WIRE, "RESONANT BODY", "TUNE" to 14 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.9f, "SPAN" to 0.55f, "GLASS" to 0.2f, "FRAME" to 0.6f, "HOLD" to 0.6f), // D2
    )

    private val glintPresets = listOf(
        p(BallastVoice.GLINT, "GLASS WAKE", "TUNE" to 12 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.45f, "SPAN" to 0.6f, "GLASS" to 0.65f, "FRAME" to 0.45f, "HOLD" to 0.4f), // C2
        p(BallastVoice.GLINT, "TILE SHIVER", "TUNE" to 19 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.4f, "SPAN" to 0.55f, "GLASS" to 0.8f, "FRAME" to 0.4f, "HOLD" to 0.3f), // G2
        p(BallastVoice.GLINT, "BRIGHT TAPS", "TUNE" to 24 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.35f, "SPAN" to 0.65f, "GLASS" to 0.7f, "FRAME" to 0.35f, "HOLD" to 0.2f), // C3
        p(BallastVoice.GLINT, "CLEAR TOPS", "TUNE" to 12 / 36f, "DRIVE" to 0.35f, "SYMPATHY" to 0.5f, "SPAN" to 0.8f, "GLASS" to 0.6f, "FRAME" to 0.5f, "HOLD" to 0.35f), // C2
        p(BallastVoice.GLINT, "SPARK ROW", "TUNE" to 7 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.45f, "SPAN" to 0.6f, "GLASS" to 0.75f, "FRAME" to 0.3f, "HOLD" to 0.25f), // G1
        p(BallastVoice.GLINT, "FROST LINE", "TUNE" to 14 / 36f, "DRIVE" to 0.3f, "SYMPATHY" to 0.55f, "SPAN" to 0.7f, "GLASS" to 0.55f, "FRAME" to 0.5f, "HOLD" to 0.45f), // D2
        p(BallastVoice.GLINT, "SMALL BELLS", "TUNE" to 21 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.4f, "SPAN" to 0.5f, "GLASS" to 0.85f, "FRAME" to 0.45f, "HOLD" to 0.3f), // A2
        p(BallastVoice.GLINT, "DENSE TILES", "TUNE" to 12 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.4f, "SPAN" to 0.55f, "GLASS" to 0.95f, "FRAME" to 0.55f, "HOLD" to 0.4f), // C2
    )

    private val deepPresets = listOf(
        p(BallastVoice.DEEP, "LOWER ECHO", "TUNE" to 0 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.45f, "SPAN" to 0.8f, "GLASS" to 0.15f, "FRAME" to 0.65f, "HOLD" to 0.45f), // C1
        p(BallastVoice.DEEP, "SUB WEIGHT", "TUNE" to 0 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.5f, "SPAN" to 0.7f, "GLASS" to 0.1f, "FRAME" to 0.7f, "HOLD" to 0.35f), // C1
        p(BallastVoice.DEEP, "LOOSE MOUNT", "TUNE" to 5 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.4f, "SPAN" to 0.7f, "GLASS" to 0.2f, "FRAME" to 0.85f, "HOLD" to 0.5f), // F1
        p(BallastVoice.DEEP, "CAVE FLOOR", "TUNE" to 7 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.55f, "SPAN" to 0.85f, "GLASS" to 0.15f, "FRAME" to 0.6f, "HOLD" to 0.55f), // G1
        p(BallastVoice.DEEP, "FALLING AWAY", "TUNE" to 12 / 36f, "DRIVE" to 0.35f, "SYMPATHY" to 0.6f, "SPAN" to 0.9f, "GLASS" to 0.2f, "FRAME" to 0.65f, "HOLD" to 0.6f), // C2
        p(BallastVoice.DEEP, "HEAVY FRAME", "TUNE" to 0 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.45f, "SPAN" to 0.65f, "GLASS" to 0.25f, "FRAME" to 0.8f, "HOLD" to 0.4f), // C1
        p(BallastVoice.DEEP, "LOW WELL", "TUNE" to 3 / 36f, "DRIVE" to 0.4f, "SYMPATHY" to 0.65f, "SPAN" to 0.75f, "GLASS" to 0.1f, "FRAME" to 0.7f, "HOLD" to 0.7f), // D#1
        p(BallastVoice.DEEP, "GREAT HALL", "TUNE" to 12 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.5f, "SPAN" to 0.95f, "GLASS" to 0.3f, "FRAME" to 0.75f, "HOLD" to 0.5f), // C2
    )

    private val bloomPresets = listOf(
        p(BallastVoice.BLOOM, "DELAYED OPEN", "TUNE" to 12 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.65f, "SPAN" to 0.5f, "GLASS" to 0.4f, "FRAME" to 0.7f, "HOLD" to 0.5f), // C2
        p(BallastVoice.BLOOM, "SLOW SWELL", "TUNE" to 7 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.7f, "SPAN" to 0.55f, "GLASS" to 0.35f, "FRAME" to 0.75f, "HOLD" to 0.6f), // G1
        p(BallastVoice.BLOOM, "LATE ARRIVAL", "TUNE" to 12 / 36f, "DRIVE" to 0.6f, "SYMPATHY" to 0.6f, "SPAN" to 0.45f, "GLASS" to 0.45f, "FRAME" to 0.65f, "HOLD" to 0.45f), // C2
        p(BallastVoice.BLOOM, "RISING HALO", "TUNE" to 19 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.75f, "SPAN" to 0.6f, "GLASS" to 0.3f, "FRAME" to 0.7f, "HOLD" to 0.55f), // G2
        p(BallastVoice.BLOOM, "SOFT CLOUD", "TUNE" to 14 / 36f, "DRIVE" to 0.45f, "SYMPATHY" to 0.65f, "SPAN" to 0.65f, "GLASS" to 0.35f, "FRAME" to 0.8f, "HOLD" to 0.7f), // D2
        p(BallastVoice.BLOOM, "OPEN FIELD", "TUNE" to 12 / 36f, "DRIVE" to 0.5f, "SYMPATHY" to 0.7f, "SPAN" to 0.7f, "GLASS" to 0.4f, "FRAME" to 0.6f, "HOLD" to 0.5f), // C2
        p(BallastVoice.BLOOM, "WIDE DAWN", "TUNE" to 5 / 36f, "DRIVE" to 0.6f, "SYMPATHY" to 0.65f, "SPAN" to 0.75f, "GLASS" to 0.45f, "FRAME" to 0.7f, "HOLD" to 0.65f), // F1
        p(BallastVoice.BLOOM, "WARM SWELL", "TUNE" to 0 / 36f, "DRIVE" to 0.55f, "SYMPATHY" to 0.6f, "SPAN" to 0.5f, "GLASS" to 0.3f, "FRAME" to 0.85f, "HOLD" to 0.55f), // C1
    )

    private val swarmPresets = listOf(
        p(BallastVoice.SWARM, "DENSE RATTLE", "TUNE" to 12 / 36f, "DRIVE" to 0.75f, "SYMPATHY" to 0.65f, "SPAN" to 0.8f, "GLASS" to 0.85f, "FRAME" to 0.65f, "HOLD" to 0.4f), // C2
        p(BallastVoice.SWARM, "CROWD GLASS", "TUNE" to 19 / 36f, "DRIVE" to 0.8f, "SYMPATHY" to 0.6f, "SPAN" to 0.85f, "GLASS" to 0.9f, "FRAME" to 0.6f, "HOLD" to 0.35f), // G2
        p(BallastVoice.SWARM, "BUSY TILES", "TUNE" to 12 / 36f, "DRIVE" to 0.7f, "SYMPATHY" to 0.7f, "SPAN" to 0.75f, "GLASS" to 0.95f, "FRAME" to 0.7f, "HOLD" to 0.45f), // C2
        p(BallastVoice.SWARM, "TILE STORM", "TUNE" to 7 / 36f, "DRIVE" to 0.9f, "SYMPATHY" to 0.55f, "SPAN" to 0.85f, "GLASS" to 1.0f, "FRAME" to 0.55f, "HOLD" to 0.3f), // G1
        p(BallastVoice.SWARM, "LOUD ROOM", "TUNE" to 0 / 36f, "DRIVE" to 0.85f, "SYMPATHY" to 0.65f, "SPAN" to 0.9f, "GLASS" to 0.8f, "FRAME" to 0.75f, "HOLD" to 0.5f), // C1
        p(BallastVoice.SWARM, "LOOSE PILE", "TUNE" to 12 / 36f, "DRIVE" to 0.65f, "SYMPATHY" to 0.7f, "SPAN" to 0.7f, "GLASS" to 0.9f, "FRAME" to 0.8f, "HOLD" to 0.55f), // C2
        p(BallastVoice.SWARM, "NOISY FRAME", "TUNE" to 5 / 36f, "DRIVE" to 0.8f, "SYMPATHY" to 0.6f, "SPAN" to 0.8f, "GLASS" to 0.85f, "FRAME" to 0.9f, "HOLD" to 0.4f), // F1
        p(BallastVoice.SWARM, "SHAKEN BOX", "TUNE" to 24 / 36f, "DRIVE" to 0.75f, "SYMPATHY" to 0.65f, "SPAN" to 0.85f, "GLASS" to 0.95f, "FRAME" to 0.6f, "HOLD" to 0.35f), // C3
    )
}
