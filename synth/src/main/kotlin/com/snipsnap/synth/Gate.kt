package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * GATE — the hit chopped on the grid. A square envelope at a note value,
 * open for the first half of each division and closed for the second, with
 * a short fade on both edges so the chop is rhythm rather than a row of
 * clicks.
 *
 * Keyed rather than racked, for the same reason as ROLL: it reads the
 * kit's tempo. Shares WOBBLE's grid and the kit's own division dial.
 *
 * AMOUNT is depth, not speed — 0 leaves the gate open, 1 closes it all the
 * way. Length unchanged, peak matched, deterministic.
 */
object Gate {

    /** The fade on each edge: long enough to not click, short enough to still chop. */
    const val EDGE_SEC = 0.003f

    /** How much of each division the gate is open for. */
    const val DUTY = 0.5f

    /** A hit must hold at least this many divisions to be worth gating. */
    const val MIN_DIVISIONS = 2

    /** Why [snip] cannot be gated at [division] and [bpm], in words, or null when it can. */
    fun refusal(snip: Snip, bpm: Float, division: String): String? {
        val period = Wobble.periodSec(bpm, division)
        val need = period * MIN_DIVISIONS
        if (snip.durationSeconds < need) {
            return "the hit is shorter than $MIN_DIVISIONS divisions of $division at ${Math.round(bpm)} BPM " +
                "- it has %.2f s and needs %.2f s".format(java.util.Locale.ROOT, snip.durationSeconds, need)
        }
        return null
    }

    /** [snip] chopped at [division] of [bpm], [amount] deep. */
    fun chop(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        refusal(snip, bpm, division)?.let { throw IllegalArgumentException(it) }

        val period = (Wobble.periodSec(bpm, division) * snip.sampleRate).toInt().coerceAtLeast(2)
        val open = (period * DUTY).toInt().coerceAtLeast(1)
        val edge = (EDGE_SEC * snip.sampleRate).toInt().coerceIn(1, open / 2)

        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            val phase = f % period
            // A trapezoid, not a square: the ramps are what keep it from clicking.
            val gate = when {
                // The hit's own attack: there is nothing before frame 0 to be
                // discontinuous with, so the first division opens already open.
                f < edge -> 1f
                phase < edge -> phase.toFloat() / edge
                phase < open - edge -> 1f
                phase < open -> (open - phase).toFloat() / edge
                else -> 0f
            }
            val g = 1f - amount * (1f - gate)
            for (ch in 0 until snip.channels) {
                out[f * snip.channels + ch] = snip.samples[f * snip.channels + ch] * g
            }
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
