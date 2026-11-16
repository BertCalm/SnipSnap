package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import kotlin.math.*

/**
 * Descriptive measurements for EROSION's round-robin takes. They describe, they do not judge:
 * whether a take sounds like the same instrument hit again is the owner's listen.
 */
internal object ErosionRoundRobinMeasure {
    private const val FRAME = 2048
    private val edges = DoubleArray(33) { 80.0 * (18000.0 / 80.0).pow(it / 32.0) }

    /** Centred log power in 32 log-frequency bands, 80 Hz to 18 kHz, averaged over the whole hit. */
    fun shape(x: FloatArray): DoubleArray {
        val power = DoubleArray(FRAME / 2 + 1)
        var start = 0
        do {
            val re = FloatArray(FRAME)
            val im = FloatArray(FRAME)
            for (i in 0 until FRAME) if (start + i < x.size)
                re[i] = x[start + i] * (.5f - .5f * cos(2 * PI * i / (FRAME - 1)).toFloat())
            Fft.forward(re, im)
            for (k in power.indices) power[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            start += FRAME / 2
        } while (start < x.size)
        val bands = DoubleArray(32)
        for (k in power.indices) {
            val hz = k.toDouble() * Dsp.RATE / FRAME
            val b = edges.indexOfLast { it <= hz }
            if (b in 0 until 32 && hz < edges[32]) bands[b] += power[k]
        }
        val db = DoubleArray(32) { 10 * log10(bands[it] + 1e-20) }
        val mean = db.average()
        return DoubleArray(32) { db[it] - mean }
    }

    fun distance(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) } / a.size)

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

    /** Energy left after removing [y]'s best gain match to [x], relative to [x]: a louder or quieter copy reads 0. */
    fun residual(x: FloatArray, y: FloatArray): Double {
        val xx = x.sumOf { it.toDouble() * it }
        val n = max(x.size, y.size)
        val scale = (0 until n).sumOf { x.getOrElse(it) { 0f }.toDouble() * y.getOrElse(it) { 0f } } / xx
        return (0 until n).sumOf { (y.getOrElse(it) { 0f } - scale * x.getOrElse(it) { 0f }).pow(2) } / xx
    }

    fun loudness(x: FloatArray) = Loudness.of(x, 1, Dsp.RATE).toDouble()
}
