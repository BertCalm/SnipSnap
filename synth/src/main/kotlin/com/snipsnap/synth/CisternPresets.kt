package com.snipsnap.synth

/**
 * Twelve dry starting points for the liquid membrane, following the proposed
 * Cistern roster. They need a listening verdict in `generateCisternAudition`;
 * the held circle is a settled replenished loop, with no initiating strike.
 */
object CisternPresets {
    private fun p(voice: CisternVoice, name: String, vararg macros: Pair<String, Float>) =
        CisternPatch(name, voice, macros.toMap())

    fun forVoice(voice: CisternVoice): List<CisternPatch> = when (voice) {
        CisternVoice.FIRST -> first
        CisternVoice.DRIP -> drip
        CisternVoice.CASCADE -> cascade
        CisternVoice.POOL -> pool
        CisternVoice.RIPPLE -> ripple
        CisternVoice.RECOVERY -> recovery
    }

    fun all(): List<CisternPatch> = CisternVoice.entries.flatMap { forVoice(it) }

    private val first = listOf(
        p(CisternVoice.FIRST, "First Drop", "STRIKE" to 0.55f, "SUSPENSION" to 0.25f, "DROP" to 0.30f, "SKIN" to 0.65f, "DRAIN" to 0.65f, "HOLD" to 0f),
        p(CisternVoice.FIRST, "Soft Skin", "STRIKE" to 0.20f, "SUSPENSION" to 0.30f, "DROP" to 0.35f, "SKIN" to 0.20f, "DRAIN" to 0.55f, "HOLD" to 0f),
    )
    private val drip = listOf(
        p(CisternVoice.DRIP, "Hanging Rain", "STRIKE" to 0.35f, "SUSPENSION" to 0.45f, "DROP" to 0.50f, "SKIN" to 0.55f, "DRAIN" to 0.50f, "HOLD" to 0f),
        p(CisternVoice.DRIP, "Quiet Reservoir", "STRIKE" to 0.18f, "SUSPENSION" to 0.25f, "DROP" to 0.32f, "SKIN" to 0.45f, "DRAIN" to 0.60f, "HOLD" to 0f),
    )
    private val cascade = listOf(
        p(CisternVoice.CASCADE, "Wide Cascade", "STRIKE" to 0.65f, "SUSPENSION" to 0.90f, "DROP" to 0.45f, "SKIN" to 0.65f, "DRAIN" to 0.50f, "HOLD" to 0f),
        p(CisternVoice.CASCADE, "Heavy Landing", "STRIKE" to 0.75f, "SUSPENSION" to 0.70f, "DROP" to 0.85f, "SKIN" to 0.45f, "DRAIN" to 0.35f, "HOLD" to 0f),
    )
    private val pool = listOf(
        p(CisternVoice.POOL, "Wet Basin", "STRIKE" to 0.50f, "SUSPENSION" to 0.65f, "DROP" to 0.90f, "SKIN" to 0.30f, "DRAIN" to 0.20f, "HOLD" to 0f),
        p(CisternVoice.POOL, "Slow Drain", "STRIKE" to 0.40f, "SUSPENSION" to 0.50f, "DROP" to 0.70f, "SKIN" to 0.45f, "DRAIN" to 0.05f, "HOLD" to 0f),
    )
    private val ripple = listOf(
        p(CisternVoice.RIPPLE, "Thin Ripple", "STRIKE" to 0.45f, "SUSPENSION" to 0.65f, "DROP" to 0.15f, "SKIN" to 0.90f, "DRAIN" to 0.75f, "HOLD" to 0f),
        p(CisternVoice.RIPPLE, "Dense Surface", "STRIKE" to 0.65f, "SUSPENSION" to 0.95f, "DROP" to 0.35f, "SKIN" to 0.75f, "DRAIN" to 0.55f, "HOLD" to 0f),
    )
    private val recovery = listOf(
        p(CisternVoice.RECOVERY, "Clear Return", "STRIKE" to 0.55f, "SUSPENSION" to 0.60f, "DROP" to 0.70f, "SKIN" to 0.55f, "DRAIN" to 0.95f, "HOLD" to 0f),
        p(CisternVoice.RECOVERY, "Replenished Circle", "STRIKE" to 0.40f, "SUSPENSION" to 0.65f, "DROP" to 0.50f, "SKIN" to 0.65f, "DRAIN" to 0.70f, "HOLD" to 1f),
    )
}
