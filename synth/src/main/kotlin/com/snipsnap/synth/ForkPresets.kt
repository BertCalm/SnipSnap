package com.snipsnap.synth

/**
 * FORK's factory presets — the roadmap's own rule (U1 of
 * `docs/SYNTH_UPGRADE.md`): preset-first, knobs-second. Eight per voice,
 * thirty-two total, spread across STRIKE, BARK, STIFF and DECAY so each
 * bar's whole range gets heard rather than clustering near the defaults.
 * Named for what they sound like, never for a maker — the naming rule
 * `PresetTestSupport.trademarkBlocklist` now checks for the electric
 * piano makers directly.
 */
object ForkPresets {

    private fun p(voice: ForkVoice, name: String, vararg macros: Pair<String, Float>) =
        ForkPatch(name, voice, macros.toMap())

    fun forVoice(voice: ForkVoice): List<ForkPatch> = when (voice) {
        ForkVoice.TINE -> tinePresets
        ForkVoice.BAR -> barPresets
        ForkVoice.NODE -> nodePresets
        ForkVoice.REED -> reedPresets
    }

    fun all(): List<ForkPatch> = ForkVoice.entries.flatMap { forVoice(it) }

    private val tinePresets = listOf(
        p(ForkVoice.TINE, "DINNER JAZZ", "TUNE" to 0.5f, "STRIKE" to 0.5f, "BARK" to 0.4f, "STIFF" to 0.5f, "DECAY" to 0.5f),
        p(ForkVoice.TINE, "GLASS TINE", "TUNE" to 0.6f, "STRIKE" to 0.3f, "BARK" to 0.15f, "STIFF" to 0.75f, "DECAY" to 0.6f),
        p(ForkVoice.TINE, "HARD BARK", "TUNE" to 0.5f, "STRIKE" to 0.85f, "BARK" to 0.9f, "STIFF" to 0.5f, "DECAY" to 0.4f),
        p(ForkVoice.TINE, "SOFT KEYS", "TUNE" to 0.4f, "STRIKE" to 0.15f, "BARK" to 0.2f, "STIFF" to 0.45f, "DECAY" to 0.55f),
        p(ForkVoice.TINE, "LONG RING", "TUNE" to 0.3f, "STRIKE" to 0.4f, "BARK" to 0.35f, "STIFF" to 0.5f, "DECAY" to 0.95f),
        p(ForkVoice.TINE, "STRING TONE", "TUNE" to 0.55f, "STRIKE" to 0.5f, "BARK" to 0.3f, "STIFF" to 0.05f, "DECAY" to 0.5f),
        p(ForkVoice.TINE, "TIGHT PLUCK", "TUNE" to 0.7f, "STRIKE" to 0.9f, "BARK" to 0.5f, "STIFF" to 0.6f, "DECAY" to 0.1f),
        p(ForkVoice.TINE, "WARM LOW", "TUNE" to 0.1f, "STRIKE" to 0.35f, "BARK" to 0.45f, "STIFF" to 0.4f, "DECAY" to 0.6f),
    )

    private val barPresets = listOf(
        p(ForkVoice.BAR, "VIBE BELL", "TUNE" to 0.5f, "STRIKE" to 0.5f, "BARK" to 0.4f, "STIFF" to 0.5f, "DECAY" to 0.6f),
        p(ForkVoice.BAR, "COLD METAL", "TUNE" to 0.65f, "STRIKE" to 0.6f, "BARK" to 0.5f, "STIFF" to 0.9f, "DECAY" to 0.5f),
        p(ForkVoice.BAR, "MALLET RING", "TUNE" to 0.35f, "STRIKE" to 0.3f, "BARK" to 0.25f, "STIFF" to 0.55f, "DECAY" to 0.9f),
        p(ForkVoice.BAR, "BRIGHT CHIME", "TUNE" to 0.7f, "STRIKE" to 0.55f, "BARK" to 0.85f, "STIFF" to 0.6f, "DECAY" to 0.45f),
        p(ForkVoice.BAR, "DEEP BAR", "TUNE" to 0.05f, "STRIKE" to 0.4f, "BARK" to 0.3f, "STIFF" to 0.45f, "DECAY" to 0.7f),
        p(ForkVoice.BAR, "SHORT KNOCK", "TUNE" to 0.55f, "STRIKE" to 0.8f, "BARK" to 0.6f, "STIFF" to 0.65f, "DECAY" to 0.1f),
        p(ForkVoice.BAR, "ROUND TONE", "TUNE" to 0.45f, "STRIKE" to 0.35f, "BARK" to 0.1f, "STIFF" to 0.2f, "DECAY" to 0.5f),
        p(ForkVoice.BAR, "LOUD CLANG", "TUNE" to 0.6f, "STRIKE" to 0.95f, "BARK" to 0.95f, "STIFF" to 0.95f, "DECAY" to 0.35f),
    )

