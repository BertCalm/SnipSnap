package com.snipsnap.synth

/**
 * VOX's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (three voices, thirty-six total), spread across
 * TUNE/VOWEL/BREATH/DECAY, and since round 1 SIZE (LOW and HIGH throats)
 * and GLIDE (SPEAK BOX's "wah", HIGH SIGH and LOW MOAN sighing toward
 * "ooh"). VOWEL walks the A→E→I→O→U morph `Vox.kt` defines; BREATH
 * crossfades the throat toward filtered noise.
 * Authored from that DSP, not by ear, and checked by [VoxPresetsTest]'s
 * sanity/round-trip/spread suite.
 */
object VoxPresets {

    private fun p(voice: VoxVoice, name: String, vararg macros: Pair<String, Float>) =
        VoxPatch(name, voice, macros.toMap())

    fun forVoice(voice: VoxVoice): List<VoxPatch> = when (voice) {
        VoxVoice.CHOIR -> choirPresets
        VoxVoice.ROBOT -> robotPresets
        VoxVoice.GHOST -> ghostPresets
    }

    fun all(): List<VoxPatch> = VoxVoice.entries.flatMap { forVoice(it) }

    private val choirPresets = listOf(
        p(VoxVoice.CHOIR, "AAH CHOIR", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.15f, "DECAY" to 0.6f),
        p(VoxVoice.CHOIR, "OOH CHOIR", "TUNE" to 0.45f, "VOWEL" to 0.95f, "BREATH" to 0.2f, "DECAY" to 0.65f),
        p(VoxVoice.CHOIR, "EH CHOIR", "TUNE" to 0.5f, "VOWEL" to 0.3f, "BREATH" to 0.1f, "DECAY" to 0.55f, "GLIDE" to 0.35f),
        p(VoxVoice.CHOIR, "BREATHY CHOIR", "TUNE" to 0.4f, "VOWEL" to 0.2f, "BREATH" to 0.6f, "DECAY" to 0.7f),
        p(VoxVoice.CHOIR, "TIGHT CHOIR", "TUNE" to 0.55f, "VOWEL" to 0.1f, "BREATH" to 0.05f, "DECAY" to 0.35f),
        p(VoxVoice.CHOIR, "HIGH CHOIR", "TUNE" to 0.7f, "VOWEL" to 0.15f, "BREATH" to 0.2f, "DECAY" to 0.5f, "SIZE" to 0.35f),
        p(VoxVoice.CHOIR, "LOW CHOIR", "TUNE" to 0.25f, "VOWEL" to 0.1f, "BREATH" to 0.1f, "DECAY" to 0.75f, "SIZE" to 0.7f),
        p(VoxVoice.CHOIR, "ANGEL PAD", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.3f, "DECAY" to 0.9f),
        p(VoxVoice.CHOIR, "WIND CHOIR", "TUNE" to 0.45f, "VOWEL" to 0.5f, "BREATH" to 0.7f, "DECAY" to 0.6f),
        p(VoxVoice.CHOIR, "SHORT AAH", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.1f, "DECAY" to 0.25f),
        p(VoxVoice.CHOIR, "DEEP OOH", "TUNE" to 0.2f, "VOWEL" to 0.9f, "BREATH" to 0.15f, "DECAY" to 0.8f, "SIZE" to 0.75f),
        p(VoxVoice.CHOIR, "UNISON PAD", "TUNE" to 0.3f, "VOWEL" to 0.75f, "BREATH" to 0.35f, "DECAY" to 0.85f),
    )

