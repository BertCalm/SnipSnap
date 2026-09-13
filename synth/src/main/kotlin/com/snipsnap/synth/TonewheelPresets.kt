package com.snipsnap.synth

/**
 * TONEWHEEL's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (three voices, thirty-six total), each a full
 * twelve-macro registration (TUNE/PERC/WARBLE/DIRT plus all eight
 * BAR1..BAR8 drawbars — `Tonewheel.macrosFor`'s shape). Every preset keeps
 * its voice's own drawbar character close to `Tonewheel.registration()`'s
 * defaults and varies it deliberately (pushing highs, cutting lows,
 * hollowing out even harmonics) rather than drifting arbitrarily, so a
 * voice's twelve presets still read as that voice's own family. Authored
 * from the DSP, not by ear, and checked by [TonewheelPresetsTest]'s
 * sanity/round-trip/spread suite — spread here is computed over all
 * twelve macros at once, so small, deliberate per-bar nudges still add up
 * to real separation.
 */
object TonewheelPresets {

    private fun p(voice: TonewheelVoice, name: String, vararg macros: Pair<String, Float>) =
        TonewheelPatch(name, voice, macros.toMap())

    fun forVoice(voice: TonewheelVoice): List<TonewheelPatch> = when (voice) {
        TonewheelVoice.FULL -> fullPresets
        TonewheelVoice.SOUL -> soulPresets
        TonewheelVoice.STAB -> stabPresets
    }

    fun all(): List<TonewheelPatch> = TonewheelVoice.entries.flatMap { forVoice(it) }

    private fun bars(vararg levels: Float): List<Pair<String, Float>> =
        levels.mapIndexed { i, level -> "BAR${i + 1}" to level }

