package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * PUMP (keyed treatment `pumped`): the sidechain pump. The characteristic gesture is: the sound plays untouched for
 * one division, then on every beat its level drops hard and breathes back up before the next, the way a held chord
 * ducks under a club record's kick.
 *
 * Nothing in the kit makes that. GATE ([Gate.chop]) chops on the same grid with a square: open, then shut, then open
 * again at once. TREMOLO moves the level with a sine whose rise and fall are mirror images. A pump is lopsided: the
 * fall takes [ATTACK_SEC], the rise most of the division, and the rise is a compressor's release, steady in decibels,
 * so the sound swells back rather than snapping. This is the pedal board brainstorm's idea 7
 * (`docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md`), parked behind the keyed row's two free chips.
 *
 * Keyed rather than racked, for GATE's reason: it reads the kit's tempo, and shares WOBBLE's grid ([Wobble.periodSec]),
 * so a pump, a gate, a roll and a filter sweep land on the same beats. Its own default division is [DEFAULT_DIVISION],
 * a quarter, where GATE's is an eighth: a pump on every eighth is a flutter.
 *
 * The envelope, one gain for every channel, so mono stays mono:
 * - The first division is untouched: the hit is the kick the pump answers, so nothing ducks it.
 * - On each later beat the gain falls along a raised cosine over [ATTACK_SEC] to the dip, then rises back to 1 over
 *   [RELEASE_FRACTION] of the division, along a straight line in decibels, and holds at 1 until the next beat.
 * - AMOUNT is the depth, as GATE's: the dip's gain is 1 - AMOUNT (1 - [FLOOR_DB] as a gain), linear in the gain, so
 *   AMOUNT 0.7 dips about 9 dB and AMOUNT 1 [FLOOR_DB]. The floor is written into the law: AMOUNT 1 never silences.
 *
 * A pad shorter than [MIN_DIVISIONS] divisions is refused, in words, as GATE's is: it would hear one dip at most, at
 * its very end. Every drum one-shot is out (two quarters at 92 BPM is 1.3 s); the brainstorm puts it on long pads.
 * Length unchanged, peak matched (the first division is untouched, so the peak is almost always where it was),
 * deterministic, no seed.
 *
 * Chip-less until its listening gate (the brainstorm's chip is PUMP on the KEYED row).
 */
object Pump {

    /** A quarter: where a club record's kick lands. */
    const val DEFAULT_DIVISION = "1/4"

    /** The fall into each dip: fast enough to read as a duck, slow enough not to click. */
    const val ATTACK_SEC = 0.005f

    /** How much of each division the rise back takes; the rest holds at full level before the next dip. */
    const val RELEASE_FRACTION = 0.8f

    /** The dip at AMOUNT 1, decibels under full level. */
    const val FLOOR_DB = -24f

    /** A pad must hold at least this many divisions to be pumped, as GATE's. */
    const val MIN_DIVISIONS = 2

    /** Why [snip] cannot be pumped at [division] and [bpm], in words, or null when it can. */
    fun refusal(snip: Snip, bpm: Float, division: String): String? {
        val period = Wobble.periodSec(bpm, division)
        val need = period * MIN_DIVISIONS
        if (snip.durationSeconds < need) {
            return "the sound is shorter than $MIN_DIVISIONS divisions of $division at ${Math.round(bpm)} BPM " +
                "- it has %.2f s and needs %.2f s".format(java.util.Locale.ROOT, snip.durationSeconds, need)
        }
        return null
    }

    /** The dip's gain at [amount]: 1 at 0, the floor at 1, linear in the gain between. */
    fun dipGain(amount: Float): Float {
        val floor = 10f.pow(FLOOR_DB / 20f)
        return 1f - amount.coerceIn(0f, 1f) * (1f - floor)
    }

    /** The gain for each of [frames] frames at [rate]: the envelope [pump] multiplies every channel by. */
    fun envelope(frames: Int, rate: Int, bpm: Float, division: String, amount: Float): FloatArray {
        val gains = FloatArray(frames) { 1f }
        if (amount <= 0f) return gains
        val period = (Wobble.periodSec(bpm, division) * rate).toInt().coerceAtLeast(2)
        val attack = (ATTACK_SEC * rate).toInt().coerceIn(1, period / 4)
        val release = ((RELEASE_FRACTION * period).toInt() - attack).coerceAtLeast(1)
        val dipDb = 20.0 * log10(dipGain(amount).toDouble())
        val dip = dipGain(amount)
        for (f in period until frames) {
            val phase = f % period
            gains[f] = when {
                // The fall: a raised cosine from full level to the dip, so the gain's slope is zero at both ends.
                phase < attack -> 1f - (1f - dip) * 0.5f * (1f - cos(PI * phase / attack).toFloat())
                // The rise: the dip's decibels shrinking in a straight line to none, a compressor's release.
                phase < attack + release -> 10.0.pow(dipDb * (1.0 - (phase - attack).toDouble() / release) / 20.0).toFloat()
                else -> 1f
            }
        }
        return gains
    }

    /** [snip] pumped at [division] of [bpm], [amount] deep. */
    fun pump(snip: Snip, bpm: Float, division: String = DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        refusal(snip, bpm, division)?.let { throw IllegalArgumentException(it) }

        val gains = envelope(snip.frameCount, snip.sampleRate, bpm, division, amount)
        val ch = snip.channels
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            for (c in 0 until ch) out[f * ch + c] = snip.samples[f * ch + c] * gains[f]
        }

        // Shape, not loudness: a pad whose peak sat in a dip comes back to the level it had.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f && outPeak < inPeak) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
