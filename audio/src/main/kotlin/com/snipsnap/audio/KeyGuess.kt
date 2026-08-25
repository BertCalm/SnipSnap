package com.snipsnap.audio

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The capture names its own key: a pitch-class histogram over whatever
 * pitched material the chop produced, scored against every major and
 * minor key. Coverage — the fraction of pitched hits that sit in the
 * scale — is the confidence; ties between relatives (A minor and C major
 * share every note) break toward the root the material actually leans on.
 *
 * Honest refusals over guesses: fewer than [MIN_PITCHES] pitched hits, or
 * fewer than [MIN_DISTINCT] distinct pitch classes (one bass note looped
 * is not a key), return null.
 */
object KeyGuess {

    const val MIN_PITCHES = 3
    const val MIN_DISTINCT = 3

    /** What a caller needs to clear before acting on a guess unasked. */
    const val SURE_CONFIDENCE = 0.6f

    data class Guess(val key: KeySpec, val confidence: Float)

    fun guess(pitchesHz: List<Float>): Guess? {
        val pcs = pitchesHz.filter { it > 0f }.map { pitchClass(it) }
        if (pcs.size < MIN_PITCHES || pcs.distinct().size < MIN_DISTINCT) return null

        val counts = FloatArray(12)
        pcs.forEach { counts[it]++ }
        val total = pcs.size.toFloat()

        var best: Guess? = null
        var bestScore = -1f
        for (root in 0 until 12) {
            for (scale in listOf(Scale.MAJOR, Scale.MINOR)) {
                val inScale = scale.intervals.sumOf { counts[(root + it) % 12].toDouble() }.toFloat()
                val coverage = inScale / total
                // The relative-key tie-breaker: weight the root's own share.
                val score = coverage + 0.25f * (counts[root] / total)
                if (score > bestScore) {
                    bestScore = score
                    best = Guess(KeySpec(root, scale), coverage.coerceIn(0f, 1f))
                }
            }
        }
        return best
    }

    /** 0..11 semitones above C, from a frequency. */
    fun pitchClass(hz: Float): Int {
        require(hz > 0f) { "hz must be positive: $hz" }
        val midi = (12.0 * ln(hz / 440.0) / ln(2.0) + 69.0).roundToInt()
        return ((midi % 12) + 12) % 12
    }
}
