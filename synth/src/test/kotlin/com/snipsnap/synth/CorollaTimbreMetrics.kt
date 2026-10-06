package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Fixed-pitch, fixed-duration spectral envelopes. Each frame has unit power, so
 * gain, a gradual overall decay change and waveform phase cannot satisfy a timbre gate. */
internal object CorollaTimbreMetrics {
    private const val FRAME = 4096
    private const val HOP = FRAME / 2
    private val edges = doubleArrayOf(0.0, .75, 1.22, 1.8, 2.3, 3.15,
        4.2, 5.15, 6.9, 9.2, 13.0, 20.0, Double.POSITIVE_INFINITY)

    data class Frame(val bands: DoubleArray, val centroidHz: Double, val rms: Double) {
        val rootShare get() = bands[1]
        val upperShare get() = bands.drop(3).sum()
    }

    fun frames(samples: FloatArray, hz: Double, fromSeconds: Double,
        untilSeconds: Double, rate: Int = Dsp.RATE): List<Frame> {
        val from = (fromSeconds * rate).toInt()
        val until = minOf(samples.size, (untilSeconds * rate).toInt())
        return (from..until - FRAME step HOP).map { start ->
            val real = FloatArray(FRAME)
            val imaginary = FloatArray(FRAME)
            var rawPower = 0.0
            for (i in 0 until FRAME) {
                val value = samples[start + i]
                rawPower += value.toDouble() * value
                real[i] = (value * (.5 - .5 * cos(2 * PI * i / (FRAME - 1)))).toFloat()
            }
            Fft.forward(real, imaginary)
            val bands = DoubleArray(edges.size - 1)
            var total = 0.0
            var weighted = 0.0
            for (bin in 1..FRAME / 2) {
                val frequency = bin * rate.toDouble() / FRAME
                val ratio = frequency / hz
                var band = 0
                while (ratio >= edges[band + 1]) band++
                val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                bands[band] += power
                total += power
                weighted += power * frequency
            }
            if (total > 1e-24) for (band in bands.indices) bands[band] /= total
            Frame(bands, if (total > 1e-24) weighted / total else 0.0,
                kotlin.math.sqrt(rawPower / FRAME))
        }
    }

    /** Fraction of frame energy that must move to make the two band envelopes equal. */
    fun distance(a: List<Frame>, b: List<Frame>): Double {
        require(a.isNotEmpty() && a.size == b.size)
        return a.indices.sumOf { frame ->
            a[frame].bands.indices.sumOf { band -> abs(a[frame].bands[band] - b[frame].bands[band]) } / 2
        } / a.size
    }

    fun centroid(frames: List<Frame>) = frames.map { it.centroidHz }.average()
    fun rootShare(frames: List<Frame>) = frames.map { it.rootShare }.average()
    fun upperShare(frames: List<Frame>) = frames.map { it.upperShare }.average()
}
