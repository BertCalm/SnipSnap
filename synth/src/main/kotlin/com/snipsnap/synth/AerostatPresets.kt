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

    // Root is C3. Names and factory notes are retained; the common-C3
    // audition checks that these roles survive removing pitch differences.
    // SOFT CATCH: gentle paddle/tube, below the airflow catch.
    // TWIN PIPES: both banks catch, with a modest powered bloom.
    // HEAVY ROTOR: preserve the high-inertia hit, darken its later air.
    // DRIFTING STEAM: long powered air and pronounced vessel rise.
    // HIGH ENVELOPE: fast, hard contact followed by a brief bright burst.
    private val floatPresets = listOf(
        p(AerostatVoice.FLOAT, "SOFT CATCH", "TUNE" to 12 / 24f, "STRIKE" to 0.10f, "PRESSURE" to 0.18f, "INERTIA" to 0.65f, "RELEASE" to 0.18f, "LIFT" to 0f, "HOLD" to 0f), // C4
        p(AerostatVoice.FLOAT, "TWIN PIPES", "TUNE" to 12 / 24f, "STRIKE" to 0.70f, "PRESSURE" to 0.60f, "INERTIA" to 0.24f, "RELEASE" to 0.48f, "LIFT" to 0.30f, "HOLD" to 0.14f), // C4
        p(AerostatVoice.FLOAT, "HEAVY ROTOR", "TUNE" to 7 / 24f, "STRIKE" to 0.88f, "PRESSURE" to 0.28f, "INERTIA" to 0.92f, "RELEASE" to 0.42f, "LIFT" to 0.08f, "HOLD" to 0f), // G3
        p(AerostatVoice.FLOAT, "DRIFTING STEAM", "TUNE" to 16 / 24f, "STRIKE" to 0.66f, "PRESSURE" to 0.92f, "INERTIA" to 0.64f, "RELEASE" to 1f, "LIFT" to 1f, "HOLD" to 0.72f), // E4
        p(AerostatVoice.FLOAT, "HIGH ENVELOPE", "TUNE" to 19 / 24f, "STRIKE" to 0.98f, "PRESSURE" to 0.82f, "INERTIA" to 0.02f, "RELEASE" to 0.10f, "LIFT" to 0.72f, "HOLD" to 0f), // G4
    )
}
