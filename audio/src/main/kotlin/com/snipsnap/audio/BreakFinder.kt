package com.snipsnap.audio

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The Dig — finding the drum breaks *inside* full songs, so the
 * crate-digging ritual can start from the music itself instead of from
 * pre-cut breaks. Honest windowed DSP, no models:
 *
 * - **onset density** — a break hits steadily and often; verses and pads
 *   don't;
 * - **spectral flatness** — drums are broadband, sung and played notes
 *   are peaky ([FeatureExtractor]'s own measure, per window);
 * - **low-band pulse** — kicks make the sub *pump* (high variation of
 *   short-time low-band energy); sustained bass and pads just sit there.
 *
 * The three combine per window, adjacent scoring windows merge into
 * candidate sections, and candidates rank best-first. Deterministic —
 * the same song always yields the same dig.
 */
object BreakFinder {

    data class Candidate(
        val startSec: Float,
        val endSec: Float,
        /** Mean window score in 0..1 — how break-like the section is. */
        val score: Float,
    ) {
        val durationSec: Float get() = endSec - startSec
    }

    data class Config(
        val windowSec: Float = 1.0f,
        val hopSec: Float = 0.5f,
        /** Shorter sections than this aren't a break, they're a fill. */
        val minSectionSec: Float = 2.0f,
        /** Absolute score floor — nothing in a song of pads gets promoted. */
        val minScore: Float = 0.45f,
    ) {
        init {
            require(windowSec > 0f && hopSec > 0f && hopSec <= windowSec) {
                "window/hop must be positive with hop <= window"
            }
            require(minSectionSec >= windowSec) { "minSectionSec must cover at least one window" }
            require(minScore in 0f..1f) { "minScore is 0..1" }
        }
    }

    /** A steady break runs at least this many hits per second at full credit. */
    private const val FULL_CREDIT_ONSETS_PER_SEC = 6f

    /** Flatness at or above this reads as fully broadband. */
    private const val FULL_CREDIT_FLATNESS = 0.25f

    private const val LOW_BAND_HZ = 150f
    private const val PULSE_SUBFRAME_SEC = 0.05f

    fun find(snip: Snip, config: Config = Config()): List<Candidate> {
        val rate = snip.sampleRate
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        val win = (config.windowSec * rate).toInt()
        val hop = (config.hopSec * rate).toInt()
        if (mono.size < win) return emptyList()

        val onsets = Transients.detect(Snip(mono, 1, rate))
        val onsetFrames = IntArray(onsets.size) { onsets[it].frame }
        val low = lowpassed(mono, rate)

        val count = (mono.size - win) / hop + 1
        val scores = FloatArray(count)
        for (w in 0 until count) {
            val start = w * hop
            val end = start + win

            var energy = 0f
            for (i in start until end) energy += mono[i] * mono[i]
            if (energy / win < 1e-7f) continue // silence stays scoreless

            val density = countInRange(onsetFrames, start, end) / config.windowSec
            val dNorm = (density / FULL_CREDIT_ONSETS_PER_SEC).coerceAtMost(1f)

            val flatness = FeatureExtractor.extract(Snip(mono.copyOfRange(start, end), 1, rate)).flatness
            val fNorm = (flatness / FULL_CREDIT_FLATNESS).coerceAtMost(1f)

            scores[w] = 0.45f * dNorm + 0.30f * fNorm + 0.25f * lowPulse(low, start, end, rate)
        }

        // Adjacent windows above the floor merge into one section.
        val candidates = mutableListOf<Candidate>()
        var i = 0
        while (i < count) {
            if (scores[i] < config.minScore) {
                i++
                continue
            }
            var j = i
            while (j + 1 < count && scores[j + 1] >= config.minScore) j++
            val startSec = i * config.hopSec
            val endSec = j * config.hopSec + config.windowSec
            if (endSec - startSec >= config.minSectionSec) {
                var mean = 0f
                for (k in i..j) mean += scores[k]
                candidates += Candidate(startSec, endSec, mean / (j - i + 1))
            }
            i = j + 1
        }
        return candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.startSec })
    }

    /**
     * How much the low band *pumps* inside the window: the coefficient of
     * variation of short-time low-band energy. Kicks pulse it; sustained
     * bass and pads hold it flat.
     */
    internal fun lowPulse(low: FloatArray, start: Int, end: Int, rate: Int): Float {
        val sub = (PULSE_SUBFRAME_SEC * rate).toInt().coerceAtLeast(1)
        val energies = ArrayList<Float>((end - start) / sub + 1)
        var i = start
        while (i + sub <= end) {
            var e = 0f
            for (k in i until i + sub) e += low[k] * low[k]
            energies += sqrt(e / sub)
            i += sub
        }
        if (energies.size < 4) return 0f
        val mean = energies.sum() / energies.size
        if (mean < 1e-5f) return 0f
        var varAcc = 0f
        for (e in energies) {
            val d = e - mean
            varAcc += d * d
        }
        val cv = sqrt(varAcc / energies.size) / mean
        return (cv / 1.2f).coerceAtMost(1f)
    }

    private fun lowpassed(mono: FloatArray, rate: Int): FloatArray {
        val a = (1.0 - exp(-2.0 * Math.PI * LOW_BAND_HZ / rate)).toFloat()
        val out = FloatArray(mono.size)
        var y = 0f
        for (i in mono.indices) {
            y += a * (mono[i] - y)
            out[i] = y
        }
        return out
    }

    /** Onset frames are sorted; count those in [start, end) by binary search. */
    private fun countInRange(sorted: IntArray, start: Int, end: Int): Int {
        var lo = sorted.binarySearch(start).let { if (it < 0) -it - 1 else it }
        var hi = sorted.binarySearch(end).let { if (it < 0) -it - 1 else it }
        // binarySearch finds *an* equal element; walk to the range edges.
        while (lo > 0 && sorted[lo - 1] >= start) lo--
        while (hi < sorted.size && sorted[hi] < end) hi++
        return hi - lo
    }
}

private fun IntArray.binarySearch(key: Int): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            this[mid] < key -> lo = mid + 1
            this[mid] > key -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}
