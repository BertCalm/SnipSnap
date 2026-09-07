package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.cos
import kotlin.math.pow

/**
 * WOBBLE — a tempo-synced filter sweep baked onto a captured hit. One
 * resonant low-pass (the same TPT state-variable filter VELVET plays
 * through), its cutoff swept by a cosine that opens at the hit's onset
 * and closes half a division later, the division a note value at the
 * kit's BPM — so the wobble lands on the grid the moment the kit does.
 * RATE snaps to [DIVISIONS]; AMOUNT is the sweep's depth (0 leaves the
 * hit as it is, 1 sweeps the whole [LOW_HZ]..[HIGH_HZ] range). Peak
 * matched; no seed.
 */
object Wobble {

    /** Note divisions of a whole note (four beats), fastest last. */
    val DIVISIONS: List<String> = listOf("1/1", "1/2", "1/4", "1/8", "1/16")
    const val DEFAULT_DIVISION = "1/8"

    const val MIN_BPM = 40f
    const val MAX_BPM = 300f

    /** The sweep's floor and ceiling. */
    const val LOW_HZ = 120f
    const val HIGH_HZ = 6000f

    /** The filter's damping: some ring, never a scream. */
    const val RESONANCE_K = 0.6f

    private const val MAKEUP_MAX = 4f

    /** The division's length in seconds at [bpm]. */
    fun periodSec(bpm: Float, division: String): Float {
        require(bpm in MIN_BPM..MAX_BPM) { "bpm wants $MIN_BPM..$MAX_BPM, got $bpm" }
        val index = DIVISIONS.indexOf(division)
        require(index >= 0) { "unknown division '$division' - one of: ${DIVISIONS.joinToString(", ")}" }
        val fraction = 1f / (1 shl index)
        return 60f / bpm * 4f * fraction
    }

    /** RATE as a 0..1 macro → the division it snaps to. */
    fun divisionFor(rate: Float): String {
        require(rate in 0f..1f) { "rate is 0..1, got $rate" }
        return DIVISIONS[(rate * (DIVISIONS.size - 1)).let { Math.round(it) }.coerceIn(0, DIVISIONS.size - 1)]
    }

    /** [snip] swept at [division] of [bpm], [amount] deep. */
    fun sweep(snip: Snip, bpm: Float, division: String = DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        require(snip.frameCount > 0) { "the source is empty" }
        val period = periodSec(bpm, division)
        if (amount <= 0f) return snip
        val rate = snip.sampleRate
        val channels = snip.channels
        val frames = snip.frameCount
        val out = FloatArray(snip.samples.size)
        val span = (LOW_HZ / HIGH_HZ).toDouble()
        for (ch in 0 until channels) {
            val svf = Dsp.TptSvf(rate)
            for (i in 0 until frames) {
                // Open at the onset, closed half a division later, open again on the beat.
                val closed = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (period * rate))
                val cutoff = (HIGH_HZ * span.pow(amount * closed)).toFloat()
                svf.process(snip.samples[i * channels + ch], cutoff, RESONANCE_K)
                out[i * channels + ch] = svf.low
            }
        }
        val result = Snip(out, channels, rate)
        val inPeak = snip.peak()
        val outPeak = result.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val makeup = (inPeak / outPeak).coerceAtMost(MAKEUP_MAX)
            for (i in out.indices) out[i] *= makeup
        }
        return result
    }
}