    // FULL's own default registration: [0.9, 1, 0.8, 0.75, 0.6, 0.55, 0.4, 0.5]
    private val fullPresets = listOf(
        p(TonewheelVoice.FULL, "ALL DRAWBARS", "TUNE" to 0.5f, "PERC" to 0.2f, "WARBLE" to 0.3f, "DIRT" to 0.3f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "FULL CHORUS", "TUNE" to 0.5f, "PERC" to 0.15f, "WARBLE" to 0.6f, "DIRT" to 0.2f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "FULL PERC", "TUNE" to 0.5f, "PERC" to 0.7f, "WARBLE" to 0.3f, "DIRT" to 0.3f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "DIRTY FULL", "TUNE" to 0.5f, "PERC" to 0.2f, "WARBLE" to 0.25f, "DIRT" to 0.7f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "BRIGHT FULL", "TUNE" to 0.6f, "PERC" to 0.3f, "WARBLE" to 0.3f, "DIRT" to 0.3f, *bars(0.6f, 0.8f, 0.5f, 0.9f, 0.85f, 0.9f, 0.7f, 0.85f).toTypedArray()),
        p(TonewheelVoice.FULL, "DARK FULL", "TUNE" to 0.4f, "PERC" to 0.15f, "WARBLE" to 0.35f, "DIRT" to 0.35f, *bars(1f, 1f, 0.9f, 0.6f, 0.3f, 0.2f, 0.1f, 0.15f).toTypedArray()),
        p(TonewheelVoice.FULL, "HOLLOW FULL", "TUNE" to 0.5f, "PERC" to 0.25f, "WARBLE" to 0.4f, "DIRT" to 0.25f, *bars(0.9f, 1f, 0.2f, 0.75f, 0.2f, 0.55f, 0.2f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "THIN FULL", "TUNE" to 0.55f, "PERC" to 0.1f, "WARBLE" to 0.15f, "DIRT" to 0.1f, *bars(0.3f, 0.7f, 0.3f, 0.4f, 0.3f, 0.3f, 0.2f, 0.3f).toTypedArray()),
        p(TonewheelVoice.FULL, "LOUD FULL", "TUNE" to 0.45f, "PERC" to 0.35f, "WARBLE" to 0.35f, "DIRT" to 0.55f, *bars(1f, 1f, 1f, 0.9f, 0.85f, 0.8f, 0.7f, 0.75f).toTypedArray()),
        p(TonewheelVoice.FULL, "HIGH FULL", "TUNE" to 0.75f, "PERC" to 0.3f, "WARBLE" to 0.3f, "DIRT" to 0.3f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "LOW FULL", "TUNE" to 0.2f, "PERC" to 0.2f, "WARBLE" to 0.3f, "DIRT" to 0.3f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
        p(TonewheelVoice.FULL, "WOBBLY FULL", "TUNE" to 0.5f, "PERC" to 0.2f, "WARBLE" to 0.95f, "DIRT" to 0.2f, *bars(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f).toTypedArray()),
    )

    // SOUL's own default registration: [0.7, 1, 0.4, 0.3, 0.15, 0, 0, 0]
    private val soulPresets = listOf(
        p(TonewheelVoice.SOUL, "BALLAD BED", "TUNE" to 0.5f, "PERC" to 0.15f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "WARM SOUL", "TUNE" to 0.45f, "PERC" to 0.1f, "WARBLE" to 0.45f, "DIRT" to 0.05f, *bars(0.8f, 1f, 0.5f, 0.35f, 0.2f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "SOFT PADS", "TUNE" to 0.5f, "PERC" to 0.05f, "WARBLE" to 0.5f, "DIRT" to 0.05f, *bars(0.6f, 1f, 0.3f, 0.2f, 0.1f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "SLOW BEAT", "TUNE" to 0.55f, "PERC" to 0.2f, "WARBLE" to 0.8f, "DIRT" to 0.1f, *bars(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "GOSPEL BED", "TUNE" to 0.4f, "PERC" to 0.15f, "WARBLE" to 0.35f, "DIRT" to 0.2f, *bars(0.75f, 1f, 0.5f, 0.4f, 0.25f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "FULL CHURCH", "TUNE" to 0.35f, "PERC" to 0.1f, "WARBLE" to 0.3f, "DIRT" to 0.15f, *bars(0.85f, 1f, 0.6f, 0.5f, 0.3f, 0.1f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "THIN SOUL", "TUNE" to 0.6f, "PERC" to 0.1f, "WARBLE" to 0.2f, "DIRT" to 0.05f, *bars(0.4f, 1f, 0.2f, 0.15f, 0f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "LOW SOUL", "TUNE" to 0.2f, "PERC" to 0.1f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "HIGH SOUL", "TUNE" to 0.7f, "PERC" to 0.3f, "WARBLE" to 0.5f, "DIRT" to 0.3f, *bars(0.5f, 1f, 0.3f, 0.2f, 0.05f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "DRIVEN SOUL", "TUNE" to 0.5f, "PERC" to 0.2f, "WARBLE" to 0.3f, "DIRT" to 0.6f, *bars(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "PERCY SOUL", "TUNE" to 0.5f, "PERC" to 0.55f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f).toTypedArray()),
        p(TonewheelVoice.SOUL, "QUIET SOUL", "TUNE" to 0.45f, "PERC" to 0.05f, "WARBLE" to 0.15f, "DIRT" to 0.05f, *bars(0.55f, 1f, 0.25f, 0.15f, 0f, 0f, 0f, 0f).toTypedArray()),
    )

    // STAB's own default registration: [0.3, 0.9, 0.2, 0.85, 0.55, 0.8, 0.3, 0.7]
    private val stabPresets = listOf(
        p(TonewheelVoice.STAB, "HORN STAB", "TUNE" to 0.5f, "PERC" to 0.55f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "SHARP STAB", "TUNE" to 0.55f, "PERC" to 0.7f, "WARBLE" to 0.25f, "DIRT" to 0.2f, *bars(0.2f, 0.9f, 0.15f, 0.9f, 0.7f, 0.9f, 0.5f, 0.85f).toTypedArray()),
        p(TonewheelVoice.STAB, "FUNKY STAB", "TUNE" to 0.3f, "PERC" to 0.8f, "WARBLE" to 0.15f, "DIRT" to 0.4f, *bars(0.15f, 0.85f, 0.3f, 0.7f, 0.4f, 0.6f, 0.5f, 0.5f).toTypedArray()),
        p(TonewheelVoice.STAB, "BRIGHT STAB", "TUNE" to 0.6f, "PERC" to 0.6f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.2f, 0.8f, 0.1f, 0.95f, 0.8f, 0.95f, 0.6f, 0.9f).toTypedArray()),
        p(TonewheelVoice.STAB, "DIRTY STAB", "TUNE" to 0.5f, "PERC" to 0.6f, "WARBLE" to 0.3f, "DIRT" to 0.6f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "SOFT STAB", "TUNE" to 0.4f, "PERC" to 0.35f, "WARBLE" to 0.4f, "DIRT" to 0.05f, *bars(0.4f, 0.9f, 0.3f, 0.6f, 0.4f, 0.5f, 0.2f, 0.5f).toTypedArray()),
        p(TonewheelVoice.STAB, "LOW STAB", "TUNE" to 0.25f, "PERC" to 0.5f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "HIGH STAB", "TUNE" to 0.75f, "PERC" to 0.55f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "HOLLOW STAB", "TUNE" to 0.5f, "PERC" to 0.55f, "WARBLE" to 0.3f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0f, 0.85f, 0f, 0.8f, 0f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "WARBLE STAB", "TUNE" to 0.5f, "PERC" to 0.5f, "WARBLE" to 0.9f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "FAST CLICK", "TUNE" to 0.55f, "PERC" to 0.9f, "WARBLE" to 0.25f, "DIRT" to 0.15f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
        p(TonewheelVoice.STAB, "NO CLICK", "TUNE" to 0.45f, "PERC" to 0.05f, "WARBLE" to 0.35f, "DIRT" to 0.1f, *bars(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f).toTypedArray()),
    )
}
