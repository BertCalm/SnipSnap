package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Spectral measures for NACRE's sources: centroid, third-octave balance, pitch and spectral distance. */
internal object NacreVoicesMeasure {
    private const val RATE = Dsp.RATE

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            for (start in 0 until n step len) for (k in 0 until len / 2) {
                val c = cos(ang * k); val s = sin(ang * k)
                val a = start + k; val b = a + len / 2
                val xr = re[b] * c - im[b] * s; val xi = re[b] * s + im[b] * c
                re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
            }
            len = len shl 1
        }
    }

    /** Average Hann-windowed power spectrum over [fromSec, toSec), in 8192-sample frames. */
    fun power(x: FloatArray, fromSec: Double, toSec: Double, size: Int = 8192): DoubleArray {
        val from = (fromSec * RATE).roundToInt()
        val to = minOf(x.size, (toSec * RATE).roundToInt())
        val acc = DoubleArray(size / 2)
        var frames = 0
        var start = from
        while (start + size <= to) {
            val re = DoubleArray(size) { x[start + it] * (.5 - .5 * cos(2.0 * PI * it / (size - 1))) }
            val im = DoubleArray(size)
            fft(re, im)
            for (k in acc.indices) acc[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            start += size / 2
        }
        require(frames > 0) { "window too short" }
        for (k in acc.indices) acc[k] /= frames
        return acc
    }

    fun centroidHz(x: FloatArray, fromSec: Double, toSec: Double): Double {
        val p = power(x, fromSec, toSec)
        var num = 0.0; var den = 0.0
        for (k in 1 until p.size) { val hz = k.toDouble() * RATE / (2 * p.size); num += hz * p[k]; den += p[k] }
        return if (den == 0.0) 0.0 else num / den
    }

    /** Third-octave band power fractions from 40 Hz to 16 kHz, summing to 1. */
    fun bands(x: FloatArray, fromSec: Double, toSec: Double): DoubleArray {
        val p = power(x, fromSec, toSec)
        val edges = generateSequence(40.0) { it * 2.0.pow(1.0 / 3) }.takeWhile { it < 16000.0 }.toList()
        val out = DoubleArray(edges.size)
        for (k in 1 until p.size) {
            val hz = k.toDouble() * RATE / (2 * p.size)
            val band = edges.indexOfLast { it <= hz }
            if (band >= 0 && hz < 16000.0) out[band] += p[k]
        }
        val total = out.sum()
        return DoubleArray(out.size) { if (total == 0.0) 0.0 else out[it] / total }
    }

    /** Total-variation distance between two third-octave balances: 0 identical, 1 disjoint. */
    fun distance(a: FloatArray, b: FloatArray, fromSec: Double = 0.0, toSec: Double = 2.0): Double {
        val p = bands(a, fromSec, toSec); val q = bands(b, fromSec, toSec)
        return .5 * p.indices.sumOf { abs(p[it] - q[it]) }
    }

    /**
     * Timbre distance: semitone-band power over the ring-out, 0.5 to 3 s (past the strum, where DUET and TOUCH timing dominate), each spectrum in dB against its
     * own strongest band and floored at [floorDb], then the mean absolute difference over 55 Hz to
     * 8 kHz. The floor keeps near-empty bands from counting; level and strum timing barely move it,
     * while a different overtone structure (inharmonic partials, missing or added overtones) does.
     */
    fun toneDistance(a: FloatArray, b: FloatArray, fromSec: Double = .5, toSec: Double = 3.0, floorDb: Double = -45.0): Double {
        fun profile(x: FloatArray): DoubleArray {
            val p = power(x, fromSec, toSec, 16384)
            val edges = generateSequence(55.0 * 2.0.pow(-1.0 / 24)) { it * 2.0.pow(1.0 / 12) }.takeWhile { it < 8000.0 }.toList()
            val bandsOut = DoubleArray(edges.size)
            for (k in 1 until p.size) {
                val hz = k.toDouble() * RATE / (2 * p.size)
                val band = edges.indexOfLast { it <= hz }
                if (band >= 0 && hz < 8000.0) bandsOut[band] += p[k]
            }
            val strongest = bandsOut.max()
            return DoubleArray(bandsOut.size) { maxOf(floorDb, 10.0 * log10(maxOf(bandsOut[it], 1e-300) / strongest)) }
        }
        val pa = profile(a); val pb = profile(b)
        return pa.indices.sumOf { abs(pa[it] - pb[it]) } / pa.size
    }

    /** Power above [multiple] times [f0], in dB against the total: how bright the strike is. */
    fun upperShareDb(x: FloatArray, f0: Double, fromSec: Double, toSec: Double, multiple: Double = 4.0): Double {
        val p = power(x, fromSec, toSec)
        var upper = 0.0; var total = 0.0
        for (k in 1 until p.size) {
            val hz = k.toDouble() * RATE / (2 * p.size)
            total += p[k]; if (hz >= multiple * f0) upper += p[k]
        }
        return 10.0 * log10(maxOf(upper, 1e-300) / total)
    }

    /** The strongest component within ±[cents] of [expected], by a fine DFT scan of a Hann window. */
    fun pitchHz(x: FloatArray, fromSec: Double, toSec: Double, expected: Double, cents: Double = 60.0): Double {
        val from = (fromSec * RATE).roundToInt()
        val n = minOf(x.size, (toSec * RATE).roundToInt()) - from
        val w = DoubleArray(n) { x[from + it] * (.5 - .5 * cos(2.0 * PI * it / (n - 1))) }
        fun mag(hz: Double): Double {
            val step = 2.0 * PI * hz / RATE
            var re = 0.0; var im = 0.0
            val c = cos(step); val s = sin(step)
            var cr = 1.0; var ci = 0.0
            for (i in 0 until n) {
                re += w[i] * cr; im -= w[i] * ci
                val nr = cr * c - ci * s; ci = cr * s + ci * c; cr = nr
            }
            return sqrt(re * re + im * im)
        }
        var bestC = -cents; var best = -1.0
        var c = -cents
        while (c <= cents) { val m = mag(expected * 2.0.pow(c / 1200)); if (m > best) { best = m; bestC = c }; c += .5 }
        // Parabolic refinement on the log magnitude around the best half-cent step.
        val l = ln(mag(expected * 2.0.pow((bestC - .5) / 1200)) + 1e-30)
        val m0 = ln(best + 1e-30)
        val r = ln(mag(expected * 2.0.pow((bestC + .5) / 1200)) + 1e-30)
        val denom = l - 2 * m0 + r
        val shift = if (denom == 0.0) 0.0 else .5 * (l - r) / denom * .5
        return expected * 2.0.pow((bestC + shift) / 1200)
    }

    fun cents(hz: Double, expected: Double): Double = 1200.0 * ln(hz / expected) / ln(2.0)

    fun rms(x: FloatArray, fromSec: Double, toSec: Double): Double {
        val from = (fromSec * RATE).roundToInt(); val to = minOf(x.size, (toSec * RATE).roundToInt())
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / (to - from))
    }

    fun db(v: Double): Double = 20.0 * log10(v)
}
