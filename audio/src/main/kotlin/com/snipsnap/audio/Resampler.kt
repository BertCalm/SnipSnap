package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sample-rate conversion for bake time.
 *
 * SnipSnap is 44.1 kHz everywhere because that is what the MPC takes natively,
 * but a phone's output is commonly 48 kHz. Conversion happens once, when a
 * block is baked, never inside an audio callback — so this can afford a real
 * band-limited kernel instead of the linear interpolation that would dull the
 * top octave going up and fold aliases back down coming down.
 *
 * Windowed sinc, evaluated per output sample. When downsampling the cutoff
 * follows the new Nyquist, which is what stops the aliasing.
 */
object Resampler {

    /** Taps either side of the centre. 16 is the usual quality/cost knee. */
    private const val HALF_TAPS = 16

    fun resample(snip: Snip, targetRate: Int): Snip {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (snip.sampleRate == targetRate) return snip

        val channels = snip.channels
        val srcFrames = snip.frameCount
        val ratio = targetRate.toDouble() / snip.sampleRate
        val dstFrames = floor(srcFrames * ratio).toInt()
        val out = FloatArray(dstFrames * channels)

        // Going down, pull the passband to the destination's Nyquist so nothing
        // above it survives to fold back. Going up, the source is already band-
        // limited and the kernel just interpolates.
        val cutoff = if (ratio < 1.0) ratio else 1.0

        for (o in 0 until dstFrames) {
            val srcPos = o / ratio
            val centre = floor(srcPos).toInt()
            val lo = centre - HALF_TAPS + 1
            val hi = centre + HALF_TAPS

            for (c in 0 until channels) {
                var acc = 0.0
                var norm = 0.0
                for (n in lo..hi) {
                    if (n < 0 || n >= srcFrames) continue
                    val w = kernel(srcPos - n, cutoff)
                    acc += snip.samples[n * channels + c] * w
                    norm += w
                }
                out[o * channels + c] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
        }
        return Snip(out, channels, targetRate)
    }

    /**
     * Sinc at [cutoff] of Nyquist, under a Blackman window.
     *
     * Normalising by the summed weights at the call site keeps DC gain at unity
     * even where the window is truncated at the buffer edges.
     */
    private fun kernel(x: Double, cutoff: Double): Double {
        val ax = abs(x)
        if (ax >= HALF_TAPS) return 0.0
        val sinc = if (ax < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
        val t = (x + HALF_TAPS) / (2.0 * HALF_TAPS)
        val window = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        return sinc * window
    }
}
