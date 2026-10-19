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
 * follows the new Nyquist, which is what stops the aliasing. Halving or
 * quartering the rate (every oversampled synth render, via Dsp.decimate)
 * reuses one set of weights for every output instead, 3-9x faster per render
 * and identical to the bit.
 *
 * The kernel's half-width is 16 taps measured in SOURCE samples at ratios
 * of 0.5x and above, so conversions in the range this class is mostly used
 * for (device output rates around the MPC's 44.1 kHz, and [Dsp.decimate]'s
 * cascaded 2:1 steps in `:synth`) are bit-for-bit what they have always
 * been. Below 0.5x the window widens in proportion (see [halfTapsFor]):
 * downsampling packs more source samples per output sample, so a fixed
 * 16-tap window covers a narrower slice of the output's bandwidth and the
 * passband droops — measured -2.2 dB at 3 kHz and -5.0 dB at 3.8 kHz
 * converting 44.1k -> 8k before the widening; flat to 3.4 kHz and
 * -1.9 dB at 3.8 kHz (95% of the new Nyquist, where any windowed kernel
 * is already in its transition band) after it, with a 6 kHz alias probe
 * down at 3e-5 of full level (ResamplerTest's far-downsample cases).
 * The widening holds the kernel's support constant in OUTPUT samples,
 * which is the quantity the ear judges; its cost is bounded because the
 * output shrinks by the same factor the kernel grows.
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
    fun resample(snip: Snip, targetRate: Int): Snip = resample(snip, targetRate, powerOfTwoFastPath = true)

    /**
     * [resample], with the power-of-two fast path switchable off so a test can
     * hold it to the general loop, sample for sample.
     */
    internal fun resample(snip: Snip, targetRate: Int, powerOfTwoFastPath: Boolean): Snip {
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

        // Down by a power of two (Dsp.decimate's two 2:1 steps), every output
        // lands exactly on a source sample, so the kernel's weights are the same
        // for every output: compute them once instead of three trig calls per
        // tap per sample. Same weights, summed in the same order, so the result
        // is the general loop's to the bit, several times faster.
        if (powerOfTwoFastPath && isPowerOfTwoDown(snip.sampleRate, targetRate)) {
            decimateByPowerOfTwo(snip, snip.sampleRate / targetRate, cutoff, dstFrames, out)
            return Snip(out, channels, targetRate)
        }

        // The kernel's weights depend on the output position, not the channel,
        // so work them out once per output frame and reuse them for every
        // channel: half the trig on stereo. Same weights, summed in the same
        // order, so every sample comes out to the bit as before.
        val half = halfTapsFor(cutoff)
        val weights = DoubleArray(2 * half)
        for (o in 0 until dstFrames) {
            val srcPos = o / ratio
            val centre = floor(srcPos).toInt()
            val lo = centre - half + 1

            var norm = 0.0
            for (k in weights.indices) {
                val n = lo + k
                if (n < 0 || n >= srcFrames) continue
                val w = kernel(srcPos - n, cutoff, half)
                weights[k] = w
                norm += w
            }
            for (c in 0 until channels) {
                var acc = 0.0
                for (k in weights.indices) {
                    val n = lo + k
                    if (n < 0 || n >= srcFrames) continue
                    acc += snip.samples[n * channels + c] * weights[k]
                }
                out[o * channels + c] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
        }
        return Snip(out, channels, targetRate)
    }

    /**
     * Kernel half-width in source samples for a downsample [cutoff]
     * (the rate ratio, 0..1). At 0.5x and above this is the historical
     * [HALF_TAPS], so every conversion in that regime — including both
     * of `Dsp.decimate`'s 2:1 steps — keeps its exact weights. From
     * 0.5x down to 0.25x the width grows as `HALF_TAPS * 0.5 / cutoff`,
     * holding the support at the 8 output samples 0.5x already had;
     * below 0.25x it grows as `HALF_TAPS / cutoff` (16 output samples),
     * because a 4:1-or-greater conversion is a deliberate offline
     * quality conversion, not a device-rate shim, and can pay for the
     * sharper transition. Both joins are continuous. Capped so an
     * absurd ratio (a corrupt header's 44.1k -> 100 Hz) cannot make
     * one output sample cost thousands of taps; at the cap the
     * conversion is merely back to the old droop, never wrong.
     */
    internal fun halfTapsFor(cutoff: Double): Int {
        if (cutoff >= 0.5) return HALF_TAPS
        return (if (cutoff >= 0.25) HALF_TAPS * 0.5 / cutoff else HALF_TAPS / cutoff).toInt().coerceIn(HALF_TAPS, MAX_HALF_TAPS)
    }

    /** Widest kernel the far-ratio widening may reach, in source taps either side. */
    private const val MAX_HALF_TAPS = 512

    /** Whether [from] -> [to] is a whole power-of-two step down, the case [resample]'s fast path takes. */
    internal fun isPowerOfTwoDown(from: Int, to: Int): Boolean {
        if (to <= 0 || from <= to || from % to != 0) return false
        val step = from / to
        return step and (step - 1) == 0
    }

    /**
     * [resample]'s loop for a whole power-of-two [step] down. Output `o` sits
     * at source sample `o * step` exactly (1 / step is exact in a double), so
     * the tap at `lo + k` is always `half - 1 - k` source samples away:
     * the same [kernel] argument, hence the same weight, as the general loop
     * computes each time. Edge taps outside the buffer are skipped and the
     * weights renormalised exactly as there.
     */
    private fun decimateByPowerOfTwo(snip: Snip, step: Int, cutoff: Double, dstFrames: Int, out: FloatArray) {
        val channels = snip.channels
        val srcFrames = snip.frameCount
        val samples = snip.samples
        val half = halfTapsFor(cutoff)
        val weights = DoubleArray(2 * half) { k -> kernel((half - 1 - k).toDouble(), cutoff, half) }
        for (o in 0 until dstFrames) {
            val lo = o * step - half + 1
            for (c in 0 until channels) {
                var acc = 0.0
                var norm = 0.0
                for (k in weights.indices) {
                    val n = lo + k
                    if (n < 0 || n >= srcFrames) continue
                    val w = weights[k]
                    acc += samples[n * channels + c] * w
                    norm += w
                }
                out[o * channels + c] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
        }
    }

    /**
     * Sinc at [cutoff] of Nyquist, under a Blackman window.
     *
     * Normalising by the summed weights at the call site keeps DC gain at unity
     * even where the window is truncated at the buffer edges.
     */
    private fun kernel(x: Double, cutoff: Double, half: Int = HALF_TAPS): Double {
        val ax = abs(x)
        if (ax >= half) return 0.0
        val sinc = if (ax < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
        val t = (x + half) / (2.0 * half)
        val window = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        return sinc * window
    }
}
