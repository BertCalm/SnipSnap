package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurements shared by the LIBRATION and CHITIN candidate-voice tests. Nothing here is heard. */
internal object CandidateVoiceMeasures {
    /** ETCH's bar for distinct voices: RMS difference of level-matched third-octave shapes, dB. */
    const val DISTINCT_DB = 3.7

    /** Level-matched third-octave shape (24 bands, 40 Hz to 10 kHz) over the whole render, dB. */
    fun bands(x: FloatArray): DoubleArray {
        val n = 8192
        val e = DoubleArray(24)
        var total = 0.0
        var start = 0
        while (start + n <= x.size) {
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (j in 0 until n) re[j] = (x[start + j] * (.5 - .5 * cos(2 * PI * j / (n - 1)))).toFloat()
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val hz = bin * Dsp.RATE.toDouble() / n
                if (hz < 40.0 || hz >= 10_000.0) continue
                val p = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                e[(3.0 * ln(hz / 40.0) / ln(2.0)).toInt().coerceIn(0, 23)] += p
                total += p
            }
            start += n / 2
        }
        return DoubleArray(24) { 10 * log10(e[it] / (total + 1e-30) + 1e-12) }
    }

    fun distance(a: DoubleArray, b: DoubleArray): Double = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) } / a.size)

    /** Hann-windowed amplitude of the component at [hz]. */
    fun component(x: FloatArray, hz: Double, fromSeconds: Double, seconds: Double): Double {
        val a = (fromSeconds * Dsp.RATE).toInt()
        val n = minOf((seconds * Dsp.RATE).toInt(), x.size - a)
        if (n <= 0) return 0.0
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val v = x[a + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))
            re += v * cos(2 * PI * hz * i / Dsp.RATE)
            im += v * sin(2 * PI * hz * i / Dsp.RATE)
        }
        return 4 * sqrt(re * re + im * im) / n
    }

    /** Where the root actually sits: cents of the strongest component within 60 cents of [hz]. */
    fun rootCents(x: FloatArray, hz: Double, fromSeconds: Double = .1, seconds: Double = .6): Double {
        var best = 0.0
        var bestAmplitude = -1.0
        for (c in -60..60) {
            val amplitude = component(x, hz * 2.0.pow(c / 1200.0), fromSeconds, seconds)
            if (amplitude > bestAmplitude) { bestAmplitude = amplitude; best = c.toDouble() }
        }
        return best
    }

    /** The root must be a real, strong component, not the edge of something else. */
    fun rootShare(x: FloatArray, hz: Double, fromSeconds: Double = .1, seconds: Double = .6): Double {
        val a = (fromSeconds * Dsp.RATE).toInt()
        val n = minOf((seconds * Dsp.RATE).toInt(), x.size - a)
        val rms = sqrt((0 until n).sumOf { x[a + it].toDouble() * x[a + it] } / n)
        return component(x, hz, fromSeconds, seconds) / (rms * sqrt(2.0) + 1e-30)
    }

    fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    fun hash(lines: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
}
