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
    private val KEY = Regex(
        """(?<![A-Za-z])([A-G][#b]?)\s*(maj|major|min|minor|m)?(?![A-Za-z])""",
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
        for (m in KEY.findAll(stem)) {
            val note = m.groupValues[1]
            val quality = m.groupValues[2].lowercase()
            val spec = when {
                quality.startsWith("min") || quality == "m" -> "${note}min"
                quality.startsWith("maj") -> "${note}maj"
                else -> note
            }
            runCatching { KeySpec.parse(spec) }.getOrNull()?.let { return it }
        }
        return null
    }
}