    /**
     * NODE's own eight — the same cantilever tine as TINE, read at its
     * second mode's own node (round two's audition follow-up: neither TINE
     * nor BAR read as "piano" outright, but TINE read closer). Named for the
     * purer, more fundamental-forward tone that reading gives, not for
     * having "solved" the piano question — that is still the audition's own
     * call to make.
     */
    private val nodePresets = listOf(
        p(ForkVoice.NODE, "WARM NOTE", "TUNE" to 0.5f, "STRIKE" to 0.45f, "BARK" to 0.35f, "STIFF" to 0.5f, "DECAY" to 0.5f),
        p(ForkVoice.NODE, "SOFT PIANO", "TUNE" to 0.4f, "STRIKE" to 0.15f, "BARK" to 0.2f, "STIFF" to 0.45f, "DECAY" to 0.55f),
        p(ForkVoice.NODE, "PURE TONE", "TUNE" to 0.5f, "STRIKE" to 0.3f, "BARK" to 0.15f, "STIFF" to 0.5f, "DECAY" to 0.6f),
        p(ForkVoice.NODE, "ROUND KEY", "TUNE" to 0.35f, "STRIKE" to 0.4f, "BARK" to 0.3f, "STIFF" to 0.4f, "DECAY" to 0.65f),
        p(ForkVoice.NODE, "MELLOW RING", "TUNE" to 0.3f, "STRIKE" to 0.35f, "BARK" to 0.25f, "STIFF" to 0.5f, "DECAY" to 0.9f),
        p(ForkVoice.NODE, "CLEAN STRIKE", "TUNE" to 0.6f, "STRIKE" to 0.7f, "BARK" to 0.5f, "STIFF" to 0.55f, "DECAY" to 0.35f),
        p(ForkVoice.NODE, "DEEP WARMTH", "TUNE" to 0.1f, "STRIKE" to 0.3f, "BARK" to 0.4f, "STIFF" to 0.45f, "DECAY" to 0.6f),
        p(ForkVoice.NODE, "BRIGHT NOTE", "TUNE" to 0.65f, "STRIKE" to 0.85f, "BARK" to 0.7f, "STIFF" to 0.6f, "DECAY" to 0.3f),
    )

    /**
     * REED's own eight — round four's second electric-piano family, a
     * Wurlitzer-style electrostatic comb pickup rather than a Rhodes-style
     * magnetic one: a symmetric, odd-harmonic-dominant drive in place of
     * TINE/BAR/NODE's asymmetric bark, plus a discrete mechanical-contact
     * rattle at hard enough strikes and a close enough plate (high STRIKE,
     * high BARK). Spread across both ends of that: some presets stay under
     * the rattle's own threshold entirely (a clean, gentler reed tone),
     * others are chosen specifically to cross it.
     */
    private val reedPresets = listOf(
        p(ForkVoice.REED, "REED TONE", "TUNE" to 0.5f, "STRIKE" to 0.5f, "BARK" to 0.4f, "STIFF" to 0.5f, "DECAY" to 0.5f),
        p(ForkVoice.REED, "SOFT BUZZ", "TUNE" to 0.4f, "STRIKE" to 0.2f, "BARK" to 0.3f, "STIFF" to 0.45f, "DECAY" to 0.55f),
        p(ForkVoice.REED, "HARD RATTLE", "TUNE" to 0.5f, "STRIKE" to 0.9f, "BARK" to 0.85f, "STIFF" to 0.5f, "DECAY" to 0.45f),
        p(ForkVoice.REED, "MELLOW REED", "TUNE" to 0.3f, "STRIKE" to 0.35f, "BARK" to 0.2f, "STIFF" to 0.5f, "DECAY" to 0.9f),
        p(ForkVoice.REED, "BRIGHT BUZZ", "TUNE" to 0.65f, "STRIKE" to 0.75f, "BARK" to 0.6f, "STIFF" to 0.6f, "DECAY" to 0.4f),
        p(ForkVoice.REED, "LOOSE PLATE", "TUNE" to 0.55f, "STRIKE" to 0.6f, "BARK" to 0.95f, "STIFF" to 0.55f, "DECAY" to 0.5f),
        p(ForkVoice.REED, "DEEP REED", "TUNE" to 0.1f, "STRIKE" to 0.4f, "BARK" to 0.35f, "STIFF" to 0.4f, "DECAY" to 0.6f),
        p(ForkVoice.REED, "SHORT REED", "TUNE" to 0.6f, "STRIKE" to 0.8f, "BARK" to 0.75f, "STIFF" to 0.65f, "DECAY" to 0.15f),
    )
}
