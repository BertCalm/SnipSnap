package com.snipsnap.synth

/**
 * VELVET's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (four voices, forty-eight total), spread across
 * TUNE/SHAPE/FAT/CUTOFF/SQUEEZE/DECAY. Authored from `Velvet.kt`'s DSP
 * (SHAPE's saw-to-PWM crossfade, SQUEEZE's joint resonance/envelope
 * mapping), not by ear, and checked by [VelvetPresetsTest]'s
 * sanity/round-trip/spread suite. SQUELCH's own resonant-acid character is
 * named for its sound (`WET SQUELCH`, `SCREAMER`, ...), never for the
 * genre or the trademarked family of machines that popularized it — the
 * word "acid" itself is on the naming rule's own blocklist.
 */
object VelvetPresets {

    private fun p(voice: VelvetVoice, name: String, vararg macros: Pair<String, Float>) =
        VelvetPatch(name, voice, macros.toMap())

    fun forVoice(voice: VelvetVoice): List<VelvetPatch> = when (voice) {
        VelvetVoice.BASS -> bassPresets
        VelvetVoice.BRASS -> brassPresets
        VelvetVoice.SQUELCH -> squelchPresets
        VelvetVoice.CHIP -> chipPresets
    }

    fun all(): List<VelvetPatch> = VelvetVoice.entries.flatMap { forVoice(it) }

    private val bassPresets = listOf(
        p(VelvetVoice.BASS, "SUB DEEP", "TUNE" to 0.1f, "SHAPE" to 0.1f, "FAT" to 0.2f, "CUTOFF" to 0.2f, "SQUEEZE" to 0.3f, "DECAY" to 0.6f),
        p(VelvetVoice.BASS, "WARM SUB", "TUNE" to 0.15f, "SHAPE" to 0.15f, "FAT" to 0.4f, "CUTOFF" to 0.3f, "SQUEEZE" to 0.35f, "DECAY" to 0.5f),
        p(VelvetVoice.BASS, "FAT BASS", "TUNE" to 0.2f, "SHAPE" to 0.25f, "FAT" to 0.7f, "CUTOFF" to 0.35f, "SQUEEZE" to 0.4f, "DECAY" to 0.45f),
        p(VelvetVoice.BASS, "TIGHT BASS", "TUNE" to 0.25f, "SHAPE" to 0.3f, "FAT" to 0.3f, "CUTOFF" to 0.45f, "SQUEEZE" to 0.5f, "DECAY" to 0.3f),
        p(VelvetVoice.BASS, "GROWL BASS", "TUNE" to 0.3f, "SHAPE" to 0.5f, "FAT" to 0.5f, "CUTOFF" to 0.5f, "SQUEEZE" to 0.6f, "DECAY" to 0.4f),
        p(VelvetVoice.BASS, "SQUIRMY BASS", "TUNE" to 0.35f, "SHAPE" to 0.4f, "FAT" to 0.3f, "CUTOFF" to 0.3f, "SQUEEZE" to 0.8f, "DECAY" to 0.35f),
        p(VelvetVoice.BASS, "ROUND BASS", "TUNE" to 0.4f, "SHAPE" to 0.05f, "FAT" to 0.35f, "CUTOFF" to 0.25f, "SQUEEZE" to 0.2f, "DECAY" to 0.55f),
        p(VelvetVoice.BASS, "PLUCKY BASS", "TUNE" to 0.45f, "SHAPE" to 0.35f, "FAT" to 0.25f, "CUTOFF" to 0.55f, "SQUEEZE" to 0.45f, "DECAY" to 0.25f),
        p(VelvetVoice.BASS, "RUBBER BASS", "TUNE" to 0.5f, "SHAPE" to 0.6f, "FAT" to 0.15f, "CUTOFF" to 0.4f, "SQUEEZE" to 0.55f, "DECAY" to 0.3f),
        p(VelvetVoice.BASS, "DARK BASS", "TUNE" to 0.05f, "SHAPE" to 0.1f, "FAT" to 0.1f, "CUTOFF" to 0.15f, "SQUEEZE" to 0.25f, "DECAY" to 0.7f),
        p(VelvetVoice.BASS, "BRIGHT BASS", "TUNE" to 0.55f, "SHAPE" to 0.3f, "FAT" to 0.45f, "CUTOFF" to 0.7f, "SQUEEZE" to 0.35f, "DECAY" to 0.2f),
        p(VelvetVoice.BASS, "FUZZY BASS", "TUNE" to 0.3f, "SHAPE" to 0.7f, "FAT" to 0.6f, "CUTOFF" to 0.45f, "SQUEEZE" to 0.65f, "DECAY" to 0.5f),
    )

