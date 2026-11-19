package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.*

/**
 * Descriptive measurements for TERRA's round-robin takes. They describe, they do not judge:
 * whether a take sounds like the same drum struck again is the owner's listen. The owner kept the
 * kit's takes by ear in terra-rr-1 (16 Nov 2026).
 */
internal object TerraRoundRobinMeasure {
    private const val FRAME = 2048
    private const val SPECTRO_FRAME = 1024
    private const val SPECTRO_HOP = 256
    /** Cells this far under the loudest one are left out of [spectroDistance]: below them is the tail's noise floor. */
    const val SPECTRO_RANGE_DB = 50.0
    private val edges = DoubleArray(33) { 80.0 * (18000.0 / 80.0).pow(it / 32.0) }

    private fun hann(i: Int, n: Int) = (.5f - .5f * cos(2 * PI * i / (n - 1)).toFloat())

    /** Power in 32 log-frequency bands, 80 Hz to 18 kHz, from one FFT's [re] and [im] of [size] points. */
    private fun bands(re: FloatArray, im: FloatArray, size: Int, into: DoubleArray) {
        for (k in 0..size / 2) {
            val hz = k.toDouble() * Dsp.RATE / size
            val b = edges.indexOfLast { it <= hz }
            if (b in 0 until 32 && hz < edges[32]) into[b] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
    }

    /**
     * Centred log power in 32 log-frequency bands, 80 Hz to 18 kHz, averaged over the whole hit,
     * each band floored [SPECTRO_RANGE_DB] under the loudest: a band that far down (an agogô's
     * 80 Hz, a udu's 15 kHz) is not part of the hit's shape, and its noise would swamp the measure.
     */
    fun shape(x: FloatArray): DoubleArray {
        val power = DoubleArray(32)
        var start = 0
        do {
            val re = FloatArray(FRAME)
            val im = FloatArray(FRAME)
            for (i in 0 until FRAME) if (start + i < x.size) re[i] = x[start + i] * hann(i, FRAME)
            Fft.forward(re, im)
            bands(re, im, FRAME, power)
            start += FRAME / 2
        } while (start < x.size)
        val raw = DoubleArray(32) { 10 * log10(power[it] + 1e-20) }
        val floor = raw.max() - SPECTRO_RANGE_DB
        val db = DoubleArray(32) { max(raw[it], floor) }
        val mean = db.average()
        return DoubleArray(32) { db[it] - mean }
    }

    fun distance(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) } / a.size)

    /** First sample within 20 dB of the peak. */
    fun onsetMs(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        return x.indexOfFirst { abs(it) >= .1f * peak } * 1000.0 / Dsp.RATE
    }

    /** Where the hit starts: the first sample within 40 dB of the peak (TerraMeasure's onset). */
    fun startMs(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        return x.indexOfFirst { abs(it) >= .01f * peak } * 1000.0 / Dsp.RATE
    }

    /** Time by which 90% of the energy has arrived. */
    fun t90Ms(x: FloatArray): Double {
        val total = x.sumOf { it.toDouble() * it }
        var sum = 0.0
        for (i in x.indices) { sum += x[i].toDouble() * x[i]; if (sum >= .9 * total) return i * 1000.0 / Dsp.RATE }
        return x.size * 1000.0 / Dsp.RATE
    }

    /** The band levels in dB of 23 ms frames every 5.8 ms over the first [maxMs] of [x]. */
    private fun spectrogram(x: FloatArray, maxMs: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val end = min(x.size, (maxMs * Dsp.RATE / 1000).toInt())
        var s = 0
        while (s < end) {
            val re = FloatArray(SPECTRO_FRAME)
            val im = FloatArray(SPECTRO_FRAME)
            for (i in 0 until SPECTRO_FRAME) if (s + i < x.size) re[i] = x[s + i] * hann(i, SPECTRO_FRAME)
            Fft.forward(re, im)
            val p = DoubleArray(32)
            bands(re, im, SPECTRO_FRAME, p)
            out += DoubleArray(32) { 10 * log10(p[it] + 1e-20) }
            s += SPECTRO_HOP
        }
        return out
    }

    /**
     * How far apart two hits sound, blind to phase: the mean absolute difference, in dB, between
     * their band levels in 23 ms frames over the first [maxMs], over the cells within
     * [SPECTRO_RANGE_DB] of the louder hit's loudest cell (both floored there). Two hits that
     * differ only in phase, as a noise burst re-drawn with the same spectrum nearly does, read
     * near 0; a copy reads exactly 0.
     */
    fun spectroDistance(a: FloatArray, b: FloatArray, maxMs: Double = 2000.0): Double {
        val sa = spectrogram(a, maxMs)
        val sb = spectrogram(b, maxMs)
        val n = min(sa.size, sb.size)
        var ref = Double.NEGATIVE_INFINITY
        for (f in 0 until n) for (k in 0 until 32) ref = max(ref, max(sa[f][k], sb[f][k]))
        val floor = ref - SPECTRO_RANGE_DB
        var sum = 0.0
        var count = 0
        for (f in 0 until n) for (k in 0 until 32) {
            val x = max(sa[f][k], floor)
            val y = max(sb[f][k], floor)
            if (x > floor || y > floor) { sum += abs(x - y); count++ }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
