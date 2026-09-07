package com.snipsnap.synth

import com.snipsnap.audio.Snip

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
}
