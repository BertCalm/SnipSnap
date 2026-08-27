package com.snipsnap.audio

import kotlin.math.sqrt

/**
 * More-like-this — nearest-neighbour over the extractor's own features.
 * The same measurements the classifier hears become a compact normalized
 * vector, and distance between vectors is "does it sound alike": another
 * snare sits near a snare, a kick sits far from a hat. Level is left out
 * on purpose — a quiet snare is still a snare.
 *
 * Deterministic: same audio, same vector, same ranking.
 */
object Similar {

    /** The vector's dimensionality, for sanity checks and future storage. */
    const val DIMENSIONS = 9

    /**
     * [Features] as a normalized vector, every dimension in ~0..1 so no
     * single measure dominates the distance.
     */
    fun vector(f: Features): FloatArray = floatArrayOf(
        (f.centroidHz / 8_000f).coerceIn(0f, 1f),
        (f.rolloffHz / 12_000f).coerceIn(0f, 1f),
        f.flatness.coerceIn(0f, 1f),
        f.zeroCrossingRate.coerceIn(0f, 1f),
        f.lowRatio.coerceIn(0f, 1f),
        f.midRatio.coerceIn(0f, 1f),
        f.highRatio.coerceIn(0f, 1f),
        (f.durationSeconds / 2f).coerceIn(0f, 1f),
        (f.decayMs / 500f).coerceIn(0f, 1f),
    )

    /** Euclidean distance between two feature vectors. 0 = the same sound. */
    fun distance(a: Features, b: Features): Float {
        val va = vector(a)
        val vb = vector(b)
        var acc = 0f
        for (i in va.indices) {
            val d = va[i] - vb[i]
            acc += d * d
        }
        return sqrt(acc)
    }

    /**
     * Rank [candidates] by closeness to [target], nearest first. Ties
     * break by index, so the order is total and stable.
     */
    fun <T> rank(target: Features, candidates: List<Pair<T, Features>>): List<Pair<T, Float>> =
        candidates.mapIndexed { i, (id, f) -> Triple(id, distance(target, f), i) }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first to it.second }
}
