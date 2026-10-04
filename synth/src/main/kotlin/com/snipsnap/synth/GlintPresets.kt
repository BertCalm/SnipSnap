package com.snipsnap.synth

/**
 * GLINT's factory presets (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6): eight per voice,
 * thirty-two in all.
 *
 * Each is one of the numbered candidates of the roster audition (`GlintRosterCandidates`: sixteen per voice, a
 * stratified spread of PEAK, BODY, BLOOM and DEPTH, rendered as held pads), and the trailing comment is its number.
 * The values are the candidate's, to the bit. The eight per voice were chosen by a stated rule: drop the shrill
 * (a centroid over 5 kHz, or most of the power above 4 kHz), drop the nearly-plain-sine (DEPTH 0.84 or more, or
 * nearly half the power in the fundamental), then take the eight whose settings differ most; and the owner heard
 * them and approved. The names describe what was measured.
 *
 * TUNE, DECAY and FOLLOW were not part of the spread, so they ship at their defaults: TUNE is overridden per zone in
 * a pad, a held pad ignores DECAY, and FOLLOW does nothing at the pitch the audition used. The final listen is where
 * FOLLOW is heard across the keyboard, and where it may be moved.
 *
 * Order is a contract (`--preset N`, `.first()` pins): the most typical first, each next the most different from
 * those before it. [GlintPresetsTest] pins the names, the order and the provenance.
 */
object GlintPresets {

    private fun p(voice: GlintVoice, name: String, vararg macros: Pair<String, Float>) =
        GlintPatch(name, voice, macros.toMap())

    fun forVoice(voice: GlintVoice): List<GlintPatch> = when (voice) {
        GlintVoice.SWEEP -> sweepPresets
        GlintVoice.STEP -> stepPresets
        GlintVoice.BRASS -> brassPresets
        GlintVoice.VOWEL -> vowelPresets
    }

    fun all(): List<GlintPatch> = GlintVoice.entries.flatMap { forVoice(it) }

