package com.snipsnap.synth.otis

import com.snipsnap.synth.Dsp
import com.snipsnap.synth.Tide
import com.snipsnap.audio.Snip

internal object OtisOutput {
    const val SETTLING_SECONDS = .12
    /**
     * Raw audition output deliberately keeps source dynamics for the A/B. The house band limit
     * precedes decimation. A causal 20 Hz high-pass removes asymmetric amplifier DC without
     * subtracting the note's mean from preceding silence. The extra settling interval lets
     * that filter return to rest before the final guard; no limiter hides internal growth.
     * AuditionLevel owns comparison matching, separately from this source renderer.
     */
    fun condition(raw: FloatArray): Snip {
        check(raw.isNotEmpty() && raw.all { it.isFinite() }) { "nonfinite or empty prototype render" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val filtered = raw.copyOf(raw.size + (SETTLING_SECONDS * rate).toInt())
        Tide.bandLimit(filtered, rate)
        val out = Dsp.decimate(filtered, Dsp.RATE)
        val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in out.indices) {
            val x = out[i]
            out[i] = x - dc.lp(x)
        }
        for (i in 0 until minOf(6, out.size)) out[i] *= i.toFloat() / 6
        Dsp.fadeTail(out)
        check(out.all { it.isFinite() }) { "nonfinite conditioned prototype render" }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
