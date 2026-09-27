package com.snipsnap.synth

/**
 * SIREN's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md),
 * SIREN's turn.
 *
 * Ten per voice, forty total, spread across RATE, DEPTH, SWEEP (the dive
 * and the climb), GRIT and HOLD, with one LOOP per voice — the render the
 * SURFACE holds under a finger — at the end of each list. Named for what
 * they sound like, never for a maker or a box, per the naming rule; the
 * classifier's reading of them is measured in [SirenPresetsTest].
 */
object SirenPresets {

    private fun p(voice: SirenVoice, name: String, vararg macros: Pair<String, Float>) =
        SirenPatch(name, voice, macros.toMap())

    fun forVoice(voice: SirenVoice): List<SirenPatch> = when (voice) {
        SirenVoice.WAIL -> wailPresets
        SirenVoice.TRILL -> trillPresets
        SirenVoice.LASER -> laserPresets
        SirenVoice.BIRD -> birdPresets
    }

    fun all(): List<SirenPatch> = SirenVoice.entries.flatMap { forVoice(it) }

    private val wailPresets = listOf(
        p(SirenVoice.WAIL, "AIR RAID", "TUNE" to 0.5f, "RATE" to 0.15f, "DEPTH" to 0.5f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.7f),
        p(SirenVoice.WAIL, "SLOW DAWN", "TUNE" to 0.35f, "RATE" to 0.08f, "DEPTH" to 0.6f, "SWEEP" to 0.5f, "GRIT" to 0.35f, "HOLD" to 0.9f),
        p(SirenVoice.WAIL, "FOG HORN", "TUNE" to 0.15f, "RATE" to 0.12f, "DEPTH" to 0.3f, "SWEEP" to 0.5f, "GRIT" to 0.7f, "HOLD" to 0.6f),
        p(SirenVoice.WAIL, "HIGH ALARM", "TUNE" to 0.85f, "RATE" to 0.3f, "DEPTH" to 0.4f, "SWEEP" to 0.5f, "GRIT" to 0.6f, "HOLD" to 0.5f),
        p(SirenVoice.WAIL, "WIDE WAIL", "TUNE" to 0.5f, "RATE" to 0.2f, "DEPTH" to 0.9f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.75f),
        p(SirenVoice.WAIL, "DIVE WAIL", "TUNE" to 0.5f, "RATE" to 0.15f, "DEPTH" to 0.5f, "SWEEP" to 0.15f, "GRIT" to 0.5f, "HOLD" to 0.6f),
        p(SirenVoice.WAIL, "RISE WAIL", "TUNE" to 0.5f, "RATE" to 0.15f, "DEPTH" to 0.5f, "SWEEP" to 0.85f, "GRIT" to 0.5f, "HOLD" to 0.6f),
        p(SirenVoice.WAIL, "SOFT MOAN", "TUNE" to 0.3f, "RATE" to 0.1f, "DEPTH" to 0.35f, "SWEEP" to 0.5f, "GRIT" to 0.1f, "HOLD" to 0.8f),
        p(SirenVoice.WAIL, "HARSH WAIL", "TUNE" to 0.6f, "RATE" to 0.25f, "DEPTH" to 0.55f, "SWEEP" to 0.5f, "GRIT" to 0.95f, "HOLD" to 0.5f),
        p(SirenVoice.WAIL, "WAIL LOOP", "TUNE" to 0.5f, "RATE" to 0.15f, "DEPTH" to 0.5f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 1f),
    )

    private val trillPresets = listOf(
        p(SirenVoice.TRILL, "TWO TONE", "TUNE" to 0.5f, "RATE" to 0.69f, "DEPTH" to 0.21f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.TRILL, "PATROL", "TUNE" to 0.6f, "RATE" to 0.6f, "DEPTH" to 0.3f, "SWEEP" to 0.5f, "GRIT" to 0.6f, "HOLD" to 0.7f),
        p(SirenVoice.TRILL, "PHONE RING", "TUNE" to 0.7f, "RATE" to 0.85f, "DEPTH" to 0.12f, "SWEEP" to 0.5f, "GRIT" to 0.4f, "HOLD" to 0.4f),
        p(SirenVoice.TRILL, "SLOW BELL", "TUNE" to 0.4f, "RATE" to 0.35f, "DEPTH" to 0.25f, "SWEEP" to 0.5f, "GRIT" to 0.3f, "HOLD" to 0.8f),
        p(SirenVoice.TRILL, "WIDE TRILL", "TUNE" to 0.5f, "RATE" to 0.65f, "DEPTH" to 0.7f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.TRILL, "HARD TRILL", "TUNE" to 0.55f, "RATE" to 0.75f, "DEPTH" to 0.2f, "SWEEP" to 0.5f, "GRIT" to 0.95f, "HOLD" to 0.45f),
        p(SirenVoice.TRILL, "DIVE TRILL", "TUNE" to 0.5f, "RATE" to 0.7f, "DEPTH" to 0.2f, "SWEEP" to 0.15f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.TRILL, "RISE TRILL", "TUNE" to 0.5f, "RATE" to 0.7f, "DEPTH" to 0.2f, "SWEEP" to 0.85f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.TRILL, "LOW BUZZER", "TUNE" to 0.1f, "RATE" to 0.9f, "DEPTH" to 0.08f, "SWEEP" to 0.5f, "GRIT" to 0.7f, "HOLD" to 0.35f),
        p(SirenVoice.TRILL, "TRILL LOOP", "TUNE" to 0.5f, "RATE" to 0.69f, "DEPTH" to 0.21f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 1f),
    )

