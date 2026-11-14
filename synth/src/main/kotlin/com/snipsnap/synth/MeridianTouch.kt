package com.snipsnap.synth

import kotlin.math.exp

/**
 * Optional strike shaping for a softer finite MERIDIAN touch. Velocity alone
 * barely changes the colour, because the output is levelled afterwards: a soft
 * strike would only be a quieter hard one turned back up. A [shaping] amount
 * above 0 makes a soft strike a broader, darker contact instead:
 *
 * - a wider catch pulse (the contact presses for longer);
 * - a lower brush cutoff;
 * - less drive into the upper modes;
 * - quieter second and third carrier harmonics (DRAW and LIFT only).
 *
 * Each factor scales with (1 − velocity), so it is exactly 1.0 at velocity 1,
 * and exactly 1.0 at shaping 0. Multiplying by an exact 1.0 changes no bit, so
 * a render with no shaping (every saved recipe and factory preset) is the render
 * from before this existed. A patch records its own amount; only the MERIDIAN
 * SIX bank's soft and medium layers set one.
 */
internal object MeridianTouch {
    const val WIDTH_K = 3.0
    const val CUTOFF_K = .8
    const val TILT_K = .6
    const val HARMONIC_K = .9

    fun widthMul(velocity: Double, shaping: Double): Double = 1 + shaping * WIDTH_K * (1 - velocity)
    fun cutoffMul(velocity: Double, shaping: Double): Double = maxOf(.15, 1 - shaping * CUTOFF_K * (1 - velocity))
    fun modeTilt(velocity: Double, shaping: Double, k: Int): Double = exp(-shaping * TILT_K * (1 - velocity) * k)
    fun harmonicMul(velocity: Double, shaping: Double): Double = maxOf(0.0, 1 - shaping * HARMONIC_K * (1 - velocity))
}
