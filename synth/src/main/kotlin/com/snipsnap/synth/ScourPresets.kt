package com.snipsnap.synth

/**
 * SCOUR's factory roster: sixteen impacts, four for each voice, from a bare slam to a long grinding run,
 * a ringing bell and a distant storm. Every sound is dry and names all six controls; TUNE stays on C minor
 * pentatonic from each voice's root (C3, and C2 for SHEET). BUILD is in seconds (the engine has no
 * tempo): the kit's own build pads are cut to bar lengths at 90 and 120 BPM. These names and settings are
 * proposals for the owner's next listen.
 */
object ScourPresets {

    private fun p(voice: ScourVoice, name: String, vararg macros: Pair<String, Float>) =
        ScourPatch(name, voice, macros.toMap())

    fun forVoice(voice: ScourVoice): List<ScourPatch> = when (voice) {
        ScourVoice.PLATE -> plate
        ScourVoice.PANE -> pane
        ScourVoice.SHEET -> sheet
        ScourVoice.BEAM -> beam
    }

    fun all(): List<ScourPatch> = ScourVoice.entries.flatMap { forVoice(it) }

    private val plate = listOf(
        p(ScourVoice.PLATE, "IRON SLAM", "TUNE" to 0f, "BUILD" to 0f, "RPM" to 0.30f, "SHOT" to 0.70f, "HARDEN" to 0.60f, "HOLD" to 0.20f),
        p(ScourVoice.PLATE, "FORGE RISE", "TUNE" to 0f, "BUILD" to 0.60f, "RPM" to 0.70f, "SHOT" to 0.80f, "HARDEN" to 0.90f, "HOLD" to 0.30f),
        p(ScourVoice.PLATE, "GRINDER RUN", "TUNE" to 0f, "BUILD" to 0.85f, "RPM" to 1f, "SHOT" to 0.50f, "HARDEN" to 0.40f, "HOLD" to 0.50f),
        p(ScourVoice.PLATE, "PEENED BELL", "TUNE" to 12 / 24f, "BUILD" to 0.30f, "RPM" to 0.20f, "SHOT" to 0.50f, "HARDEN" to 1f, "HOLD" to 1f),
    )

    private val pane = listOf(
        p(ScourVoice.PANE, "GLASS SNAP", "TUNE" to 12 / 24f, "BUILD" to 0f, "RPM" to 0.20f, "SHOT" to 0.60f, "HARDEN" to 0.50f, "HOLD" to 0.10f),
        p(ScourVoice.PANE, "FROST RISE", "TUNE" to 7 / 24f, "BUILD" to 0.50f, "RPM" to 0.40f, "SHOT" to 0.70f, "HARDEN" to 0.60f, "HOLD" to 0.50f),
        p(ScourVoice.PANE, "SHATTER RUN", "TUNE" to 0f, "BUILD" to 0.70f, "RPM" to 0.60f, "SHOT" to 1f, "HARDEN" to 1f, "HOLD" to 0.30f),
        p(ScourVoice.PANE, "PANE TOLL", "TUNE" to 15 / 24f, "BUILD" to 0.20f, "RPM" to 0.10f, "SHOT" to 0.40f, "HARDEN" to 0.80f, "HOLD" to 1f),
    )

    /** SHEET plays from C2, so its TUNE counts semitones from there. */
    private val sheet = listOf(
        p(ScourVoice.SHEET, "STEEL BOOM", "TUNE" to 0f, "BUILD" to 0f, "RPM" to 0.30f, "SHOT" to 0.60f, "HARDEN" to 0.50f, "HOLD" to 0.40f),
        p(ScourVoice.SHEET, "SHEET RISE", "TUNE" to 5 / 24f, "BUILD" to 0.60f, "RPM" to 0.60f, "SHOT" to 0.80f, "HARDEN" to 0.80f, "HOLD" to 0.30f),
        p(ScourVoice.SHEET, "THUNDER RUN", "TUNE" to 0f, "BUILD" to 0.85f, "RPM" to 1f, "SHOT" to 0.50f, "HARDEN" to 0.40f, "HOLD" to 0.60f),
        p(ScourVoice.SHEET, "DISTANT STORM", "TUNE" to 7 / 24f, "BUILD" to 0.70f, "RPM" to 0.40f, "SHOT" to 0.30f, "HARDEN" to 0.30f, "HOLD" to 1f),
    )

    /** BEAM plays from C3, like PLATE and PANE. */
    private val beam = listOf(
        p(ScourVoice.BEAM, "GIRDER CLANG", "TUNE" to 0f, "BUILD" to 0f, "RPM" to 0.20f, "SHOT" to 0.60f, "HARDEN" to 0.70f, "HOLD" to 0.30f),
        p(ScourVoice.BEAM, "RIVET RISE", "TUNE" to 7 / 24f, "BUILD" to 0.60f, "RPM" to 0.50f, "SHOT" to 0.90f, "HARDEN" to 0.90f, "HOLD" to 0.20f),
        p(ScourVoice.BEAM, "GANTRY RUN", "TUNE" to 3 / 24f, "BUILD" to 0.85f, "RPM" to 0.80f, "SHOT" to 0.50f, "HARDEN" to 0.50f, "HOLD" to 0.50f),
        p(ScourVoice.BEAM, "FOUNDRY BELL", "TUNE" to 12 / 24f, "BUILD" to 0.30f, "RPM" to 0.10f, "SHOT" to 0.40f, "HARDEN" to 1f, "HOLD" to 1f),
    )
}