    private val brassPresets = listOf(
        p(VelvetVoice.BRASS, "FANFARE", "TUNE" to 0.5f, "SHAPE" to 0.1f, "FAT" to 0.6f, "CUTOFF" to 0.55f, "SQUEEZE" to 0.3f, "DECAY" to 0.5f),
        p(VelvetVoice.BRASS, "BRASS STAB", "TUNE" to 0.45f, "SHAPE" to 0.15f, "FAT" to 0.7f, "CUTOFF" to 0.6f, "SQUEEZE" to 0.35f, "DECAY" to 0.35f),
        p(VelvetVoice.BRASS, "HORN SECTION", "TUNE" to 0.4f, "SHAPE" to 0.05f, "FAT" to 0.5f, "CUTOFF" to 0.5f, "SQUEEZE" to 0.25f, "DECAY" to 0.55f),
        p(VelvetVoice.BRASS, "SYNTH BRASS", "TUNE" to 0.55f, "SHAPE" to 0.2f, "FAT" to 0.65f, "CUTOFF" to 0.65f, "SQUEEZE" to 0.4f, "DECAY" to 0.4f),
        p(VelvetVoice.BRASS, "THIN BRASS", "TUNE" to 0.6f, "SHAPE" to 0.1f, "FAT" to 0.3f, "CUTOFF" to 0.45f, "SQUEEZE" to 0.2f, "DECAY" to 0.3f),
        p(VelvetVoice.BRASS, "WARM BRASS", "TUNE" to 0.2f, "SHAPE" to 0f, "FAT" to 0.8f, "CUTOFF" to 0.25f, "SQUEEZE" to 0.15f, "DECAY" to 0.8f),
        p(VelvetVoice.BRASS, "BUZZY BRASS", "TUNE" to 0.5f, "SHAPE" to 0.4f, "FAT" to 0.4f, "CUTOFF" to 0.7f, "SQUEEZE" to 0.5f, "DECAY" to 0.25f),
        p(VelvetVoice.BRASS, "STAB HIT", "TUNE" to 0.65f, "SHAPE" to 0.3f, "FAT" to 0.75f, "CUTOFF" to 0.75f, "SQUEEZE" to 0.45f, "DECAY" to 0.2f),
        p(VelvetVoice.BRASS, "SOFT BRASS", "TUNE" to 0.3f, "SHAPE" to 0.05f, "FAT" to 0.35f, "CUTOFF" to 0.3f, "SQUEEZE" to 0.15f, "DECAY" to 0.65f),
        p(VelvetVoice.BRASS, "SHARP BRASS", "TUNE" to 0.7f, "SHAPE" to 0.25f, "FAT" to 0.6f, "CUTOFF" to 0.8f, "SQUEEZE" to 0.55f, "DECAY" to 0.3f),
        p(VelvetVoice.BRASS, "DETUNED HORN", "TUNE" to 0.45f, "SHAPE" to 0.1f, "FAT" to 0.9f, "CUTOFF" to 0.5f, "SQUEEZE" to 0.3f, "DECAY" to 0.45f),
        p(VelvetVoice.BRASS, "MELLOW BRASS", "TUNE" to 0.25f, "SHAPE" to 0.05f, "FAT" to 0.45f, "CUTOFF" to 0.35f, "SQUEEZE" to 0.2f, "DECAY" to 0.7f),
    )

    private val squelchPresets = listOf(
        p(VelvetVoice.SQUELCH, "WET SQUELCH", "TUNE" to 0.4f, "SHAPE" to 0.3f, "FAT" to 0.15f, "CUTOFF" to 0.25f, "SQUEEZE" to 0.9f, "DECAY" to 0.4f),
        p(VelvetVoice.SQUELCH, "SQUELCHY LOW", "TUNE" to 0.2f, "SHAPE" to 0.35f, "FAT" to 0.1f, "CUTOFF" to 0.2f, "SQUEEZE" to 0.85f, "DECAY" to 0.5f),
        p(VelvetVoice.SQUELCH, "SCREAMER", "TUNE" to 0.5f, "SHAPE" to 0.4f, "FAT" to 0.2f, "CUTOFF" to 0.35f, "SQUEEZE" to 0.95f, "DECAY" to 0.3f),
        p(VelvetVoice.SQUELCH, "RESONANT LOW", "TUNE" to 0.3f, "SHAPE" to 0.25f, "FAT" to 0.25f, "CUTOFF" to 0.3f, "SQUEEZE" to 0.8f, "DECAY" to 0.45f),
        p(VelvetVoice.SQUELCH, "TIGHT SQUELCH", "TUNE" to 0.45f, "SHAPE" to 0.2f, "FAT" to 0.1f, "CUTOFF" to 0.4f, "SQUEEZE" to 0.7f, "DECAY" to 0.25f),
        p(VelvetVoice.SQUELCH, "SQUELCH LEAD", "TUNE" to 0.6f, "SHAPE" to 0.45f, "FAT" to 0.3f, "CUTOFF" to 0.55f, "SQUEEZE" to 0.75f, "DECAY" to 0.35f),
        p(VelvetVoice.SQUELCH, "RUBBERY", "TUNE" to 0.35f, "SHAPE" to 0.5f, "FAT" to 0.05f, "CUTOFF" to 0.25f, "SQUEEZE" to 0.6f, "DECAY" to 0.55f),
        p(VelvetVoice.SQUELCH, "BITEY SQUELCH", "TUNE" to 0.55f, "SHAPE" to 0.55f, "FAT" to 0.15f, "CUTOFF" to 0.5f, "SQUEEZE" to 0.9f, "DECAY" to 0.2f),
        p(VelvetVoice.SQUELCH, "LOW WHINE", "TUNE" to 0.1f, "SHAPE" to 0.3f, "FAT" to 0.2f, "CUTOFF" to 0.15f, "SQUEEZE" to 0.5f, "DECAY" to 0.65f),
        p(VelvetVoice.SQUELCH, "NASAL SQUELCH", "TUNE" to 0.5f, "SHAPE" to 0.65f, "FAT" to 0.1f, "CUTOFF" to 0.45f, "SQUEEZE" to 0.65f, "DECAY" to 0.3f),
        p(VelvetVoice.SQUELCH, "DEEP SQUELCH", "TUNE" to 0.15f, "SHAPE" to 0.2f, "FAT" to 0.05f, "CUTOFF" to 0.2f, "SQUEEZE" to 0.4f, "DECAY" to 0.6f),
        p(VelvetVoice.SQUELCH, "HOWL", "TUNE" to 0.65f, "SHAPE" to 0.35f, "FAT" to 0.35f, "CUTOFF" to 0.6f, "SQUEEZE" to 0.85f, "DECAY" to 0.35f),
    )

