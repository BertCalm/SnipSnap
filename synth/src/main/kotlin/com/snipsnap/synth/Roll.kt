package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * ROLL — the hit's own head, struck again on the grid. The sampler's
 * signature edit: hold the pad, and one hit becomes a run of them at a
 * note value, each a little quieter than the last.
 *
 * A keyed treatment rather than a rack section, because it reads the kit's
 * tempo and the rack reads nothing but the sound. It shares WOBBLE's grid
 * ([Wobble.DIVISIONS], [Wobble.periodSec]) so the two land on the same
 * beats, and the kit's own dial ([Keyed.Dials.division]) chooses it.
 *
 * AMOUNT is how much of the hit the roll replaces: 0 leaves it alone, 1
 * rolls the whole thing. Length is unchanged — a roll fills the hit, it
 * does not extend it. Peak matched, deterministic, no seed.
 */
object Roll {

    /** Each repeat against the one before it. */
    const val DECAY = 0.82f

    /** A hit must hold at least this many divisions to be worth rolling. */
    const val MIN_DIVISIONS = 2

    /**
     * Why [snip] cannot be rolled at [division] and [bpm], in words, or null
     * when it can — asked before anything is touched.
     */
    fun refusal(snip: Snip, bpm: Float, division: String): String? {
        val period = Wobble.periodSec(bpm, division)
        val need = period * MIN_DIVISIONS
        if (snip.durationSeconds < need) {
            return "the hit is shorter than $MIN_DIVISIONS divisions of $division at ${Math.round(bpm)} BPM " +
                "- it has %.2f s and needs %.2f s".format(java.util.Locale.ROOT, snip.durationSeconds, need)
        }
        return null
    }

    /** [snip] rolled at [division] of [bpm], [amount] of the way. */
    fun roll(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        refusal(snip, bpm, division)?.let { throw IllegalArgumentException(it) }

        val period = (Wobble.periodSec(bpm, division) * snip.sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(snip.samples.size)
        var gain = 1f
        var start = 0
        while (start < snip.frameCount) {
            val n = minOf(period, snip.frameCount - start)
            for (f in 0 until n) {
                for (ch in 0 until snip.channels) {
                    val src = snip.samples[f * snip.channels + ch]
                    val dry = snip.samples[(start + f) * snip.channels + ch]
                    // Each repeat is the head again, faded toward the dry by AMOUNT.
                    out[(start + f) * snip.channels + ch] = dry * (1f - amount) + src * gain * amount
                }
            }
            gain *= DECAY
            start += period
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
