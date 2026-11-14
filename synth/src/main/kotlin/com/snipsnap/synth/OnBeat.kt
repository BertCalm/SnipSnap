package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln

/**
 * ON BEAT (keyed treatment `echoed`): the tempo delay. The characteristic gesture is: the hit plays, then comes back
 * on the grid, again and again, each repeat quieter and darker than the last, and the trail is gone by the end of
 * the bar: a dub throw on a snare, a hat that becomes a rhythm, a held chord that answers itself in time.
 *
 * Nothing in the kit makes that. ECHO (and the DELAY and SLAP chips over it) repeats at 60 to 350 ms, a time in
 * seconds that knows nothing of the kit's tempo, and its tail is capped at 1.1 s. A delay that lands on the beat
 * needs the tempo, which only the keyed family reads. This is the keyed twin in the pedal board brainstorm's idea 2
 * (`docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md`), "ON BEAT".
 *
 * Its own delay line, copied from ECHO's shape (one delay, one low-pass inside the loop, so repeat N has passed the
 * filter N times), rather than a seconds door on ECHO: a dotted eighth at 92 BPM is 489 ms, past ECHO's longest
 * TIME, and ECHO is pinned under the DELAY and SLAP chips, which must not move.
 *
 * - The delay is a division of the kit's bar, on WOBBLE's grid ([Wobble.periodSec]), so repeats land where a gate,
 *   a roll, a pump and a filter sweep land. One more division than the grid's: [DOTTED_EIGHTH], three sixteenths,
 *   the classic tempo delay, whose repeats fall between the beats. The grid starts at the pad's first frame, as
 *   every keyed treatment's does.
 * - AMOUNT is the send, wet level and feedback together: AMOUNT 1 is a full-level first repeat with each later one
 *   [FEEDBACK_MAX] of the one before (about 4.4 dB down, before the filter); a tap (AMOUNT 0.7) is a first repeat at
 *   0.7 and feedback 0.7 x [FEEDBACK_MAX]. AMOUNT 0 gives the pad back.
 * - Each pass goes through a one-pole low-pass at [TONE_HZ], so the trail darkens as it fades, a tape echo's.
 * - The tail is capped at one bar after the pad ends ([MAX_TAIL_BARS]); repeats that have fallen 60 dB end it
 *   sooner. When the cap cuts, the last division of the tail fades on a raised cosine, so a long trail fades out
 *   rather than stopping. A hit becomes up to a bar longer: at 92 BPM a 0.1 s hat comes back about 2.7 s long.
 * - No refusal: a drum is what a delay is most for, and a held pad simply gets its repeats over its own tail.
 *
 * Mono stays mono and a stereo pair repeats together (one delay for every channel; no ping-pong). The output is
 * matched to the pad's peak, since repeats can stack on a held pad. Deterministic, no seed.
 *
 * At its gate (2026-11-14) the owner heard it at 92 BPM on ONSET's snare and closed hat, JANGLE and the choir, beside
 * the DELAY and SLAP chips: the repeats land on the beat, it is clearly different from DELAY, the trail fades
 * naturally, the hat makes a rhythm (its repeats fall 12 to 13 dB each, the snare's about 8, and that was heard as
 * fine), it works on held sounds, and it is for drums and held sounds both. They chose the dotted eighth at a tap as
 * heard (AMOUNT 0.7), so [DEFAULT_DIVISION] stays three sixteenths and the keyed treatment `echoed` (the ON BEAT
 * chip on the KEYED row) carries its own division dial, since the grid's shared default is an eighth.
 */
object OnBeat {

    /** Three sixteenths: the dotted eighth, a division the grid does not have. */
    const val DOTTED_EIGHTH = "3/16"

    /** Every division ON BEAT takes: the grid's, and the dotted eighth. */
    val DIVISIONS: List<String> = Wobble.DIVISIONS + DOTTED_EIGHTH

    /** Three sixteenths: the brainstorm's pick and the owner's tap at the gate. */
    const val DEFAULT_DIVISION = DOTTED_EIGHTH

