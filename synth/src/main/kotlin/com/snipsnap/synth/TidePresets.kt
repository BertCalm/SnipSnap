package com.snipsnap.synth

/**
 * TIDE's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md),
 * TIDE's turn.
 *
 * Ten per voice, forty total, spread across FOLD (clean → glassy → snarl),
 * WARP (the growl), DECAY (the gate's length) and, on GONG and FLARE, the
 * snapped RATIO. Authored from `Tide.kt`'s DSP, not by ear, and checked by
 * [TidePresetsTest]. Named for what they sound like — never for a maker or
 * a module — per the naming rule.
 */
object TidePresets {

    private fun p(voice: TideVoice, name: String, vararg macros: Pair<String, Float>) =
        TidePatch(name, voice, macros.toMap())

    fun forVoice(voice: TideVoice): List<TidePatch> = when (voice) {
        TideVoice.BONGO -> bongoPresets
        TideVoice.DRIP -> dripPresets
        TideVoice.GONG -> gongPresets
        TideVoice.FLARE -> flarePresets
    }

    fun all(): List<TidePatch> = TideVoice.entries.flatMap { forVoice(it) }

    private val bongoPresets = listOf(
        p(TideVoice.BONGO, "WOOD BONGO", "TUNE" to 0.5f, "FOLD" to 0.3f, "WARP" to 0.2f, "DECAY" to 0.35f, "WANDER" to 0.3f),
        p(TideVoice.BONGO, "LOW CONGA", "TUNE" to 0.2f, "FOLD" to 0.25f, "WARP" to 0.1f, "DECAY" to 0.5f, "WANDER" to 0.25f),
        p(TideVoice.BONGO, "HIGH SLAP", "TUNE" to 0.8f, "FOLD" to 0.5f, "WARP" to 0.3f, "DECAY" to 0.15f, "WANDER" to 0.35f),
        p(TideVoice.BONGO, "SOFT KNOCK", "TUNE" to 0.4f, "FOLD" to 0.05f, "WARP" to 0.05f, "DECAY" to 0.3f, "WANDER" to 0.2f),
        p(TideVoice.BONGO, "GLASS TAP", "TUNE" to 0.9f, "FOLD" to 0.7f, "WARP" to 0.1f, "DECAY" to 0.2f, "WANDER" to 0.3f),
        p(TideVoice.BONGO, "RUBBER DRUM", "TUNE" to 0.3f, "FOLD" to 0.45f, "WARP" to 0.5f, "DECAY" to 0.45f, "WANDER" to 0.4f),
        p(TideVoice.BONGO, "WET POP", "TUNE" to 0.6f, "FOLD" to 0.35f, "WARP" to 0.6f, "DECAY" to 0.1f, "WANDER" to 0.3f),
        p(TideVoice.BONGO, "LOG DRUM", "TUNE" to 0.15f, "FOLD" to 0.2f, "WARP" to 0.25f, "DECAY" to 0.65f, "WANDER" to 0.2f),
        p(TideVoice.BONGO, "SNARL TOM", "TUNE" to 0.35f, "FOLD" to 0.9f, "WARP" to 0.4f, "DECAY" to 0.4f, "WANDER" to 0.5f),
        p(TideVoice.BONGO, "WANDER HAND", "TUNE" to 0.55f, "FOLD" to 0.4f, "WARP" to 0.3f, "DECAY" to 0.3f, "WANDER" to 0.9f),
    )

    private val dripPresets = listOf(
        p(TideVoice.DRIP, "RAIN DRIP", "TUNE" to 0.5f, "FOLD" to 0.2f, "WARP" to 0.3f, "DECAY" to 0.3f, "WANDER" to 0.3f),
        p(TideVoice.DRIP, "TAP WATER", "TUNE" to 0.35f, "FOLD" to 0.1f, "WARP" to 0.2f, "DECAY" to 0.2f, "WANDER" to 0.4f),
        p(TideVoice.DRIP, "BUBBLE", "TUNE" to 0.2f, "FOLD" to 0.05f, "WARP" to 0.1f, "DECAY" to 0.4f, "WANDER" to 0.3f),
        p(TideVoice.DRIP, "ICE BLIP", "TUNE" to 0.8f, "FOLD" to 0.5f, "WARP" to 0.2f, "DECAY" to 0.15f, "WANDER" to 0.2f),
        p(TideVoice.DRIP, "CAVE DROP", "TUNE" to 0.4f, "FOLD" to 0.15f, "WARP" to 0.35f, "DECAY" to 0.7f, "WANDER" to 0.35f),
        p(TideVoice.DRIP, "DEW", "TUNE" to 0.65f, "FOLD" to 0f, "WARP" to 0.05f, "DECAY" to 0.25f, "WANDER" to 0.2f),
        p(TideVoice.DRIP, "CRYSTAL", "TUNE" to 0.9f, "FOLD" to 0.65f, "WARP" to 0.4f, "DECAY" to 0.35f, "WANDER" to 0.25f),
        p(TideVoice.DRIP, "SPLASH TICK", "TUNE" to 0.55f, "FOLD" to 0.4f, "WARP" to 0.7f, "DECAY" to 0.05f, "WANDER" to 0.5f),
        p(TideVoice.DRIP, "LEAKY PIPE", "TUNE" to 0.3f, "FOLD" to 0.3f, "WARP" to 0.5f, "DECAY" to 0.3f, "WANDER" to 0.8f),
        p(TideVoice.DRIP, "SONAR", "TUNE" to 0.45f, "FOLD" to 0.1f, "WARP" to 0f, "DECAY" to 0.85f, "WANDER" to 0.1f),
    )

