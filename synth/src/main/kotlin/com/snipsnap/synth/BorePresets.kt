package com.snipsnap.synth

/**
 * BORE's factory roster: eight per voice, authored from the R1 measurements
 * (the speaking windows, onsets and timbre spans in [Bore]'s KDoc) - and
 * **provisional**: nothing here was listened to. The audition gate
 * (`./gradlew :synth:generateBoreAudition`, then `testkit/bore-audition/index.html`) is where a human decides which of these
 * is a woodwind and which is only a tone that measures like one; a name that
 * does not survive it is renamed or dropped, not defended.
 *
 * What the roster leans on, from the measurements:
 *  - FLUTE speaks at every setting (onset 0.06-0.31 s, fundamental leading),
 *    so its presets differ by register, breath noise (BREATH), brightness (LIP),
 *    attack (CHIFF) and length (HOLD).
 *  - SAX is slow to speak below about C4 (0.3-0.5 s to full amplitude, R1's
 *    stated limit), so the short, hard-tongued reeds sit high (TUNE at or over
 *    0.75) and the low ones are swells and held notes, not stabs.
 *  - HOLD at 1.0 is a LOOP, rendered dry and seamless.
 *
 * Names are plain words: no maker, no model, no phone-book instrument.
 */
object BorePresets {

    private fun p(voice: BoreVoice, name: String, vararg macros: Pair<String, Float>) =
        BorePatch(name, voice, macros.toMap())

    fun forVoice(voice: BoreVoice): List<BorePatch> = when (voice) {
        BoreVoice.FLUTE -> flutePresets
        BoreVoice.SAX -> saxPresets
    }

    fun all(): List<BorePatch> = BoreVoice.entries.flatMap { forVoice(it) }

    private val flutePresets = listOf(
        p(BoreVoice.FLUTE, "BREATHY", "TUNE" to 0.5f, "BREATH" to 0.95f, "LIP" to 0.25f, "CHIFF" to 0.3f, "HOLD" to 0.4f),
        p(BoreVoice.FLUTE, "CLEAN TONE", "TUNE" to 0.45f, "BREATH" to 0.3f, "LIP" to 0.55f, "CHIFF" to 0.2f, "HOLD" to 0.5f),
        p(BoreVoice.FLUTE, "SOFT SWELL", "TUNE" to 0.4f, "BREATH" to 0.2f, "LIP" to 0.3f, "CHIFF" to 0f, "HOLD" to 0.6f),
        p(BoreVoice.FLUTE, "HARD TONGUE", "TUNE" to 0.5f, "BREATH" to 0.6f, "LIP" to 0.6f, "CHIFF" to 1f, "HOLD" to 0.2f),
        p(BoreVoice.FLUTE, "LOW REGISTER", "TUNE" to 0.1f, "BREATH" to 0.5f, "LIP" to 0.35f, "CHIFF" to 0.4f, "HOLD" to 0.5f),
        p(BoreVoice.FLUTE, "HIGH AIR", "TUNE" to 0.9f, "BREATH" to 0.85f, "LIP" to 0.75f, "CHIFF" to 0.5f, "HOLD" to 0.3f),
        p(BoreVoice.FLUTE, "LONG NOTE", "TUNE" to 0.55f, "BREATH" to 0.5f, "LIP" to 0.5f, "CHIFF" to 0.3f, "HOLD" to 0.9f),
        p(BoreVoice.FLUTE, "STEADY LOOP", "TUNE" to 0.5f, "BREATH" to 0.6f, "LIP" to 0.5f, "CHIFF" to 0.3f, "HOLD" to 1f),
    )

    private val saxPresets = listOf(
        p(BoreVoice.SAX, "LOW HONK", "TUNE" to 0.05f, "BREATH" to 0.7f, "LIP" to 0.15f, "CHIFF" to 0.5f, "HOLD" to 0.3f),
        p(BoreVoice.SAX, "SMOOTH", "TUNE" to 0.35f, "BREATH" to 0.5f, "LIP" to 0.65f, "CHIFF" to 0.3f, "HOLD" to 0.5f),
        p(BoreVoice.SAX, "BITE", "TUNE" to 0.6f, "BREATH" to 0.75f, "LIP" to 0.1f, "CHIFF" to 0.7f, "HOLD" to 0.25f),
        p(BoreVoice.SAX, "AIRY REED", "TUNE" to 0.45f, "BREATH" to 0.3f, "LIP" to 0.7f, "CHIFF" to 0.15f, "HOLD" to 0.45f),
        p(BoreVoice.SAX, "HIGH STAB", "TUNE" to 0.85f, "BREATH" to 0.8f, "LIP" to 0.2f, "CHIFF" to 1f, "HOLD" to 0f),
        p(BoreVoice.SAX, "PAD REED", "TUNE" to 0.5f, "BREATH" to 0.55f, "LIP" to 0.5f, "CHIFF" to 0.1f, "HOLD" to 0.95f),
        p(BoreVoice.SAX, "SOLO LOOP", "TUNE" to 0.4f, "BREATH" to 0.6f, "LIP" to 0.45f, "CHIFF" to 0.35f, "HOLD" to 1f),
        p(BoreVoice.SAX, "GROWL", "TUNE" to 0.12f, "BREATH" to 0.85f, "LIP" to 0f, "CHIFF" to 0.5f, "HOLD" to 0.4f),
    )
}
