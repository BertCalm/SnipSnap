package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The strike-shaping measurements shared by the test and the audition generator. Every number is
 * level-independent where it compares layers (ratios, centroids, a gain-removed residual), so the
 * fixed sampling gains do not enter it. Spectra are of the first [WINDOW_SECONDS] of the render.
 */
internal object NacreStrikeMeasure {
    const val WINDOW_SECONDS = .300
    const val HIGH_BAND_HZ = 1000.0

    data class Layer(val highBandDb: Double, val centroidHz: Double, val residualVsHardDb: Double,
        val residualVsHard300msDb: Double, val pitchHz: Double, val peak: Double, val rmsDb: Double)

    fun measure(snip: Snip, hard: Snip): Layer {
        val x = snip.samples
        val h = hard.samples
        val rate = snip.sampleRate
        val window = (WINDOW_SECONDS * rate).toInt()
        val power = powerSpectrum(x, window)
        val binHz = rate.toDouble() / ((power.size - 1) * 2)
        var total = 0.0; var high = 0.0; var weighted = 0.0
        for (k in 1 until power.size) {
            total += power[k]; weighted += power[k] * k * binHz
            if (k * binHz >= HIGH_BAND_HZ) high += power[k]
        }
        val pitch = requireNotNull(Pitch.detect(snip)) { "NACRE strike measurement found no pitch" }
        return Layer(
            highBandDb = 10.0 * log10(maxOf(high, 1e-30) / total),
            centroidHz = weighted / total,
            residualVsHardDb = residual(x, h, 0, x.size),
            residualVsHard300msDb = residual(x, h, 0, window),
            pitchHz = pitch.hz.toDouble(),
            peak = x.maxOf { abs(it) }.toDouble(),
            rmsDb = 20.0 * log10(maxOf(sqrt(x.sumOf { it.toDouble() * it } / x.size), 1e-12)),
        )
    }

    fun cents(hz: Double, reference: Double): Double = 1200.0 * ln(hz / reference) / ln(2.0)

    /** Energy left after removing the best single scalar of Hard, relative to the layer's own energy. */
    fun residual(x: FloatArray, h: FloatArray, from: Int, to: Int): Double {
        var xh = 0.0; var hh = 0.0; var xx = 0.0
        for (i in from until to) { xh += x[i].toDouble() * h[i]; hh += h[i].toDouble() * h[i]; xx += x[i].toDouble() * x[i] }
        val g = xh / hh
        var r = 0.0
        for (i in from until to) { val d = x[i] - g * h[i]; r += d * d }
        return 10.0 * log10(maxOf(r, 1e-30) / xx)
    }

    /** One-sided power spectrum of a Hann-windowed segment, zero-padded to a power of two. */
    private fun powerSpectrum(x: FloatArray, length: Int): DoubleArray {
        var n = 1
        while (n < length) n *= 2
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until length) re[i] = x[i] * (.5 - .5 * cos(2.0 * PI * i / (length - 1)))
        fft(re, im)
        return DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }

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
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }
}
