package com.snipsnap.audio

import kotlin.math.floor

/**
 * Sample-rate conversion for bake time.
 *
 * SnipSnap is 44.1 kHz everywhere because that is what the MPC takes natively,
 * but a phone's output is commonly 48 kHz. Conversion happens once, when a
 * block is baked, never inside an audio callback.
 *
 * Placeholder linear interpolation — Task 5 replaces the kernel.
 */
object Resampler {

    fun resample(snip: Snip, targetRate: Int): Snip {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (snip.sampleRate == targetRate) return snip

        val channels = snip.channels
        val srcFrames = snip.frameCount
        val ratio = targetRate.toDouble() / snip.sampleRate
        val dstFrames = floor(srcFrames * ratio).toInt()
        val out = FloatArray(dstFrames * channels)

        for (o in 0 until dstFrames) {
            val srcPos = o / ratio
            val i = floor(srcPos).toInt()
            val frac = (srcPos - i).toFloat()
            for (c in 0 until channels) {
                val a = snip.samples[i * channels + c]
                val b = if (i + 1 < srcFrames) snip.samples[(i + 1) * channels + c] else a
                out[o * channels + c] = a + (b - a) * frac
            }
        }
        return Snip(out, channels, targetRate)
    }
}
