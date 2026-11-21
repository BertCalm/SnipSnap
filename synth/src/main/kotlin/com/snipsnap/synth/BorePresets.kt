package com.snipsnap.synth

/**
 * BORE's factory roster: eight for FLUTE and for SAX (and one each for the five voices kept on 2026-11-20, [heard]), authored from the R1 measurements
 * (the speaking windows, onsets and timbre spans in [Bore]'s KDoc) - and
 * **provisional**. It was authored before anything in BORE had been heard.
 * SAX has been heard since, in the listening rounds the BORE design doc
 * records (`docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md`,
 * "R1, the reed's bite" to "Round 1.6"; `docs/SYNTH_ROADMAP.md`'s S19 row
 * sums them up): the owner asked for more reed bite, kept the faster
 * attack, and on 2026-10-04 called the SOLO LOOP note's one-shot good and
 * its periodic loop a cheap keyboard. The loop the two LOOP presets play
 * now (Round 1.7, cut from the held one-shot) has not been heard, and
 * FLUTE's loop never has been. The audition gate
 * (`./gradlew :synth:generateBoreAudition`, then `testkit/bore-audition/index.html`) is where a human decides which of these
 * is a woodwind and which is only a tone that measures like one; a name that
 * does not survive it is renamed or dropped, not defended.
 *
 * What the roster leans on, from the measurements:
 *  - FLUTE speaks at every setting (onset 0.06-0.31 s, fundamental leading),
 *    so its presets differ by register, breath noise (BREATH), brightness (LIP),
 *    attack (CHIFF) and length (HOLD).
 *  - SAX was slow to speak below about C4 in R1 (0.3-0.5 s to full amplitude),
 *    so the short, hard-tongued reeds sit high (TUNE at or over 0.75) and the
 *    low ones are swells and held notes, not stabs. Round 1.4's tongue's seed
 *    took a default note to 0.10 s at C3 ([Bore.TONGUE_SEED]); the roster has
 *    not been re-laid out for it, so a low stab is now possible and unauthored.
 *  - HOLD at 1.0 is a LOOP, rendered dry and seamless.
 *  - OVERBLOW (FLUTE only, round two) is 0 on every factory preset: the presets were authored, and
 *    heard, before it existed, and their sound is frozen by `BoreFrozenSoundTest`. A cracked note is the
 *    player's gesture to ask for, not a preset's default.
 *
 * Names are plain words: no maker, no model, no phone-book instrument.
 */
object BorePresets {

    private fun p(voice: BoreVoice, name: String, vararg macros: Pair<String, Float>) =
        BorePatch(name, voice, macros.toMap())

    fun forVoice(voice: BoreVoice): List<BorePatch> = when (voice) {
        BoreVoice.FLUTE -> flutePresets
        BoreVoice.SAX -> saxPresets
        BoreVoice.HOLLOW -> listOf(heard(BoreVoice.HOLLOW, "LOW WOOD REED"))
        BoreVoice.SHAWM -> listOf(heard(BoreVoice.SHAWM, "PINCHED REED"))
        BoreVoice.SHAKU -> listOf(heard(BoreVoice.SHAKU, "BAMBOO BREATH"))
        BoreVoice.PANPIPE -> listOf(heard(BoreVoice.PANPIPE, "CANE PIPES"))
        BoreVoice.VESSEL -> listOf(heard(BoreVoice.VESSEL, "ROUND CLAY"))
    }

    /**
     * The one preset of each voice kept on 2026-11-20 (HOLLOW, SHAWM, SHAKU, PANPIPE, VESSEL: rounds bore-voices-1 and touch-bore-1): the
     * note the owner heard as touch-bore-1's hard note, which is the kept sound. Every macro at the voice's default, TUNE 0.5 (an octave
     * over the root: HOLLOW A3, SHAWM G4, SHAKU D5, PANPIPE C5, VESSEL C5) and HOLD at two and a half seconds of breath. Over the
     * classifier's 1.5 s line, so it reads and is filed LOOP by length; it is a one-shot, not a HOLD 1 loop. HoldHumanizePinsTest pins each.
     */
    private fun heard(voice: BoreVoice, name: String) =
        BorePatch(name, voice, Bore.defaults(voice) + mapOf("TUNE" to HEARD_TUNE, "HOLD" to HEARD_HOLD))