    /** At AMOUNT 1, each repeat is this much of the one before, before the filter. */
    const val FEEDBACK_MAX = 0.6f

    /** The low-pass every pass goes through: `delayed`'s TONE (0.45 on ECHO's law, about 2.97 kHz), rounded. */
    const val TONE_HZ = 3000f

    /** The most tail ON BEAT adds after the pad, in bars of four beats. */
    const val MAX_TAIL_BARS = 1f

    /** The delay in seconds for [division] at [bpm]: the grid's, or three sixteenths for [DOTTED_EIGHTH]. */
    fun periodSec(bpm: Float, division: String): Float =
        if (division == DOTTED_EIGHTH) 3f * Wobble.periodSec(bpm, "1/16") else Wobble.periodSec(bpm, division)

    /** One bar of four beats at [bpm], seconds. */
    fun barSec(bpm: Float): Float = Wobble.periodSec(bpm, "1/1")

    /** The feedback at [amount]: none at 0, [FEEDBACK_MAX] at 1, linear between. */
    fun feedback(amount: Float): Float = FEEDBACK_MAX * amount.coerceIn(0f, 1f)

    /**
     * The frames ON BEAT adds after a pad: the repeats until they have fallen 60 dB, one bar at most. Repeats fall by
     * the feedback alone here (the filter only makes them fall faster), so this never cuts a repeat that was audible.
     */
    fun tailFrames(rate: Int, bpm: Float, division: String, amount: Float): Int {
        if (amount <= 0f) return 0
        val delay = delayFrames(rate, bpm, division)
        val fb = feedback(amount).toDouble()
        // The first repeat, then one more each time the trail falls by the feedback, to -60 dB under the first.
        val repeats = if (fb <= 1e-6) 1 else 1 + ceil(-6.9078 / ln(fb)).toInt()
        val cap = (MAX_TAIL_BARS * barSec(bpm) * rate).toInt()
        return minOf(repeats.toLong() * delay, cap.toLong()).toInt()
    }

    /** The delay in whole frames. */
    fun delayFrames(rate: Int, bpm: Float, division: String): Int {
        require(division in DIVISIONS) { "unknown division '$division' - one of: ${DIVISIONS.joinToString(", ")}" }
        return Math.round(periodSec(bpm, division) * rate).coerceAtLeast(1)
    }

    /** [snip] echoed on [division] of [bpm], [amount] the send. */
    fun echo(snip: Snip, bpm: Float, division: String = DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        val delay = delayFrames(snip.sampleRate, bpm, division)
        if (amount <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val ch = snip.channels
        val n = snip.frameCount
        val tail = tailFrames(rate, bpm, division, amount)
        val frames = n + tail
        val fb = feedback(amount)
        val capped = tail >= (MAX_TAIL_BARS * barSec(bpm) * rate).toInt()
        // When the bar cuts the trail, its last division fades out; a trail that died on its own needs no fade.
        val fade = if (capped) minOf(delay, tail) else 0

        val out = FloatArray(frames * ch)
        for (c in 0 until ch) {
            // d[] is the echo bus: everything in it has passed the low-pass at least once.
            val d = FloatArray(frames)
            val lp = Dsp.FixedOnePole(TONE_HZ, rate)
            for (f in 0 until frames) {
                val dry = if (f < n) snip.samples[f * ch + c] else 0f
                val back = if (f >= delay) d[f - delay] else 0f
                d[f] = lp.lp(dry + fb * back)
                out[f * ch + c] = dry + amount * back
            }
        }
        if (fade > 0) {
            for (f in frames - fade until frames) {
                val g = (0.5 + 0.5 * cos(PI * (f - (frames - fade)) / fade)).toFloat()
                for (c in 0 until ch) out[f * ch + c] *= g
            }
        }

        // Shape, not loudness: repeats stacked on a held pad can rise over its peak, so match the pad's.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, rate)
    }
}
