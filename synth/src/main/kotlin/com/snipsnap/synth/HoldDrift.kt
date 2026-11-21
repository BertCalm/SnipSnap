package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A seeded slow drift for the sustained part of a note: the small ways a player's hand does not
 * hold still. It is a sum of a few sinusoids that each complete a whole number of [cycles] over
 * one span, so the drift has no mean, repeats only once per span and meets itself exactly where
 * a held loop wraps. No random number is drawn while rendering; the same seed gives the same
 * drift on every machine. The peak of the sum is scaled to 1.
 *
 * Position is given as a fraction of the span (1.0 is one whole span; any real number is valid).
 */
internal class HoldDrift(seed: Int, private val cycles: IntArray) {
    private val amplitude = DoubleArray(cycles.size)
    private val phase = DoubleArray(cycles.size)

    init {
        require(cycles.isNotEmpty() && cycles.all { it >= 1 }) { "a hold drift needs whole cycles" }
        val random = Random(seed)
        for (k in cycles.indices) {
            amplitude[k] = (.6 + .4 * random.nextDouble()) / sqrt(cycles[k].toDouble())
            phase[k] = random.nextDouble()
        }
        var peak = 0.0
        for (i in 0 until 2048) peak = maxOf(peak, abs(at(i / 2048.0)))
        for (k in cycles.indices) amplitude[k] /= peak
    }

    fun at(position: Double): Double {
        var sum = 0.0
        for (k in cycles.indices) sum += amplitude[k] * sin(2.0 * PI * (cycles[k] * position + phase[k]))
        return sum
    }

    /** The drift integrated from 0 to [position], in spans. It is periodic too, because the drift has no mean. */
    fun integral(position: Double): Double {
        var sum = 0.0
        for (k in cycles.indices) sum += amplitude[k] / (2.0 * PI * cycles[k]) *
            (cos(2.0 * PI * phase[k]) - cos(2.0 * PI * (cycles[k] * position + phase[k])))
        return sum
    }
}
