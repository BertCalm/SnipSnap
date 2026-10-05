package com.snipsnap.synth

/**
 * AEROSTAT's factory roster. Five short names, every macro written out, and
 * provisional: the audition page is where a listener says which of these is
 * the instrument and which is only a tone that measures like one.
 *
 * TUNE is semitones over 24 from C3 ([Aerostat.ROOT_MIDI]). The helper is the
 * house `p(voice, name, macros…)` shape `UserPresets.rosterLine` pastes into.
 */
object AerostatPresets {

    private fun p(voice: AerostatVoice, name: String, vararg macros: Pair<String, Float>) =
        AerostatPatch(name, voice, macros.toMap())

    fun forVoice(voice: AerostatVoice): List<AerostatPatch> = when (voice) {
        AerostatVoice.FLOAT -> floatPresets
    }

    fun all(): List<AerostatPatch> = AerostatVoice.entries.flatMap { forVoice(it) }

    // Root is C3. Comments name the note TUNE lands on.
    private val floatPresets = listOf(
        p(AerostatVoice.FLOAT, "SOFT CATCH", "TUNE" to 12 / 24f, "STRIKE" to 0.35f, "PRESSURE" to 0.40f, "INERTIA" to 0.28f, "RELEASE" to 0.42f, "LIFT" to 0.22f, "HOLD" to 0f), // C4
        p(AerostatVoice.FLOAT, "TWIN PIPES", "TUNE" to 12 / 24f, "STRIKE" to 0.72f, "PRESSURE" to 0.68f, "INERTIA" to 0.40f, "RELEASE" to 0.58f, "LIFT" to 0.48f, "HOLD" to 0f), // C4
        p(AerostatVoice.FLOAT, "HEAVY ROTOR", "TUNE" to 7 / 24f, "STRIKE" to 0.88f, "PRESSURE" to 0.50f, "INERTIA" to 0.92f, "RELEASE" to 0.32f, "LIFT" to 0.28f, "HOLD" to 0f), // G3
        p(AerostatVoice.FLOAT, "DRIFTING STEAM", "TUNE" to 16 / 24f, "STRIKE" to 0.58f, "PRESSURE" to 0.78f, "INERTIA" to 0.52f, "RELEASE" to 0.84f, "LIFT" to 0.90f, "HOLD" to 0.22f), // E4
        p(AerostatVoice.FLOAT, "HIGH ENVELOPE", "TUNE" to 19 / 24f, "STRIKE" to 0.80f, "PRESSURE" to 0.92f, "INERTIA" to 0.22f, "RELEASE" to 0.74f, "LIFT" to 0.60f, "HOLD" to 0.16f), // G4
    )
}
