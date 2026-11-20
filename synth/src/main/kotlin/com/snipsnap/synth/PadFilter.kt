package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow

/**
 * The pad shape's filter half, rendered: a resonant low-pass through the
 * same TPT state-variable filter VELVET plays through, for previews of
 * a shaped pad (the hardware renders the real one from metadata). [k] is
 * the filter's damping — 2 = no resonance, smaller rings. The output's
 * peak is held at the input's, so a resonant peak never reads as loudness.
 */
object PadFilter {

    fun lowpass(snip: Snip, cutoffHz: Float, k: Float): Snip {
        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val svf = Dsp.TptSvf(snip.sampleRate)
            var i = ch
            while (i < out.size) {
                svf.process(snip.samples[i], cutoffHz, k)
                out[i] = svf.low
                i += snip.channels
            }
        }
        var inPeak = 0f
        var outPeak = 0f
        for (v in snip.samples) inPeak = maxOf(inPeak, if (v < 0) -v else v)
        for (v in out) outPeak = maxOf(outPeak, if (v < 0) -v else v)
        if (outPeak > inPeak && outPeak > 0f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] *= g
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /**
     * [snip] with frames [from] until [end] (clamped to its length) run through a resonant low-pass ([Dsp.TptSvf], one per channel)
     * whose cutoff falls exponentially from [openHz] at [from] to [closeHz] at [end], damping [k] as in [lowpass]. Every other
     * frame is [snip]'s own, bit for bit, and the length is unchanged. The filter first runs over the [warmup] frames before
     * [from] at [openHz], so it starts the bar holding the music's state and does not click. Unlike [lowpass] the level is not
     * matched: a sweep that shuts the top of the spectrum is meant to get quieter. This is what SONG's CLOSE transition plays.
     */
    fun sweep(snip: Snip, from: Int, end: Int, openHz: Float, closeHz: Float, k: Float, warmup: Int): Snip {
        val last = minOf(end, snip.frameCount)
        if (from >= last) return snip
        val ch = snip.channels
        val out = snip.samples.copyOf()
        for (c in 0 until ch) {
            val svf = Dsp.TptSvf(snip.sampleRate)
            for (n in maxOf(0, from - warmup) until from) svf.process(snip.samples[n * ch + c], openHz, k)
            for (n in from until last) {
                val t = (n - from).toFloat() / (last - from)
                svf.process(snip.samples[n * ch + c], openHz * (closeHz / openHz).pow(t), k)
                out[n * ch + c] = svf.low
            }
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