    private val robotPresets = listOf(
        p(VoxVoice.ROBOT, "VOCODER", "TUNE" to 0.5f, "VOWEL" to 0.6f, "BREATH" to 0.05f, "DECAY" to 0.4f),
        p(VoxVoice.ROBOT, "SPEAK BOX", "TUNE" to 0.5f, "VOWEL" to 1f, "BREATH" to 0.1f, "DECAY" to 0.35f, "GLIDE" to 0f),
        p(VoxVoice.ROBOT, "ROBOT AAH", "TUNE" to 0.45f, "VOWEL" to 0.05f, "BREATH" to 0.05f, "DECAY" to 0.3f),
        p(VoxVoice.ROBOT, "ROBOT OOH", "TUNE" to 0.5f, "VOWEL" to 0.95f, "BREATH" to 0.05f, "DECAY" to 0.35f),
        p(VoxVoice.ROBOT, "METALLIC EE", "TUNE" to 0.55f, "VOWEL" to 0.35f, "BREATH" to 0.1f, "DECAY" to 0.25f),
        p(VoxVoice.ROBOT, "DROID VOICE", "TUNE" to 0.4f, "VOWEL" to 0.55f, "BREATH" to 0.15f, "DECAY" to 0.3f, "GLIDE" to 0.75f),
        p(VoxVoice.ROBOT, "TIGHT ROBOT", "TUNE" to 0.6f, "VOWEL" to 0.65f, "BREATH" to 0.02f, "DECAY" to 0.2f),
        p(VoxVoice.ROBOT, "LOW ROBOT", "TUNE" to 0.2f, "VOWEL" to 0.5f, "BREATH" to 0.1f, "DECAY" to 0.45f, "SIZE" to 0.85f),
        p(VoxVoice.ROBOT, "HIGH ROBOT", "TUNE" to 0.7f, "VOWEL" to 0.6f, "BREATH" to 0.05f, "DECAY" to 0.2f, "SIZE" to 0.15f),
        p(VoxVoice.ROBOT, "FLAT VOWEL", "TUNE" to 0.75f, "VOWEL" to 0f, "BREATH" to 0.15f, "DECAY" to 0.15f),
        p(VoxVoice.ROBOT, "BUZZY BOT", "TUNE" to 0.45f, "VOWEL" to 0.7f, "BREATH" to 0.2f, "DECAY" to 0.25f),
        p(VoxVoice.ROBOT, "SHORT BLEEP", "TUNE" to 0.55f, "VOWEL" to 0.8f, "BREATH" to 0.05f, "DECAY" to 0.1f),
    )

    private val ghostPresets = listOf(
        p(VoxVoice.GHOST, "WHISPER", "TUNE" to 0.45f, "VOWEL" to 0.85f, "BREATH" to 0.6f, "DECAY" to 0.7f),
        p(VoxVoice.GHOST, "HAUNTED OOH", "TUNE" to 0.4f, "VOWEL" to 0.6f, "BREATH" to 0.7f, "DECAY" to 0.9f, "GLIDE" to 0.7f),
        p(VoxVoice.GHOST, "AIRY AAH", "TUNE" to 0.5f, "VOWEL" to 0.1f, "BREATH" to 0.5f, "DECAY" to 0.6f),
        p(VoxVoice.GHOST, "BREATHY EE", "TUNE" to 0.55f, "VOWEL" to 0.35f, "BREATH" to 0.75f, "DECAY" to 0.55f),
        p(VoxVoice.GHOST, "FAINT VOICE", "TUNE" to 0.35f, "VOWEL" to 0.5f, "BREATH" to 0.85f, "DECAY" to 0.8f),
        p(VoxVoice.GHOST, "SPECTRAL", "TUNE" to 0.3f, "VOWEL" to 0.7f, "BREATH" to 0.65f, "DECAY" to 0.95f),
        p(VoxVoice.GHOST, "NOISY GHOST", "TUNE" to 0.5f, "VOWEL" to 0.6f, "BREATH" to 0.95f, "DECAY" to 0.5f),
        p(VoxVoice.GHOST, "LOW MOAN", "TUNE" to 0.2f, "VOWEL" to 0.8f, "BREATH" to 0.55f, "DECAY" to 0.85f, "SIZE" to 0.75f, "GLIDE" to 0.65f),
        p(VoxVoice.GHOST, "HIGH SIGH", "TUNE" to 0.65f, "VOWEL" to 0.4f, "BREATH" to 0.6f, "DECAY" to 0.4f, "GLIDE" to 0.8f),
        p(VoxVoice.GHOST, "DRY GHOST", "TUNE" to 0.45f, "VOWEL" to 0.55f, "BREATH" to 0.2f, "DECAY" to 0.5f),
        p(VoxVoice.GHOST, "SHORT GASP", "TUNE" to 0.5f, "VOWEL" to 0.3f, "BREATH" to 0.8f, "DECAY" to 0.2f),
        p(VoxVoice.GHOST, "DISTANT AAH", "TUNE" to 0.4f, "VOWEL" to 0.1f, "BREATH" to 0.5f, "DECAY" to 1f),
    )
}