    private const val HEARD_TUNE = 0.5f

    /** HOLD for 2.5 s: [Bore]'s HOLD law (exponential from [Bore.HOLD_MIN_SECONDS] to [Bore.HOLD_MAX_SECONDS] below [Bore.LOOP_THRESHOLD]) inverted. */
    private val HEARD_HOLD: Float =
        (Bore.LOOP_THRESHOLD * kotlin.math.ln(2.5f / Bore.HOLD_MIN_SECONDS) / kotlin.math.ln(Bore.HOLD_MAX_SECONDS / Bore.HOLD_MIN_SECONDS)).toFloat()

    fun all(): List<BorePatch> = BoreVoice.entries.flatMap { forVoice(it) }

    private val flutePresets = listOf(
        p(BoreVoice.FLUTE, "BREATHY", "TUNE" to 0.5f, "BREATH" to 0.95f, "LIP" to 0.25f, "CHIFF" to 0.3f, "HOLD" to 0.4f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "CLEAN TONE", "TUNE" to 0.45f, "BREATH" to 0.3f, "LIP" to 0.55f, "CHIFF" to 0.2f, "HOLD" to 0.5f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "SOFT SWELL", "TUNE" to 0.4f, "BREATH" to 0.2f, "LIP" to 0.3f, "CHIFF" to 0f, "HOLD" to 0.6f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "HARD TONGUE", "TUNE" to 0.5f, "BREATH" to 0.6f, "LIP" to 0.6f, "CHIFF" to 1f, "HOLD" to 0.2f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "LOW REGISTER", "TUNE" to 0.1f, "BREATH" to 0.5f, "LIP" to 0.35f, "CHIFF" to 0.4f, "HOLD" to 0.5f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "HIGH AIR", "TUNE" to 0.9f, "BREATH" to 0.85f, "LIP" to 0.75f, "CHIFF" to 0.5f, "HOLD" to 0.3f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "LONG NOTE", "TUNE" to 0.55f, "BREATH" to 0.5f, "LIP" to 0.5f, "CHIFF" to 0.3f, "HOLD" to 0.9f, "OVERBLOW" to 0f),
        p(BoreVoice.FLUTE, "STEADY LOOP", "TUNE" to 0.5f, "BREATH" to 0.6f, "LIP" to 0.5f, "CHIFF" to 0.3f, "HOLD" to 1f, "OVERBLOW" to 0f),
    )

    private val saxPresets = listOf(
        p(BoreVoice.SAX, "LOW HONK", "TUNE" to 0.05f, "BREATH" to 0.6f, "LIP" to 0.15f, "CHIFF" to 0.15f, "HOLD" to 0.3f),
        p(BoreVoice.SAX, "SMOOTH", "TUNE" to 0.35f, "BREATH" to 0.5f, "LIP" to 0.65f, "CHIFF" to 0.3f, "HOLD" to 0.5f),
        p(BoreVoice.SAX, "BITE", "TUNE" to 0.6f, "BREATH" to 0.75f, "LIP" to 0.1f, "CHIFF" to 0.7f, "HOLD" to 0.25f),
        p(BoreVoice.SAX, "AIRY REED", "TUNE" to 0.45f, "BREATH" to 0.3f, "LIP" to 0.7f, "CHIFF" to 0.15f, "HOLD" to 0.45f),
        p(BoreVoice.SAX, "HIGH STAB", "TUNE" to 0.85f, "BREATH" to 0.8f, "LIP" to 0.2f, "CHIFF" to 1f, "HOLD" to 0f),
        p(BoreVoice.SAX, "PAD REED", "TUNE" to 0.5f, "BREATH" to 0.55f, "LIP" to 0.5f, "CHIFF" to 0.1f, "HOLD" to 0.95f),
        p(BoreVoice.SAX, "SOLO LOOP", "TUNE" to 0.4f, "BREATH" to 0.6f, "LIP" to 0.45f, "CHIFF" to 0.35f, "HOLD" to 1f),
        p(BoreVoice.SAX, "GROWL", "TUNE" to 0.12f, "BREATH" to 0.65f, "LIP" to 0f, "CHIFF" to 0.15f, "HOLD" to 0.4f),
    )
}
