package com.snipsnap.synth

/**
 * FATHOM's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (three voices, thirty-six total). Unlike every
 * other engine here, FATHOM's three voices don't share one macro set —
 * DEEP carries SWEEP, GRIND carries SPREAD, GLASS carries RATIO, per
 * `Fathom.kt`'s own `macrosFor` — so each voice's table below fills in
 * that voice's own sixth macro rather than a shared one. Authored from the
 * DSP (drive/cutoff/decay ranges, GLIDE's upward-only slide), not by ear,
 * and checked by [FathomPresetsTest]'s sanity/round-trip/spread suite.
 */
object FathomPresets {

    private fun p(voice: FathomVoice, name: String, vararg macros: Pair<String, Float>) =
        FathomPatch(name, voice, macros.toMap())

    fun forVoice(voice: FathomVoice): List<FathomPatch> = when (voice) {
        FathomVoice.DEEP -> deepPresets
        FathomVoice.GRIND -> grindPresets
        FathomVoice.GLASS -> glassPresets
    }

    fun all(): List<FathomPatch> = FathomVoice.entries.flatMap { forVoice(it) }

    private val deepPresets = listOf(
        p(FathomVoice.DEEP, "SUB DROP", "TUNE" to 0.1f, "GLIDE" to 0f, "DRIVE" to 0.2f, "CUTOFF" to 0.3f, "DECAY" to 0.6f, "SWEEP" to 0.6f),
        p(FathomVoice.DEEP, "FLOOR SHAKE", "TUNE" to 0.05f, "GLIDE" to 0f, "DRIVE" to 0.3f, "CUTOFF" to 0.25f, "DECAY" to 0.7f, "SWEEP" to 0.8f),
        p(FathomVoice.DEEP, "ROUND SUB", "TUNE" to 0.15f, "GLIDE" to 0.1f, "DRIVE" to 0.15f, "CUTOFF" to 0.35f, "DECAY" to 0.5f, "SWEEP" to 0.3f),
        p(FathomVoice.DEEP, "SINE SUB", "TUNE" to 0.2f, "GLIDE" to 0f, "DRIVE" to 0.1f, "CUTOFF" to 0.4f, "DECAY" to 0.55f, "SWEEP" to 0.2f),
        p(FathomVoice.DEEP, "GLIDING SUB", "TUNE" to 0.25f, "GLIDE" to 0.5f, "DRIVE" to 0.25f, "CUTOFF" to 0.3f, "DECAY" to 0.5f, "SWEEP" to 0.4f),
        p(FathomVoice.DEEP, "LONG GLIDE", "TUNE" to 0.3f, "GLIDE" to 0.8f, "DRIVE" to 0.2f, "CUTOFF" to 0.35f, "DECAY" to 0.6f, "SWEEP" to 0.3f),
        p(FathomVoice.DEEP, "PUNCHY SUB", "TUNE" to 0.1f, "GLIDE" to 0f, "DRIVE" to 0.4f, "CUTOFF" to 0.45f, "DECAY" to 0.35f, "SWEEP" to 0.9f),
        p(FathomVoice.DEEP, "QUIET DEEP", "TUNE" to 0.05f, "GLIDE" to 0f, "DRIVE" to 0.05f, "CUTOFF" to 0.2f, "DECAY" to 0.8f, "SWEEP" to 0.1f),
        p(FathomVoice.DEEP, "DRIVEN SUB", "TUNE" to 0.35f, "GLIDE" to 0.2f, "DRIVE" to 0.55f, "CUTOFF" to 0.5f, "DECAY" to 0.4f, "SWEEP" to 0.5f),
        p(FathomVoice.DEEP, "SLOW SWEEP", "TUNE" to 0.15f, "GLIDE" to 0.3f, "DRIVE" to 0.3f, "CUTOFF" to 0.4f, "DECAY" to 0.65f, "SWEEP" to 0.7f),
        p(FathomVoice.DEEP, "TIGHT SUB", "TUNE" to 0.2f, "GLIDE" to 0f, "DRIVE" to 0.35f, "CUTOFF" to 0.55f, "DECAY" to 0.3f, "SWEEP" to 0.5f),
        p(FathomVoice.DEEP, "WOBBLY DEEP", "TUNE" to 0.3f, "GLIDE" to 0.65f, "DRIVE" to 0.3f, "CUTOFF" to 0.35f, "DECAY" to 0.55f, "SWEEP" to 0.4f),
    )

