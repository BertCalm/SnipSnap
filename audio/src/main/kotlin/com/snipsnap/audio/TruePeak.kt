package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Inter-sample (true) peak: how loud a snip gets *between* its samples,
 * which is what a DAC's reconstruction filter actually plays.
 *
 * A sample peak can read 0.64 while the reconstructed waveform peaks at
 * 0.90 — a sine at a quarter of the sample rate, phased 45° between
 * samples, does exactly that (+3 dB). Every gain stage in this codebase
 * historically metered the sample peak only ([Dsp.levelTo] in `:synth`
 * says so in its own doc), so a render held to a 0.99 sample ceiling can
 * still overshoot full scale on playback and clip in the converter or a
 * downstream codec. This is the meter that sees it.
 *
 * Measured by band-limited interpolation: each inter-sample phase is
 * reconstructed with the same Blackman-windowed sinc family [Resampler]
 * uses, [TAPS] source samples either side. That is an estimate, not a
 * proof — a pathological signal can hide a few tenths of a dB between
 * the measured phases — so ceilings set from it should keep a small
 * margin ([DEFAULT_CEILING] sits 1 dB under full scale for that reason,
 * in line with broadcast true-peak practice).
 *
 * Nothing calls this by default. Wiring it into an export or a level
 * stage changes shipped loudness, which is an audition decision; this
 * object is the measured door that decision can walk through.
 */
object TruePeak {

    /** Oversampling factor for the peak search: phases per sample interval. */
    const val OVERSAMPLE = 4

    /** Sinc kernel half-width, in source samples. */
    private const val TAPS = 8

    /** A sane default ceiling: 1 dB under full scale. */
    const val DEFAULT_CEILING = 0.891f // -1 dBFS

    /**
     * The largest absolute value the reconstructed waveform reaches, per
     * the whole snip (channels judged independently, the worst wins).
     * Non-finite samples read as silence, the rule [Snip.peak] keeps.
     * Silence and empty snips read 0.
     */
    fun of(snip: Snip, oversample: Int = OVERSAMPLE): Float {
        require(oversample in 1..16) { "oversample wants 1..16, got $oversample" }
        if (snip.frameCount == 0) return 0f
        var peak = snip.peak()
        if (oversample == 1 || snip.frameCount < 2) return peak
        val src = snip.samples
        for (ch in 0 until snip.channels) {
            for (f in 0 until snip.frameCount - 1) {
                for (p in 1 until oversample) {
                    val frac = p.toDouble() / oversample
                    var acc = 0.0
                    var norm = 0.0
                    for (n in f - TAPS + 1..f + TAPS) {
                        // Replicate the edge sample rather than padding
                        // with zero: a tone that runs to the file's edge
                        // does not dip there in the source, and zero
                        // padding would under-read exactly the holds and
                        // loop tails that run longest.
                        val clamped = n.coerceIn(0, snip.frameCount - 1)
                        val x = src[clamped * snip.channels + ch]
                        val v = if (x.isFinite()) x.toDouble() else 0.0
                        val w = kernel(f + frac - n)
                        acc += v * w
                        norm += w
                    }
                    val a = abs(if (norm != 0.0) acc / norm else 0.0).toFloat()
                    if (a > peak) peak = a
                }
            }
        }
        return peak
    }

    /**
     * [snip] scaled down — only down — so its true peak sits at
     * [ceiling]. A snip already under the ceiling comes back sample-for-
     * sample (copied, non-finite samples scrubbed to silence like
     * [WavWriter] scrubs them on export); silence is left alone.
     */
    fun limit(snip: Snip, ceiling: Float = DEFAULT_CEILING, oversample: Int = OVERSAMPLE): Snip {
        require(ceiling > 0f && ceiling <= 1f) { "ceiling wants (0, 1], got $ceiling" }
        val peak = of(snip, oversample)
        val out = FloatArray(snip.samples.size)
        if (peak <= ceiling || peak <= 0f) {
            for (i in snip.samples.indices) out[i] = if (snip.samples[i].isFinite()) snip.samples[i] else 0f
            return Snip(out, snip.channels, snip.sampleRate)
        }
        val g = ceiling / peak
        for (i in snip.samples.indices) {
            val v = snip.samples[i] * g
            out[i] = if (v.isFinite()) v else 0f
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Windowed sinc at unity cutoff — the interpolator, DC-normalised at the call site. */
    private fun kernel(x: Double): Double {
        val ax = abs(x)
        if (ax >= TAPS) return 0.0
        val sinc = if (ax < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        val t = (x + TAPS) / (2.0 * TAPS)
        val window = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        return sinc * window
    }
}
