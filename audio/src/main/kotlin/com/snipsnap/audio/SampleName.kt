package com.snipsnap.audio

/**
 * What a filename is willing to tell us.
 *
 * Every sample library writes tempo and key into the stem because that is
 * how producers browse. [Tempo.label] writes `92bpm` on the way out; this
 * reads it — and the handful of other spellings the rest of the world uses
 * — on the way in. Hints are preferred over detection when they are
 * plausible, because a library's own metadata beats our autocorrelation;
 * detection stays the fallback and the cross-check.
 */
object SampleName {

    data class Hints(val bpm: Float?, val key: KeySpec?)

    /** Tempi outside this are take numbers, sample rates, or years. */
    private val PLAUSIBLE_BPM = 60f..200f

    private val BPM_LABELLED = Regex("""(\d{2,3})\s*-?\s*bpm""", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER = Regex("""(?<![\d.])(\d{2,3})(?![\d.])""")

    // A bare note letter ("A", "F", "G"...) is never read as a key — take
    // letters, drum abbreviations (F_kick, hat_D_closed), and stray words
    // ("C_to_G_riser") vastly outnumber real bare-major keys in the wild,
    // and Task 2 lets a filename hint override a correct audio-detected
    // key. A wrong override is worse than no hint, so a key needs
    // corroboration — one of three forms, each captured as
    // (note, accidental, quality):
    //   1. KEY_QUALIFIED — an attached quality suffix: Am, Em, Cmin, Fmaj,
    //      Bbmin, C#m, F#maj.
    //   2. KEY_ACCIDENTAL — a sharp/flat with no quality: Bb, C#. Nobody
    //      writes "Bb" or "C#" as an ordinary word or abbreviation, so the
    //      accidental symbol is itself evidence of musical intent, the
    //      same way a quality suffix is.
    //   3. KEY_WORD — the explicit word "key" nearby: "key of C", "keyC",
    //      "_key_Am_". The word makes intent unambiguous even for a bare
    //      major letter.
    private val KEY_QUALIFIED = Regex(
        """(?<![A-Za-z])([A-G])([#b]?)\s*(maj|major|min|minor|m)(?![A-Za-z])""",
    )
    private val KEY_ACCIDENTAL = Regex(
        """(?<![A-Za-z])([A-G])([#b])\s*(maj|major|min|minor|m)?(?![A-Za-z])""",
    )
    private val KEY_WORD = Regex(
        """(?i)(?<![A-Za-z])key[\s_-]*(?:of[\s_-]*)?([A-Ga-g])([#b]?)[\s_-]*(maj|major|min|minor|m)?(?![A-Za-z])""",
    )

    fun parse(stem: String): Hints = Hints(bpm = bpmOf(stem), key = keyOf(stem))

    private fun bpmOf(stem: String): Float? {
        BPM_LABELLED.find(stem)?.groupValues?.get(1)?.toFloatOrNull()
            ?.takeIf { it in PLAUSIBLE_BPM }?.let { return it }
        // No "bpm" word: a bare number is a tempo only if it could be one.
        return BARE_NUMBER.findAll(stem)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .firstOrNull { it in PLAUSIBLE_BPM }
    }

    private fun keyOf(stem: String): KeySpec? {
        matchKey(KEY_QUALIFIED, stem)?.let { return it }
        matchKey(KEY_ACCIDENTAL, stem)?.let { return it }
        matchKey(KEY_WORD, stem)?.let { return it }
        return null
    }

    private fun matchKey(pattern: Regex, stem: String): KeySpec? {
        for (m in pattern.findAll(stem)) {
            val note = m.groupValues[1]
            val accidental = m.groupValues[2]
            val quality = m.groupValues[3].lowercase()
            val suffix = when {
                quality.startsWith("min") || quality == "m" -> "min"
                quality.startsWith("maj") -> "maj"
                else -> ""
            }
            runCatching { KeySpec.parse("$note$accidental$suffix") }.getOrNull()?.let { return it }
        }
        return null
    }
}