    private val gongPresets = listOf(
        p(TideVoice.GONG, "TEMPLE GONG", "TUNE" to 0.4f, "FOLD" to 0.15f, "WARP" to 0.45f, "RATIO" to 0.5f, "DECAY" to 0.6f, "WANDER" to 0.25f),
        p(TideVoice.GONG, "BRASS BOWL", "TUNE" to 0.6f, "FOLD" to 0.1f, "WARP" to 0.35f, "RATIO" to 0.25f, "DECAY" to 0.65f, "WANDER" to 0.2f),
        p(TideVoice.GONG, "TIN CAN", "TUNE" to 0.7f, "FOLD" to 0.45f, "WARP" to 0.6f, "RATIO" to 1f, "DECAY" to 0.2f, "WANDER" to 0.3f),
        p(TideVoice.GONG, "DARK METAL", "TUNE" to 0.1f, "FOLD" to 0.3f, "WARP" to 0.5f, "RATIO" to 0.75f, "DECAY" to 0.55f, "WANDER" to 0.3f),
        p(TideVoice.GONG, "BELL TREE", "TUNE" to 0.85f, "FOLD" to 0.2f, "WARP" to 0.3f, "RATIO" to 0f, "DECAY" to 0.5f, "WANDER" to 0.35f),
        p(TideVoice.GONG, "SHIP BELL", "TUNE" to 0.45f, "FOLD" to 0.05f, "WARP" to 0.4f, "RATIO" to 0.25f, "DECAY" to 0.8f, "WANDER" to 0.15f),
        p(TideVoice.GONG, "SCRAP HIT", "TUNE" to 0.3f, "FOLD" to 0.7f, "WARP" to 0.8f, "RATIO" to 1f, "DECAY" to 0.3f, "WANDER" to 0.5f),
        p(TideVoice.GONG, "WIND CHIME", "TUNE" to 1f, "FOLD" to 0.1f, "WARP" to 0.25f, "RATIO" to 0.5f, "DECAY" to 0.45f, "WANDER" to 0.6f),
        p(TideVoice.GONG, "SOFT MALLET", "TUNE" to 0.35f, "FOLD" to 0f, "WARP" to 0.2f, "RATIO" to 0f, "DECAY" to 0.65f, "WANDER" to 0.2f),
        p(TideVoice.GONG, "ANVIL", "TUNE" to 0.55f, "FOLD" to 0.55f, "WARP" to 0.7f, "RATIO" to 0.75f, "DECAY" to 0.35f, "WANDER" to 0.25f),
    )

    private val flarePresets = listOf(
        p(TideVoice.FLARE, "SNARL FLARE", "TUNE" to 0.5f, "FOLD" to 0.6f, "WARP" to 0.3f, "RATIO" to 0f, "DECAY" to 0.5f, "WANDER" to 0.2f),
        p(TideVoice.FLARE, "BLOOM BRASS", "TUNE" to 0.55f, "FOLD" to 0.75f, "WARP" to 0.15f, "RATIO" to 0f, "DECAY" to 0.6f, "WANDER" to 0.15f),
        p(TideVoice.FLARE, "OCTAVE GROWL", "TUNE" to 0.3f, "FOLD" to 0.55f, "WARP" to 0.5f, "RATIO" to 0.34f, "DECAY" to 0.5f, "WANDER" to 0.25f),
        p(TideVoice.FLARE, "SOFT FLUTE", "TUNE" to 0.8f, "FOLD" to 0.15f, "WARP" to 0.05f, "RATIO" to 0f, "DECAY" to 0.55f, "WANDER" to 0.2f),
        p(TideVoice.FLARE, "FOLD BASS", "TUNE" to 0.1f, "FOLD" to 0.7f, "WARP" to 0.2f, "RATIO" to 0f, "DECAY" to 0.45f, "WANDER" to 0.2f),
        p(TideVoice.FLARE, "REED STAB", "TUNE" to 0.6f, "FOLD" to 0.5f, "WARP" to 0.4f, "RATIO" to 0.67f, "DECAY" to 0.25f, "WANDER" to 0.3f),
        p(TideVoice.FLARE, "HOLLOW HORN", "TUNE" to 0.45f, "FOLD" to 0.4f, "WARP" to 0.35f, "RATIO" to 0.34f, "DECAY" to 0.7f, "WANDER" to 0.2f),
        p(TideVoice.FLARE, "BUZZ SAW", "TUNE" to 0.35f, "FOLD" to 1f, "WARP" to 0.6f, "RATIO" to 1f, "DECAY" to 0.4f, "WANDER" to 0.3f),
        p(TideVoice.FLARE, "VOWEL LEAD", "TUNE" to 0.7f, "FOLD" to 0.65f, "WARP" to 0.25f, "RATIO" to 0.34f, "DECAY" to 0.55f, "WANDER" to 0.5f),
        p(TideVoice.FLARE, "SLOW SUNRISE", "TUNE" to 0.5f, "FOLD" to 0.85f, "WARP" to 0.1f, "RATIO" to 0f, "DECAY" to 0.95f, "WANDER" to 0.35f),
    )
}
