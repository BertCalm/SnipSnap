package com.snipsnap.synth

/**
 * PLUCK's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (five voices, sixty total), spread across
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
        PluckVoice.SITAR -> sitarPresets
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

    // Authored for the audition, not by ear (the parent spec's by-ear pass
    // re-authors them). DOUBLE is the sympathetic strings on this voice.
    private val sitarPresets = listOf(
        p(PluckVoice.SITAR, "ALAAP", "TUNE" to 0.5f, "DAMP" to 0.3f, "PICK" to 0.6f, "STRIKE" to 0.3f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "JHALA", "TUNE" to 0.6f, "DAMP" to 0.5f, "PICK" to 0.85f, "STRIKE" to 0.2f, "DOUBLE" to 0.3f),
        p(PluckVoice.SITAR, "GAT", "TUNE" to 0.45f, "DAMP" to 0.4f, "PICK" to 0.7f, "STRIKE" to 0.3f, "DOUBLE" to 0.45f),
        p(PluckVoice.SITAR, "DRONE", "TUNE" to 0.5f, "DAMP" to 0.15f, "PICK" to 0.5f, "STRIKE" to 0.35f, "DOUBLE" to 1.0f),
        p(PluckVoice.SITAR, "DRY STRING", "TUNE" to 0.5f, "DAMP" to 0.45f, "PICK" to 0.65f, "STRIKE" to 0.3f, "DOUBLE" to 0.0f),
        p(PluckVoice.SITAR, "MUTED", "TUNE" to 0.4f, "DAMP" to 0.85f, "PICK" to 0.4f, "STRIKE" to 0.4f, "DOUBLE" to 0.2f),
        p(PluckVoice.SITAR, "HIGH STRING", "TUNE" to 0.85f, "DAMP" to 0.35f, "PICK" to 0.75f, "STRIKE" to 0.25f, "DOUBLE" to 0.35f),
        p(PluckVoice.SITAR, "BRIDGE PICK", "TUNE" to 0.5f, "DAMP" to 0.35f, "PICK" to 0.7f, "STRIKE" to 0.0f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "CENTRE PICK", "TUNE" to 0.5f, "DAMP" to 0.35f, "PICK" to 0.5f, "STRIKE" to 1.0f, "DOUBLE" to 0.4f),
        // Two of SITAR's twelve presets need their own BODY override once
        // the voice-wide default actually reached 1 - stacked with a fixed
        // body mode, the crest factor falls low enough that Dsp.levelTo's
        // loudness match squashes the render's peak under
        // PluckPresetsTest's 0.5 floor. Originally scoped to RINGING alone
        // on the theory that its DAMP 0 (the most sustained corner any
        // SITAR preset uses) was the whole story; measuring all twelve
        // against the real 1.0 default (not the interim .35 an earlier
        // round shipped while this was still under investigation) found a
        // second driver - proximity to bodyFor(SITAR)'s 110 Hz mode, not
        // just DAMP - and a second preset it alone accounts for. Neither
        // amount below is a near-zero compromise; each is the last value
        // that keeps its own preset's peak at the untouched-string ceiling
        // (0.99), measured across the whole BODY range rather than guessed.
        // Every other preset clears 0.5 on the voice default with real
        // margin - DRONE (0.594) and CENTRE PICK (0.624) are the closest of
        // the rest, neither close enough to need touching.
        //
        // RINGING: DAMP 0. Measured peak 0.4754537 at BODY 1; flat at 0.99
        // through 0.35, falling smoothly above it (0.4 -> 0.921, ...,
        // 1.0 -> 0.475) - 0.35 is the last full-headroom amount, and it is
        // the same value `p3a_body_35_root` already chipped as carrying
        // real character in its own right.
        p(PluckVoice.SITAR, "RINGING", "TUNE" to 0.55f, "DAMP" to 0.0f, "PICK" to 0.6f, "STRIKE" to 0.3f, "DOUBLE" to 0.5f, "BODY" to 0.35f),
        // LOW TONIC: TUNE 0, the root note (139 Hz) - close enough to the
        // 110 Hz gourd mode that this preset is more exposed to BODY than
        // its DAMP 0.3 alone would suggest. Measured peak 0.44782394 at
        // BODY 1; flat at 0.99 through 0.2, falling from there (0.25 ->
        // 0.920, 0.3 -> 0.857, ..., 1.0 -> 0.448) - 0.2 is this preset's
        // own last full-headroom amount, smaller than RINGING's because
        // the root sits nearer the resonance.
        p(PluckVoice.SITAR, "LOW TONIC", "TUNE" to 0.0f, "DAMP" to 0.3f, "PICK" to 0.55f, "STRIKE" to 0.3f, "DOUBLE" to 0.4f, "BODY" to 0.2f),
        p(PluckVoice.SITAR, "BRIGHT MIZRAB", "TUNE" to 0.5f, "DAMP" to 0.25f, "PICK" to 0.95f, "STRIKE" to 0.15f, "DOUBLE" to 0.3f),
    )
}
