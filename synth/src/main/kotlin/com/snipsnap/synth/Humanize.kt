package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A player's hand on a held note: the slow, never quite steady movement ARCO's bow and BORE's breath put under a sustain.
 *
 * The owner judged every held ARCO and BORE clip on 2026-11-20 with one note: "Holds are unnatural, they need more humanization".
 * Measured on those clips, a held note's level moved by 0.03 to 0.3 dB over the whole hold, BORE's vibrato was one sine (every
 * swing the same depth to 1 or 2 percent) and ARCO's varied by 9 to 10 percent on a two second pattern. What a player does that
 * those notes did not: the pressure drifts, the pitch leans a few cents and comes back, no two swings of the vibrato are alike,
 * and the note is found from just under its pitch.
 *
 * Everything here is a [Wander]: a few sines at whole numbers of cycles per [Wander.period], their phases drawn from a seed. So a
 * render is a pure function of its macros (the seed is the voice and the note), and a movement built from them repeats exactly
 * every [Wander.period] seconds: a LOOP or a held key gives its own length as the period and its wrap closes as it did, and a
 * one-shot gives a period longer than its bow or breath lasts, so nothing in it comes round twice.
 *
 * The amount is one number. [OFF] is the engine as it was, sample for sample (the frozen tests hold that); [MODEST] is what
 * ships; [STRONGER] is the audition's second strength. It is not a macro: neither engine had a HUMANIZE or MOTION knob to raise.
 */
internal object Humanize {
    const val OFF = 0f
    const val MODEST = 1f
    const val STRONGER = 1.8f

    /**
     * A held key's loop when the note is humanised, in seconds, against two for a plain one. A movement that repeats every two
     * seconds is heard as a pattern by the third pass; at four the slowest lean takes four seconds to come round and the ear
     * follows the note instead. It costs each zone about two more seconds of file.
     */
    const val HELD_LOOP_SECONDS = 4f

    /**
     * A one-shot's period: a held key's, so a long note and a held key move at the same pace. HOLD's longest bow or breath is four
     * seconds, so at most the last instant of the longest note comes round. (Six seconds was tried first: its slowest lean takes six
     * seconds, and a note of one to three seconds showed half the movement a held key does.)
     */
    const val ONE_SHOT_PERIOD_SECONDS = 4.0

    /** The slow leans: one, two and three cycles a period (0.25 to 0.75 Hz in a held key's four seconds). */
    val SLOW = intArrayOf(1, 2, 3)
    val SLOW_ALT = intArrayOf(1, 2, 4)
    private val SLOW_WEIGHTS = doubleArrayOf(0.5, 0.3, 0.2)

    /** The quicker unsteadiness of a hand or a lip, near 1.7, 2.3 and 3.1 Hz, snapped to whole cycles of [period]. */
    fun quick(period: Double): IntArray {
        val a = (1.7 * period).roundToInt().coerceAtLeast(2)
        val b = (2.3 * period).roundToInt().coerceAtLeast(a + 1)
        val c = (3.1 * period).roundToInt().coerceAtLeast(b + 1)
        return intArrayOf(a, b, c)
    }

    /**
     * A smooth wander within -1..1 that repeats every [period] seconds: sines at [cycles] whole cycles a period, weighted
     * 0.5, 0.3 and 0.2, their phases drawn from [seed]. [at] is its value and [integral] the integral of it from 0, which
     * is periodic too (each sine is whole cycles), so a rate that wanders by [at] has a phase that returns each period.
     */
    class Wander(seed: Int, val period: Double, private val cycles: IntArray) {
        private val phase = Random(seed).let { r -> DoubleArray(cycles.size) { r.nextDouble() * 2.0 * PI } }

        fun at(t: Double): Double {
            var s = 0.0
            for (k in cycles.indices) s += SLOW_WEIGHTS[k] * sin(2.0 * PI * cycles[k] / period * t + phase[k])
            return s
        }

        fun integral(t: Double): Double {
            var s = 0.0
            for (k in cycles.indices) {
                val w = 2.0 * PI * cycles[k] / period
                s += SLOW_WEIGHTS[k] * (cos(phase[k]) - cos(w * t + phase[k])) / w
            }
            return s
        }
    }
}
