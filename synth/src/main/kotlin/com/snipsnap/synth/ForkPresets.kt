package com.snipsnap.synth

/**
 * FORK's factory presets — the roadmap's own rule (U1 of
 * `docs/SYNTH_UPGRADE.md`): preset-first, knobs-second. Eight per voice,
 * sixteen total, spread across STRIKE, BARK, STIFF and DECAY so the two
 * bars' whole range gets heard rather than clustering near the defaults.
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
}