    private val laserPresets = listOf(
        p(SirenVoice.LASER, "RAY GUN", "TUNE" to 0.5f, "RATE" to 0.75f, "DEPTH" to 0.75f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.4f),
        p(SirenVoice.LASER, "SLOW ZAP", "TUNE" to 0.5f, "RATE" to 0.45f, "DEPTH" to 0.8f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.6f),
        p(SirenVoice.LASER, "FAST ZAP", "TUNE" to 0.6f, "RATE" to 0.95f, "DEPTH" to 0.6f, "SWEEP" to 0.5f, "GRIT" to 0.6f, "HOLD" to 0.3f),
        p(SirenVoice.LASER, "DEEP LASER", "TUNE" to 0.15f, "RATE" to 0.7f, "DEPTH" to 0.9f, "SWEEP" to 0.5f, "GRIT" to 0.7f, "HOLD" to 0.5f),
        p(SirenVoice.LASER, "SOFT DRIP", "TUNE" to 0.7f, "RATE" to 0.6f, "DEPTH" to 0.35f, "SWEEP" to 0.5f, "GRIT" to 0.15f, "HOLD" to 0.45f),
        p(SirenVoice.LASER, "DIVE LASER", "TUNE" to 0.5f, "RATE" to 0.75f, "DEPTH" to 0.7f, "SWEEP" to 0.1f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.LASER, "RISE LASER", "TUNE" to 0.5f, "RATE" to 0.75f, "DEPTH" to 0.7f, "SWEEP" to 0.9f, "GRIT" to 0.5f, "HOLD" to 0.5f),
        p(SirenVoice.LASER, "HARSH LASER", "TUNE" to 0.55f, "RATE" to 0.8f, "DEPTH" to 0.85f, "SWEEP" to 0.5f, "GRIT" to 0.95f, "HOLD" to 0.4f),
        p(SirenVoice.LASER, "LONG LASER", "TUNE" to 0.4f, "RATE" to 0.65f, "DEPTH" to 0.7f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.9f),
        p(SirenVoice.LASER, "LASER LOOP", "TUNE" to 0.5f, "RATE" to 0.75f, "DEPTH" to 0.75f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 1f),
    )

    private val birdPresets = listOf(
        p(SirenVoice.BIRD, "CHIRP", "TUNE" to 0.5f, "RATE" to 0.8f, "DEPTH" to 0.58f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.35f),
        p(SirenVoice.BIRD, "SLOW BIRD", "TUNE" to 0.5f, "RATE" to 0.5f, "DEPTH" to 0.6f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.6f),
        p(SirenVoice.BIRD, "FAST BIRD", "TUNE" to 0.6f, "RATE" to 0.97f, "DEPTH" to 0.5f, "SWEEP" to 0.5f, "GRIT" to 0.55f, "HOLD" to 0.3f),
        p(SirenVoice.BIRD, "LOW BIRD", "TUNE" to 0.15f, "RATE" to 0.75f, "DEPTH" to 0.7f, "SWEEP" to 0.5f, "GRIT" to 0.6f, "HOLD" to 0.5f),
        p(SirenVoice.BIRD, "SOFT CHIRP", "TUNE" to 0.7f, "RATE" to 0.7f, "DEPTH" to 0.35f, "SWEEP" to 0.5f, "GRIT" to 0.15f, "HOLD" to 0.4f),
        p(SirenVoice.BIRD, "DIVE BIRD", "TUNE" to 0.5f, "RATE" to 0.8f, "DEPTH" to 0.55f, "SWEEP" to 0.1f, "GRIT" to 0.5f, "HOLD" to 0.45f),
        p(SirenVoice.BIRD, "RISE BIRD", "TUNE" to 0.5f, "RATE" to 0.8f, "DEPTH" to 0.55f, "SWEEP" to 0.9f, "GRIT" to 0.5f, "HOLD" to 0.45f),
        p(SirenVoice.BIRD, "HARSH BIRD", "TUNE" to 0.55f, "RATE" to 0.85f, "DEPTH" to 0.8f, "SWEEP" to 0.5f, "GRIT" to 0.95f, "HOLD" to 0.4f),
        p(SirenVoice.BIRD, "LONG BIRD", "TUNE" to 0.4f, "RATE" to 0.6f, "DEPTH" to 0.5f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 0.9f),
        p(SirenVoice.BIRD, "BIRD LOOP", "TUNE" to 0.5f, "RATE" to 0.8f, "DEPTH" to 0.58f, "SWEEP" to 0.5f, "GRIT" to 0.5f, "HOLD" to 1f),
    )
}
