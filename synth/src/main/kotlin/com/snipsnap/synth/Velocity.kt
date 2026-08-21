package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE

/**
 * Velocity variants: softer renderings of a hit for the pad's lower
 * velocity zones.
 *
 * Physics does the design here: a softer strike excites fewer high
 * partials, so "soft" is a darker version of the same sound — not merely a
 * quieter one (the hardware already handles quieter). One low-pass, depth
 * by zone, and a phone-made kit gains ghost notes that sound like ghost
 * notes. Works on synthesized and captured hits alike.
 */
object Velocity {

    /**
     * A darker rendering of [snip]; [amount] 0 = untouched, 1 = softest.
     * Peak-matched — timbre change only, the MPC's velocity curve owns level.
     */
    fun soften(snip: Snip, amount: Float): Snip {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val cutoffHz = Dsp.expMap(1f - a, 700f, 14_000f)
        var inPeak = 0f
        for (v in snip.samples) { val x = if (v < 0) -v else v; if (x > inPeak) inPeak = x }

        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val lp = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < snip.samples.size) {
                out[i] = lp.lp(snip.samples[i], cutoffHz)
                i += snip.channels
            }
        }

        var outPeak = 0f
        for (v in out) { val x = if (v < 0) -v else v; if (x > outPeak) outPeak = x }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /**
     * [count] soft variants of [snip], softest first — ready for
     * `ArrangedPad.softVariants`. Depths are spaced so each zone is an
     * audible step: with two variants, soft ≈ closed-fist, mid ≈ relaxed.
     */
    fun variants(snip: Snip, count: Int = 2): List<Snip> {
        require(count in 1..3) { "1..3 soft variants (4 zones total), got $count" }
        return List(count) { i -> soften(snip, (count - i).toFloat() / (count + 1)) }
    }
}