    private val grindPresets = listOf(
        p(FathomVoice.GRIND, "GRINDER", "TUNE" to 0.3f, "GLIDE" to 0f, "DRIVE" to 0.5f, "CUTOFF" to 0.35f, "DECAY" to 0.6f, "SPREAD" to 0.5f),
        p(FathomVoice.GRIND, "HOLLOW GROWL", "TUNE" to 0.25f, "GLIDE" to 0f, "DRIVE" to 0.4f, "CUTOFF" to 0.3f, "DECAY" to 0.7f, "SPREAD" to 0.7f),
        p(FathomVoice.GRIND, "RAW SAW", "TUNE" to 0.35f, "GLIDE" to 0.1f, "DRIVE" to 0.6f, "CUTOFF" to 0.45f, "DECAY" to 0.5f, "SPREAD" to 0.3f),
        p(FathomVoice.GRIND, "WIDE GRIND", "TUNE" to 0.4f, "GLIDE" to 0f, "DRIVE" to 0.45f, "CUTOFF" to 0.4f, "DECAY" to 0.55f, "SPREAD" to 0.85f),
        p(FathomVoice.GRIND, "DIRTY GROWL", "TUNE" to 0.2f, "GLIDE" to 0.15f, "DRIVE" to 0.7f, "CUTOFF" to 0.5f, "DECAY" to 0.45f, "SPREAD" to 0.4f),
        p(FathomVoice.GRIND, "THROB", "TUNE" to 0.45f, "GLIDE" to 0.3f, "DRIVE" to 0.3f, "CUTOFF" to 0.3f, "DECAY" to 0.65f, "SPREAD" to 0.2f),
        p(FathomVoice.GRIND, "BEATING SUB", "TUNE" to 0.3f, "GLIDE" to 0f, "DRIVE" to 0.35f, "CUTOFF" to 0.35f, "DECAY" to 0.6f, "SPREAD" to 0.6f),
        p(FathomVoice.GRIND, "GRAVEL BASS", "TUNE" to 0.5f, "GLIDE" to 0.2f, "DRIVE" to 0.8f, "CUTOFF" to 0.55f, "DECAY" to 0.4f, "SPREAD" to 0.5f),
        p(FathomVoice.GRIND, "SLIDING GRIT", "TUNE" to 0.35f, "GLIDE" to 0.6f, "DRIVE" to 0.5f, "CUTOFF" to 0.4f, "DECAY" to 0.5f, "SPREAD" to 0.45f),
        p(FathomVoice.GRIND, "LOUD GRIND", "TUNE" to 0.55f, "GLIDE" to 0f, "DRIVE" to 0.9f, "CUTOFF" to 0.6f, "DECAY" to 0.3f, "SPREAD" to 0.3f),
        p(FathomVoice.GRIND, "SOFT GROWL", "TUNE" to 0.15f, "GLIDE" to 0.1f, "DRIVE" to 0.2f, "CUTOFF" to 0.25f, "DECAY" to 0.75f, "SPREAD" to 0.35f),
        p(FathomVoice.GRIND, "DETUNED GRIT", "TUNE" to 0.4f, "GLIDE" to 0.05f, "DRIVE" to 0.55f, "CUTOFF" to 0.45f, "DECAY" to 0.5f, "SPREAD" to 0.95f),
    )

    private val glassPresets = listOf(
        p(FathomVoice.GLASS, "METAL SUB", "TUNE" to 0.3f, "GLIDE" to 0f, "DRIVE" to 0.4f, "CUTOFF" to 0.4f, "DECAY" to 0.5f, "RATIO" to 0.2f),
        p(FathomVoice.GLASS, "GLASSY LOW", "TUNE" to 0.25f, "GLIDE" to 0f, "DRIVE" to 0.3f, "CUTOFF" to 0.35f, "DECAY" to 0.55f, "RATIO" to 0.35f),
        p(FathomVoice.GLASS, "BELL BASS", "TUNE" to 0.35f, "GLIDE" to 0.1f, "DRIVE" to 0.5f, "CUTOFF" to 0.45f, "DECAY" to 0.45f, "RATIO" to 0.5f),
        p(FathomVoice.GLASS, "FM GROWL", "TUNE" to 0.4f, "GLIDE" to 0f, "DRIVE" to 0.6f, "CUTOFF" to 0.5f, "DECAY" to 0.4f, "RATIO" to 0.6f),
        p(FathomVoice.GLASS, "RINGING SUB", "TUNE" to 0.2f, "GLIDE" to 0.2f, "DRIVE" to 0.35f, "CUTOFF" to 0.3f, "DECAY" to 0.6f, "RATIO" to 0.4f),
        p(FathomVoice.GLASS, "HARSH FM", "TUNE" to 0.5f, "GLIDE" to 0f, "DRIVE" to 0.8f, "CUTOFF" to 0.6f, "DECAY" to 0.3f, "RATIO" to 0.75f),
        p(FathomVoice.GLASS, "GLIDE BELL", "TUNE" to 0.3f, "GLIDE" to 0.5f, "DRIVE" to 0.45f, "CUTOFF" to 0.4f, "DECAY" to 0.5f, "RATIO" to 0.3f),
        p(FathomVoice.GLASS, "SUBOCTAVE FM", "TUNE" to 0.15f, "GLIDE" to 0f, "DRIVE" to 0.25f, "CUTOFF" to 0.25f, "DECAY" to 0.65f, "RATIO" to 0f),
        p(FathomVoice.GLASS, "CLANGY BASS", "TUNE" to 0.45f, "GLIDE" to 0.15f, "DRIVE" to 0.65f, "CUTOFF" to 0.55f, "DECAY" to 0.35f, "RATIO" to 0.85f),
        p(FathomVoice.GLASS, "MELLOW FM", "TUNE" to 0.6f, "GLIDE" to 0.4f, "DRIVE" to 0.1f, "CUTOFF" to 0.55f, "DECAY" to 0.3f, "RATIO" to 0.1f),
        p(FathomVoice.GLASS, "UNSTABLE FM", "TUNE" to 0.55f, "GLIDE" to 0.35f, "DRIVE" to 0.7f, "CUTOFF" to 0.5f, "DECAY" to 0.4f, "RATIO" to 0.95f),
        p(FathomVoice.GLASS, "TWELFTH BELL", "TUNE" to 0.4f, "GLIDE" to 0f, "DRIVE" to 0.5f, "CUTOFF" to 0.45f, "DECAY" to 0.45f, "RATIO" to 1f),
    )
}
