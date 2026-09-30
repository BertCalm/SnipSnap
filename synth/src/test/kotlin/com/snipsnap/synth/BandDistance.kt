package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.pow

/**
 * The GLINT distance measure (docs/superpowers/specs/2026-09-28-glint-voice-identity-design.md §7,
 * carried into 2026-09-29-glint-paths-design.md §5): energy-weighted third-octave L1 between
 * energy-normalised band vectors, 0 (identical) to 2 (disjoint).
 *
 * Tiles 4096-sample frames at hop 2048 across the whole span. `Fft.magnitudeSpectrum` applies
 * the Hann window itself, and reads only its first `size` samples, so one call over a long
 * buffer measures its first 93 ms and nothing else - the bug the 2026-09-28 probe caught.
 */
internal object BandDistance {
    private const val N = 4096
    private const val HOP = 2048
    private val CENTRES: List<Double> = generateSequence(50.0) { it * 2.0.pow(1.0 / 3.0) }.takeWhile { it <= 16000.0 }.toList()

    fun bands(x: FloatArray, rate: Int): DoubleArray {
        val e = DoubleArray(CENTRES.size)
        var start = 0
        do {
            val frame = FloatArray(N) { i -> if (start + i < x.size) x[start + i] else 0f }
            val mag = Fft.magnitudeSpectrum(frame, N)
            for (bin in mag.indices) {
                val hz = bin.toDouble() * rate / N
                for (b in CENTRES.indices) {
                    val lo = CENTRES[b] / 2.0.pow(1.0 / 6.0)
                    val hi = CENTRES[b] * 2.0.pow(1.0 / 6.0)
                    if (hz >= lo && hz < hi) { e[b] += mag[bin].toDouble() * mag[bin]; break }
                }
            }
            start += HOP
        } while (start + N / 2 < x.size)
        val sum = e.sum()
        require(sum > 0) { "BandDistance of a silent span" }
        for (b in e.indices) e[b] /= sum
        return e
    }

    fun whole(a: FloatArray, b: FloatArray, rate: Int): Double {
        val x = bands(a, rate); val y = bands(b, rate)
        return x.indices.sumOf { abs(x[it] - y[it]) }
    }

    /**
     * [whole] of each of [segments] equal consecutive spans, in time order: how far apart the
     * two are at the start of the note, through it, and at the end.
     */
    fun segmentDistances(a: FloatArray, b: FloatArray, rate: Int, segments: Int = 4): DoubleArray {
        val len = minOf(a.size, b.size) / segments
        return DoubleArray(segments) { s ->
            whole(a.copyOfRange(s * len, (s + 1) * len), b.copyOfRange(s * len, (s + 1) * len), rate)
        }
    }

    /** The mean of [segmentDistances]. */
    fun path(a: FloatArray, b: FloatArray, rate: Int, segments: Int = 4): Double =
        segmentDistances(a, b, rate, segments).average()
}
