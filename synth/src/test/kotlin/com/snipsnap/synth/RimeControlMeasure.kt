package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.*

/** Phase-independent evidence: short-time normalized power during excitation. */
internal object RimeControlMeasure {
    private fun power(samples: FloatArray, fromSeconds: Double, seconds: Double, size: Int = 16384): DoubleArray {
        val start = (fromSeconds * Dsp.RATE).roundToInt()
        val stop = min(samples.size, ((fromSeconds + seconds) * Dsp.RATE).roundToInt())
        val sum = DoubleArray(size / 2)
        var at = start
        while (at + size <= stop) {
            val re = FloatArray(size) { i ->
                if (at + i < stop) (samples[at + i] * (.5 - .5 * cos(2 * PI * i / size))).toFloat() else 0f
            }
            val im = FloatArray(size); Fft.forward(re, im)
            for (i in 1 until sum.size) sum[i] += re[i].toDouble().pow(2) + im[i].toDouble().pow(2)
            at += size / 2
        }
        val energy = sum.sum().coerceAtLeast(1e-30)
        return DoubleArray(sum.size) { sum[it] / energy }
    }

    fun distance(a: FloatArray, b: FloatArray, gateSeconds: Double): Double {
        val pa = power(a, .06, max(.1, gateSeconds - .06))
        val pb = power(b, .06, max(.1, gateSeconds - .06))
        return sqrt(pa.indices.sumOf { (sqrt(pa[it]) - sqrt(pb[it])).pow(2) })
    }

    fun fineShare(samples: FloatArray, hz: Double, fromSeconds: Double): Double {
        val p = power(samples, fromSeconds, .20, 8192)
        return p.indices.filter { it * Dsp.RATE.toDouble() / 8192 >= 1.5 * hz }.sumOf { p[it] }
    }
}
