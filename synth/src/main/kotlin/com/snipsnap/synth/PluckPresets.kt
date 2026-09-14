package com.snipsnap.synth

/**
 * PLUCK's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (four voices, forty-eight total), spread across
 * TUNE/DAMP/PICK/DOUBLE — the same four macros every voice shares, since
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
        PluckVoice.KALIMBA -> kalimbaPresets
        PluckVoice.NYLON -> nylonPresets
        PluckVoice.HARP -> harpPresets
        PluckVoice.KOTO -> kotoPresets
    }

    fun all(): List<PluckPatch> = PluckVoice.entries.flatMap { forVoice(it) }

    private val kalimbaPresets = listOf(
        p(PluckVoice.KALIMBA, "THUMBPIANO", "TUNE" to 0.3f, "DAMP" to 0.3f, "PICK" to 0.4f, "DOUBLE" to 0.05f),
        p(PluckVoice.KALIMBA, "RUSTY TINE", "TUNE" to 0.2f, "DAMP" to 0.6f, "PICK" to 0.3f, "DOUBLE" to 0.1f),
        p(PluckVoice.KALIMBA, "BRASS TINE", "TUNE" to 0.4f, "DAMP" to 0.2f, "PICK" to 0.6f, "DOUBLE" to 0.15f),
        p(PluckVoice.KALIMBA, "WOOD BODY", "TUNE" to 0.35f, "DAMP" to 0.5f, "PICK" to 0.35f, "DOUBLE" to 0f),
        p(PluckVoice.KALIMBA, "GLASSY MBIRA", "TUNE" to 0.5f, "DAMP" to 0.15f, "PICK" to 0.7f, "DOUBLE" to 0.2f),
        p(PluckVoice.KALIMBA, "SOFT PLUCK", "TUNE" to 0.45f, "DAMP" to 0.7f, "PICK" to 0.25f, "DOUBLE" to 0.05f),
        p(PluckVoice.KALIMBA, "TWANG", "TUNE" to 0.6f, "DAMP" to 0.25f, "PICK" to 0.55f, "DOUBLE" to 0.3f),
        p(PluckVoice.KALIMBA, "DUSTY KEYS", "TUNE" to 0.55f, "DAMP" to 0.45f, "PICK" to 0.4f, "DOUBLE" to 0.1f),
        p(PluckVoice.KALIMBA, "BRIGHT TINE", "TUNE" to 0.7f, "DAMP" to 0.1f, "PICK" to 0.8f, "DOUBLE" to 0.25f),
        p(PluckVoice.KALIMBA, "MUTED THUMB", "TUNE" to 0.25f, "DAMP" to 0.85f, "PICK" to 0.2f, "DOUBLE" to 0f),
        p(PluckVoice.KALIMBA, "DOUBLE ROW", "TUNE" to 0.65f, "DAMP" to 0.35f, "PICK" to 0.5f, "DOUBLE" to 0.6f),
        p(PluckVoice.KALIMBA, "HIGH CHIME", "TUNE" to 0.8f, "DAMP" to 0.05f, "PICK" to 0.9f, "DOUBLE" to 0.15f),
    )

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
}
