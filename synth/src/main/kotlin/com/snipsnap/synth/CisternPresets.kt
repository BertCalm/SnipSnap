package com.snipsnap.synth

/**
 * Twelve dry starting points for the liquid membrane, following the proposed
 * Cistern roster. Each pair contrasts contact compliance and release timing,
 * rather than relying on strike level to distinguish its two gestures. They
 * need a listening verdict in `generateCisternAudition`; the held circle is a
 * settled replenished loop, with no initiating strike.
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
        p(CisternVoice.FIRST, "First Drop", "STRIKE" to 0.78f, "SUSPENSION" to 0.08f, "DROP" to 0.14f, "SKIN" to 0.88f, "DRAIN" to 0.88f, "HOLD" to 0f),
        p(CisternVoice.FIRST, "Soft Skin", "STRIKE" to 0.22f, "SUSPENSION" to 0.36f, "DROP" to 0.62f, "SKIN" to 0.08f, "DRAIN" to 0.18f, "HOLD" to 0f),
    )
    private val drip = listOf(
        p(CisternVoice.DRIP, "Hanging Rain", "STRIKE" to 0.10f, "SUSPENSION" to 0.76f, "DROP" to 0.68f, "SKIN" to 0.42f, "DRAIN" to 0.72f, "HOLD" to 0f),
        p(CisternVoice.DRIP, "Quiet Reservoir", "STRIKE" to 0.08f, "SUSPENSION" to 0.12f, "DROP" to 0.95f, "SKIN" to 0.12f, "DRAIN" to 0.08f, "HOLD" to 0f),
    )
    private val cascade = listOf(
        p(CisternVoice.CASCADE, "Wide Cascade", "STRIKE" to 0.54f, "SUSPENSION" to 0.94f, "DROP" to 0.26f, "SKIN" to 0.88f, "DRAIN" to 0.80f, "HOLD" to 0f),
        p(CisternVoice.CASCADE, "Heavy Landing", "STRIKE" to 0.82f, "SUSPENSION" to 0.45f, "DROP" to 0.98f, "SKIN" to 0.16f, "DRAIN" to 0.15f, "HOLD" to 0f),
    )
    private val pool = listOf(
        p(CisternVoice.POOL, "Wet Basin", "STRIKE" to 0.50f, "SUSPENSION" to 0.80f, "DROP" to 0.95f, "SKIN" to 0.08f, "DRAIN" to 0.05f, "HOLD" to 0f),
        p(CisternVoice.POOL, "Slow Drain", "STRIKE" to 0.42f, "SUSPENSION" to 0.27f, "DROP" to 0.54f, "SKIN" to 0.70f, "DRAIN" to 0.01f, "HOLD" to 0f),
    )
    private val ripple = listOf(
        p(CisternVoice.RIPPLE, "Thin Ripple", "STRIKE" to 0.20f, "SUSPENSION" to 0.18f, "DROP" to 0.06f, "SKIN" to 0.97f, "DRAIN" to 0.96f, "HOLD" to 0f),
        p(CisternVoice.RIPPLE, "Dense Surface", "STRIKE" to 0.65f, "SUSPENSION" to 0.98f, "DROP" to 0.40f, "SKIN" to 0.55f, "DRAIN" to 0.28f, "HOLD" to 0f),
    )
    private val recovery = listOf(
        p(CisternVoice.RECOVERY, "Clear Return", "STRIKE" to 0.65f, "SUSPENSION" to 0.70f, "DROP" to 0.68f, "SKIN" to 0.84f, "DRAIN" to 0.98f, "HOLD" to 0f),
        p(CisternVoice.RECOVERY, "Replenished Circle", "STRIKE" to 0.15f, "SUSPENSION" to 0.78f, "DROP" to 0.34f, "SKIN" to 0.50f, "DRAIN" to 0.72f, "HOLD" to 1f),
    )
}
