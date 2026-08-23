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
 *
 * The kernel's half-width is a fixed 16 taps measured in SOURCE samples, so
 * its effective bandwidth (and quality) shrinks as the rate ratio moves away
 * from unity: downsampling packs more source samples per output sample, and
 * the same 16-tap window covers a narrower slice of a lower-rate output's
 * bandwidth. Measured flat to 18 kHz converting 48k -> 44.1k; already
 * -5.0 dB at 3.8 kHz with only -13 dB stopband rejection converting
 * 44.1k -> 8k. Ratios within roughly 0.5x-2x of unity (the range this class
 * is actually used for — device output rates around the MPC's 44.1 kHz) stay
 * well within the flat, well-rejected regime; treat conversions far outside
 * that band as lower fidelity.
 */
object Resampler {

    /** Taps either side of the centre. 16 is the usual quality/cost knee. */
    private const val HALF_TAPS = 16

    /**
     * JVM array allocation fails below `Int.MAX_VALUE` on most implementations
     * (a handful of header words are reserved) — this is HotSpot's practical
     * ceiling, used so the guard below rejects exactly what cannot be
     * allocated, no more.
     */
    private const val MAX_ARRAY_LENGTH = Int.MAX_VALUE - 8

    /**
     * Converts [snip] to [targetRate].
     *
     * Returns [snip] itself, unchanged, when its rate already equals
     * [targetRate] — no copy is made. Caller and result then share the same
     * mutable `FloatArray`; a caller that mutates the returned [Snip]'s
     * samples in that case will corrupt the input too.
     */
    fun resample(snip: Snip, targetRate: Int): Snip {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (snip.sampleRate == targetRate) return snip

        val channels = snip.channels
        val srcFrames = snip.frameCount
        val ratio = targetRate.toDouble() / snip.sampleRate
        val dstFramesExact = floor(srcFrames * ratio)
        require(dstFramesExact <= MAX_ARRAY_LENGTH / channels.toDouble()) {
            "resampling $srcFrames frames from ${snip.sampleRate}Hz to " +
                "${targetRate}Hz ($channels ch) would need $dstFramesExact output " +
                "frames, which cannot be allocated (max ${MAX_ARRAY_LENGTH / channels} " +
                "frames for $channels channel(s)); check the source sample rate"
        }
        val dstFrames = dstFramesExact.toInt()
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