    private val chipPresets = listOf(
        p(VelvetVoice.CHIP, "EIGHT BIT", "TUNE" to 0.5f, "SHAPE" to 0.9f, "FAT" to 0.05f, "CUTOFF" to 0.9f, "SQUEEZE" to 0.1f, "DECAY" to 0.3f),
        p(VelvetVoice.CHIP, "ARCADE", "TUNE" to 0.6f, "SHAPE" to 0.95f, "FAT" to 0.1f, "CUTOFF" to 0.85f, "SQUEEZE" to 0.15f, "DECAY" to 0.25f),
        p(VelvetVoice.CHIP, "PIXEL LEAD", "TUNE" to 0.8f, "SHAPE" to 0.5f, "FAT" to 0.3f, "CUTOFF" to 0.6f, "SQUEEZE" to 0.35f, "DECAY" to 0.15f),
        p(VelvetVoice.CHIP, "HANDHELD", "TUNE" to 0.65f, "SHAPE" to 0.85f, "FAT" to 0.15f, "CUTOFF" to 0.8f, "SQUEEZE" to 0.2f, "DECAY" to 0.2f),
        p(VelvetVoice.CHIP, "SQUARE LEAD", "TUNE" to 0.45f, "SHAPE" to 0.6f, "FAT" to 0.1f, "CUTOFF" to 0.7f, "SQUEEZE" to 0.1f, "DECAY" to 0.4f),
        p(VelvetVoice.CHIP, "PWM CHIP", "TUNE" to 0.5f, "SHAPE" to 0.95f, "FAT" to 0.2f, "CUTOFF" to 0.9f, "SQUEEZE" to 0.05f, "DECAY" to 0.3f),
        p(VelvetVoice.CHIP, "LOFI CHIP", "TUNE" to 0.4f, "SHAPE" to 0.7f, "FAT" to 0.3f, "CUTOFF" to 0.6f, "SQUEEZE" to 0.25f, "DECAY" to 0.45f),
        p(VelvetVoice.CHIP, "BLEEP", "TUNE" to 0.7f, "SHAPE" to 0.9f, "FAT" to 0f, "CUTOFF" to 0.95f, "SQUEEZE" to 0.1f, "DECAY" to 0.15f),
        p(VelvetVoice.CHIP, "BLOOP", "TUNE" to 0.3f, "SHAPE" to 0.8f, "FAT" to 0.05f, "CUTOFF" to 0.75f, "SQUEEZE" to 0.15f, "DECAY" to 0.5f),
        p(VelvetVoice.CHIP, "CHIPTUNE LEAD", "TUNE" to 0.6f, "SHAPE" to 0.55f, "FAT" to 0.25f, "CUTOFF" to 0.65f, "SQUEEZE" to 0.3f, "DECAY" to 0.3f),
        p(VelvetVoice.CHIP, "DIRTY CHIP", "TUNE" to 0.5f, "SHAPE" to 0.4f, "FAT" to 0.4f, "CUTOFF" to 0.5f, "SQUEEZE" to 0.4f, "DECAY" to 0.35f),
        p(VelvetVoice.CHIP, "SOFT SQUARE", "TUNE" to 0.35f, "SHAPE" to 0.5f, "FAT" to 0.1f, "CUTOFF" to 0.55f, "SQUEEZE" to 0.1f, "DECAY" to 0.55f),
    )
}
