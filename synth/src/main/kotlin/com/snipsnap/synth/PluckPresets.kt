package com.snipsnap.synth

/**
 * PLUCK's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (four voices, forty-eight total), spread across
 * TUNE/DAMP/PICK/STRIKE/BODY/DOUBLE — the same six macros every voice shares, since
 * PLUCK's character lives in each voice's fixed body constants
 * (`loopHz`/`pickLo`/`pickHi`/`ring` in `Pluck.kt`), not in a different
 * macro shape per voice. Authored from that DSP, not by ear (no audio
 * playback in this session), and checked by [PluckPresetsTest]'s
 * sanity/round-trip/spread suite.
 */
object PluckPresets {

    private fun p(voice: PluckVoice, name: String, vararg macros: Pair<String, Float>) =
        PluckPatch(name, voice, macros.toMap())

    fun forVoice(voice: PluckVoice): List<PluckPatch> = when (voice) {
        PluckVoice.NYLON -> nylonPresets
        PluckVoice.HARP -> harpPresets
        PluckVoice.KOTO -> kotoPresets
        PluckVoice.BANJO -> banjoPresets
    }

    fun all(): List<PluckPatch> = PluckVoice.entries.flatMap { forVoice(it) }

    private val nylonPresets = listOf(
        p(PluckVoice.NYLON, "CLASSICAL", "TUNE" to 0.3f, "DAMP" to 0.3f, "PICK" to 0.3f, "DOUBLE" to 0.1f),
        p(PluckVoice.NYLON, "SPANISH", "TUNE" to 0.35f, "DAMP" to 0.2f, "PICK" to 0.5f, "DOUBLE" to 0.2f),
        p(PluckVoice.NYLON, "SOFT NYLON", "TUNE" to 0.4f, "DAMP" to 0.5f, "PICK" to 0.25f, "DOUBLE" to 0.05f),
        p(PluckVoice.NYLON, "FLAMENCO", "TUNE" to 0.5f, "DAMP" to 0.15f, "PICK" to 0.7f, "DOUBLE" to 0.3f),
        p(PluckVoice.NYLON, "MELLOW GUT", "TUNE" to 0.25f, "DAMP" to 0.6f, "PICK" to 0.2f, "DOUBLE" to 0f),
        p(PluckVoice.NYLON, "BRIGHT NYLON", "TUNE" to 0.6f, "DAMP" to 0.1f, "PICK" to 0.8f, "DOUBLE" to 0.15f),
        p(PluckVoice.NYLON, "FINGERSTYLE", "TUNE" to 0.45f, "DAMP" to 0.35f, "PICK" to 0.4f, "DOUBLE" to 0.1f),
        p(PluckVoice.NYLON, "TWELVE STRING", "TUNE" to 0.55f, "DAMP" to 0.3f, "PICK" to 0.45f, "DOUBLE" to 0.7f),
        p(PluckVoice.NYLON, "MUTED NYLON", "TUNE" to 0.3f, "DAMP" to 0.8f, "PICK" to 0.15f, "DOUBLE" to 0f),
        p(PluckVoice.NYLON, "THIN STRING", "TUNE" to 0.7f, "DAMP" to 0.25f, "PICK" to 0.6f, "DOUBLE" to 0.2f),
        p(PluckVoice.NYLON, "WARM STRUM", "TUNE" to 0.35f, "DAMP" to 0.45f, "PICK" to 0.3f, "DOUBLE" to 0.35f),
        p(PluckVoice.NYLON, "HARSH PLUCK", "TUNE" to 0.65f, "DAMP" to 0.05f, "PICK" to 0.95f, "DOUBLE" to 0.1f),
    )

    private val harpPresets = listOf(
        p(PluckVoice.HARP, "ANGELIC", "TUNE" to 0.5f, "DAMP" to 0.1f, "PICK" to 0.5f, "DOUBLE" to 0.2f),
        p(PluckVoice.HARP, "CASCADE", "TUNE" to 0.4f, "DAMP" to 0.15f, "PICK" to 0.6f, "DOUBLE" to 0.3f),
        p(PluckVoice.HARP, "GLISTEN", "TUNE" to 0.6f, "DAMP" to 0.05f, "PICK" to 0.7f, "DOUBLE" to 0.15f),
        p(PluckVoice.HARP, "SOFT HARP", "TUNE" to 0.45f, "DAMP" to 0.3f, "PICK" to 0.35f, "DOUBLE" to 0.1f),
        p(PluckVoice.HARP, "CELESTIAL", "TUNE" to 0.7f, "DAMP" to 0.1f, "PICK" to 0.8f, "DOUBLE" to 0.4f),
        p(PluckVoice.HARP, "LOW HARP", "TUNE" to 0.2f, "DAMP" to 0.25f, "PICK" to 0.4f, "DOUBLE" to 0.05f),
        p(PluckVoice.HARP, "GLASS STRING", "TUNE" to 0.75f, "DAMP" to 0.2f, "PICK" to 0.9f, "DOUBLE" to 0.25f),
        p(PluckVoice.HARP, "RIPPLE", "TUNE" to 0.5f, "DAMP" to 0.4f, "PICK" to 0.45f, "DOUBLE" to 0.5f),
        p(PluckVoice.HARP, "DEEP HARP", "TUNE" to 0.15f, "DAMP" to 0.35f, "PICK" to 0.3f, "DOUBLE" to 0f),
        p(PluckVoice.HARP, "SHIMMERING", "TUNE" to 0.65f, "DAMP" to 0.15f, "PICK" to 0.65f, "DOUBLE" to 0.6f),
        p(PluckVoice.HARP, "MUTED HARP", "TUNE" to 0.3f, "DAMP" to 0.7f, "PICK" to 0.2f, "DOUBLE" to 0.1f),
        p(PluckVoice.HARP, "HARP DOUBLE", "TUNE" to 0.55f, "DAMP" to 0.2f, "PICK" to 0.55f, "DOUBLE" to 0.85f),
    )

