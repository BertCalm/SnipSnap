package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.*

/**
 * Descriptive measurements for TEMPEST's round-robin takes, after EROSION's. They describe, they
 * do not judge: whether a take sounds like the same pad struck again is the owner's listen.
 */
internal object TempestRoundRobinMeasure {
    private const val FRAME = 2048
    private val edges = DoubleArray(33) { 80.0 * (18000.0 / 80.0).pow(it / 32.0) }

    private fun window(n: Int) = FloatArray(n) { (.5f - .5f * cos(2 * PI * it / (n - 1)).toFloat()) }

    /** Power in 32 log-frequency bands, 80 Hz to 18 kHz, of one [n]-sample frame starting at [start]. */
    private fun bands(x: FloatArray, start: Int, n: Int, w: FloatArray): DoubleArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) if (start + i < x.size) re[i] = x[start + i] * w[i]
        Fft.forward(re, im)
        val out = DoubleArray(32)
        for (k in 0..n / 2) {
            val hz = k.toDouble() * Dsp.RATE / n
            val b = edges.indexOfLast { it <= hz }
            if (b in 0 until 32 && hz < edges[32]) out[b] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        return out
    }

    /** Log power in 32 log-frequency bands, summed over the whole hit, dB. */
    private fun bandDb(x: FloatArray): DoubleArray {
        val w = window(FRAME)
        val power = DoubleArray(32)
        var start = 0
        do {
            val b = bands(x, start, FRAME, w)
            for (i in power.indices) power[i] += b[i]
            start += FRAME / 2
        } while (start < x.size)
        return DoubleArray(32) { 10 * log10(power[it] + 1e-20) }
    }

    /** Centred log power in 32 log-frequency bands, averaged over the whole hit (EROSION's measure). */
    fun shape(x: FloatArray): DoubleArray {
        val db = bandDb(x)
        val mean = db.average()
        return DoubleArray(32) { db[it] - mean }
    }

    fun distance(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) } / a.size)

    /**
     * [shape]'s distance over the bands that carry the hit: those within [rangeDb] of the accepted
     * hit's strongest band, each side centred over just those bands. A band 40 dB under the hit's
     * main band is inaudible beside it, yet a fresh noise draw swings it by several dB, which is
     * what drives [distance] up for noise voices whose takes change only their noise.
     */
    fun audibleDistance(accepted: FloatArray, take: FloatArray, rangeDb: Double = 30.0): Double {
        val a = bandDb(accepted)
        val b = bandDb(take)
        val keep = a.indices.filter { a[it] >= a.max() - rangeDb }
        val ma = keep.sumOf { a[it] } / keep.size
        val mb = keep.sumOf { b[it] } / keep.size
        return sqrt(keep.sumOf { ((a[it] - ma) - (b[it] - mb)).pow(2) } / keep.size)
    }

    /** First sample within 20 dB of the peak. */
    fun onsetMs(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        return x.indexOfFirst { abs(it) >= .1f * peak } * 1000.0 / Dsp.RATE
    }

    /** Time by which 90% of the energy has arrived. */
    fun t90Ms(x: FloatArray): Double {
        val total = x.sumOf { it.toDouble() * it }
        var sum = 0.0
        for (i in x.indices) { sum += x[i].toDouble() * x[i]; if (sum >= .9 * total) return i * 1000.0 / Dsp.RATE }
        return x.size * 1000.0 / Dsp.RATE
    }

    /** Energy left after removing [y]'s best gain match to [x], relative to [x]: a louder or quieter copy reads 0. Phase-sensitive. */
    fun residual(x: FloatArray, y: FloatArray): Double {
        val xx = x.sumOf { it.toDouble() * it }
        val n = max(x.size, y.size)
        val scale = (0 until n).sumOf { x.getOrElse(it) { 0f }.toDouble() * y.getOrElse(it) { 0f } } / xx
        return (0 until n).sumOf { (y.getOrElse(it) { 0f } - scale * x.getOrElse(it) { 0f }).pow(2) } / xx
    }

    /**
     * Phase-blind difference, dB: band powers of 512-sample frames (11.6 ms, hop 256) over time,
     * each floored 60 dB under the louder hit's loudest band, then the mean absolute difference over
     * the frames where either hit is within 40 dB of its loudest frame. Moving a waveform's phase
     * alone barely moves it; a fresh noise draw, a different beat between partials or a moved burst does.
     */
    fun texture(x: FloatArray, y: FloatArray): Double {
        val n = 512
        val w = window(n)
        val frames = (max(x.size, y.size) - n) / (n / 2) + 1
        val bx = Array(frames) { bands(x, it * n / 2, n, w) }
        val by = Array(frames) { bands(y, it * n / 2, n, w) }
        val top = max(bx.maxOf { it.max() }, by.maxOf { it.max() })
        val floor = top * 1e-6
        val ex = bx.map { it.sum() }
        val ey = by.map { it.sum() }
        val loud = max(ex.max(), ey.max()) * 1e-4
        var sum = 0.0
        var count = 0
        for (f in 0 until frames) if (ex[f] >= loud || ey[f] >= loud) for (b in 0 until 32) {
            sum += abs(10 * log10(max(bx[f][b], floor) / max(by[f][b], floor)))
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }
}