    private val sweepPresets = listOf(
        p(GlintVoice.SWEEP, "WARM DRIFT", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.6666667f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.66f), // #15
        p(GlintVoice.SWEEP, "DEEP BLOOM", "TUNE" to 0.5f, "PEAK" to 0.06666667f, "FOLLOW" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #09
        p(GlintVoice.SWEEP, "OPEN SLIDE", "TUNE" to 0.5f, "PEAK" to 0.46666667f, "FOLLOW" to 0.8f, "BODY" to 0.46666667f, "BLOOM" to 0.8666667f, "DECAY" to 0.5f, "DEPTH" to 0.060000002f), // #01
        p(GlintVoice.SWEEP, "HOLLOW MID", "TUNE" to 0.5f, "PEAK" to 0.53333336f, "FOLLOW" to 0.8f, "BODY" to 0.93333334f, "BLOOM" to 0.6f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #10
        p(GlintVoice.SWEEP, "AMBER", "TUNE" to 0.5f, "PEAK" to 0.2f, "FOLLOW" to 0.8f, "BODY" to 0.2f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #03
        p(GlintVoice.SWEEP, "DARK RISE", "TUNE" to 0.5f, "PEAK" to 0.13333334f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #13
        p(GlintVoice.SWEEP, "WARM RISE", "TUNE" to 0.5f, "PEAK" to 0.4f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 0.13333334f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #08
        p(GlintVoice.SWEEP, "BRIGHT FALL", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "FOLLOW" to 0.8f, "BODY" to 0.73333335f, "BLOOM" to 0.8f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #14
    )

    private val stepPresets = listOf(
        p(GlintVoice.STEP, "SOFT LADDER", "TUNE" to 0.5f, "PEAK" to 0.46666667f, "FOLLOW" to 0.8f, "BODY" to 0.73333335f, "BLOOM" to 0.46666667f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #07
        p(GlintVoice.STEP, "WARM BUZZ", "TUNE" to 0.5f, "PEAK" to 0.0f, "FOLLOW" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 0.2f, "DECAY" to 0.5f, "DEPTH" to 0.17999999f), // #14
        p(GlintVoice.STEP, "LONG CLIMB", "TUNE" to 0.5f, "PEAK" to 0.13333334f, "FOLLOW" to 0.8f, "BODY" to 0.4f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #04
        p(GlintVoice.STEP, "FULL BUZZ", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.0f), // #15
        p(GlintVoice.STEP, "BRIGHT CLIMB", "TUNE" to 0.5f, "PEAK" to 0.4f, "FOLLOW" to 0.8f, "BODY" to 0.2f, "BLOOM" to 0.6666667f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #09
        p(GlintVoice.STEP, "RISING STEPS", "TUNE" to 0.5f, "PEAK" to 0.53333336f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #05
        p(GlintVoice.STEP, "TWIN STEPS", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "FOLLOW" to 0.8f, "BODY" to 1.0f, "BLOOM" to 0.73333335f, "DECAY" to 0.5f, "DEPTH" to 0.6f), // #03
        p(GlintVoice.STEP, "GLASS LADDER", "TUNE" to 0.5f, "PEAK" to 0.6f, "FOLLOW" to 0.8f, "BODY" to 0.93333334f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #16
    )

    private val brassPresets = listOf(
        p(GlintVoice.BRASS, "MELLOW HORN", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "FOLLOW" to 0.8f, "BODY" to 0.26666668f, "BLOOM" to 0.4f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #11
        p(GlintVoice.BRASS, "PLUSH HORN", "TUNE" to 0.5f, "PEAK" to 0.8f, "FOLLOW" to 0.8f, "BODY" to 1.0f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #10
        p(GlintVoice.BRASS, "DARK TUBA", "TUNE" to 0.5f, "PEAK" to 0.0f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.6f, "DECAY" to 0.5f, "DEPTH" to 0.120000005f), // #01
        p(GlintVoice.BRASS, "SWAY HORN", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "FOLLOW" to 0.8f, "BODY" to 0.0f, "BLOOM" to 0.8666667f, "DECAY" to 0.5f, "DEPTH" to 0.71999997f), // #02
        p(GlintVoice.BRASS, "BRIGHT HORN", "TUNE" to 0.5f, "PEAK" to 0.73333335f, "FOLLOW" to 0.8f, "BODY" to 0.6f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.24000001f), // #06
        p(GlintVoice.BRASS, "BREATHY HORN", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #05
        p(GlintVoice.BRASS, "STEADY HORN", "TUNE" to 0.5f, "PEAK" to 0.6f, "FOLLOW" to 0.8f, "BODY" to 0.8f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.17999999f), // #16
        p(GlintVoice.BRASS, "SLOW ROLL", "TUNE" to 0.5f, "PEAK" to 0.2f, "FOLLOW" to 0.8f, "BODY" to 0.13333334f, "BLOOM" to 0.93333334f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #13
    )

    private val vowelPresets = listOf(
        p(GlintVoice.VOWEL, "STILL AH", "TUNE" to 0.5f, "PEAK" to 0.4f, "BODY" to 0.4f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #10
        p(GlintVoice.VOWEL, "BUZZY EH", "TUNE" to 0.5f, "PEAK" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 0.8f, "DECAY" to 0.5f, "DEPTH" to 0.0f), // #14
        p(GlintVoice.VOWEL, "SOFT OH", "TUNE" to 0.5f, "PEAK" to 0.2f, "BODY" to 0.8666667f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #01
        p(GlintVoice.VOWEL, "RISING OO", "TUNE" to 0.5f, "PEAK" to 0.0f, "BODY" to 0.2f, "BLOOM" to 0.0f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #06
        p(GlintVoice.VOWEL, "PLAIN OO", "TUNE" to 0.5f, "PEAK" to 0.06666667f, "BODY" to 0.93333334f, "BLOOM" to 0.73333335f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #03
        p(GlintVoice.VOWEL, "FALLING EH", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "BODY" to 0.13333334f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #04
        p(GlintVoice.VOWEL, "RISING OH", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "BODY" to 0.8f, "BLOOM" to 0.2f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #11
        p(GlintVoice.VOWEL, "OPEN AH", "TUNE" to 0.5f, "PEAK" to 0.6f, "BODY" to 0.33333334f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.060000002f), // #16
    )
}