    private val kotoPresets = listOf(
        p(PluckVoice.KOTO, "KOTO STRIKE", "TUNE" to 0.5f, "DAMP" to 0.3f, "PICK" to 0.7f, "DOUBLE" to 0.4f),
        p(PluckVoice.KOTO, "ZEN PLUCK", "TUNE" to 0.4f, "DAMP" to 0.4f, "PICK" to 0.5f, "DOUBLE" to 0.2f),
        p(PluckVoice.KOTO, "TEMPLE STRING", "TUNE" to 0.3f, "DAMP" to 0.5f, "PICK" to 0.4f, "DOUBLE" to 0.1f),
        p(PluckVoice.KOTO, "BRIGHT KOTO", "TUNE" to 0.6f, "DAMP" to 0.15f, "PICK" to 0.85f, "DOUBLE" to 0.3f),
        p(PluckVoice.KOTO, "DEEP KOTO", "TUNE" to 0.2f, "DAMP" to 0.35f, "PICK" to 0.3f, "DOUBLE" to 0.05f),
        p(PluckVoice.KOTO, "SILK STRING", "TUNE" to 0.55f, "DAMP" to 0.25f, "PICK" to 0.6f, "DOUBLE" to 0.5f),
        p(PluckVoice.KOTO, "SHARP PLUCK", "TUNE" to 0.3f, "DAMP" to 0.05f, "PICK" to 0.95f, "DOUBLE" to 0.8f),
        p(PluckVoice.KOTO, "MUTED KOTO", "TUNE" to 0.35f, "DAMP" to 0.75f, "PICK" to 0.25f, "DOUBLE" to 0.1f),
        p(PluckVoice.KOTO, "TWANGY KOTO", "TUNE" to 0.7f, "DAMP" to 0.2f, "PICK" to 0.75f, "DOUBLE" to 0.6f),
        p(PluckVoice.KOTO, "RESONANT", "TUNE" to 0.45f, "DAMP" to 0.05f, "PICK" to 0.55f, "DOUBLE" to 0.55f),
        p(PluckVoice.KOTO, "DRY PLUCK", "TUNE" to 0.5f, "DAMP" to 0.6f, "PICK" to 0.35f, "DOUBLE" to 0f),
        p(PluckVoice.KOTO, "SPARKLE KOTO", "TUNE" to 0.75f, "DAMP" to 0.1f, "PICK" to 0.95f, "DOUBLE" to 0.7f),
    )

    private val banjoPresets = listOf(
        p(PluckVoice.BANJO, "OPEN G", "TUNE" to 0.5f, "DAMP" to 0.5f, "PICK" to 0.7f, "STRIKE" to 0.4f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "CLAWHAMMER", "TUNE" to 0.4f, "DAMP" to 0.6f, "PICK" to 0.55f, "STRIKE" to 0.55f, "DOUBLE" to 0.05f),
        p(PluckVoice.BANJO, "BRIGHT ROLL", "TUNE" to 0.6f, "DAMP" to 0.45f, "PICK" to 0.9f, "STRIKE" to 0.3f, "DOUBLE" to 0.15f),
        p(PluckVoice.BANJO, "PLUNK", "TUNE" to 0.3f, "DAMP" to 0.75f, "PICK" to 0.5f, "STRIKE" to 0.5f, "DOUBLE" to 0f),
        p(PluckVoice.BANJO, "TENOR", "TUNE" to 0.45f, "DAMP" to 0.4f, "PICK" to 0.65f, "STRIKE" to 0.45f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "MUTED HEAD", "TUNE" to 0.35f, "DAMP" to 0.85f, "PICK" to 0.4f, "STRIKE" to 0.6f, "DOUBLE" to 0f),
        p(PluckVoice.BANJO, "HIGH FIFTH", "TUNE" to 0.8f, "DAMP" to 0.5f, "PICK" to 0.8f, "STRIKE" to 0.35f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "TWIN STRING", "TUNE" to 0.5f, "DAMP" to 0.45f, "PICK" to 0.7f, "STRIKE" to 0.4f, "DOUBLE" to 0.6f),
        p(PluckVoice.BANJO, "RINGING", "TUNE" to 0.55f, "DAMP" to 0.2f, "PICK" to 0.75f, "STRIKE" to 0.4f, "DOUBLE" to 0.2f),
        p(PluckVoice.BANJO, "THUMB", "TUNE" to 0.25f, "DAMP" to 0.55f, "PICK" to 0.45f, "STRIKE" to 0.7f, "DOUBLE" to 0.05f),
        p(PluckVoice.BANJO, "TINNY", "TUNE" to 0.7f, "DAMP" to 0.6f, "PICK" to 0.95f, "STRIKE" to 0.15f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "SOFT PICK", "TUNE" to 0.4f, "DAMP" to 0.5f, "PICK" to 0.3f, "STRIKE" to 0.5f, "DOUBLE" to 0.1f),
    )
}
