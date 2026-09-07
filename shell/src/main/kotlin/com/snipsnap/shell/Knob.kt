package com.snipsnap.shell

import kotlin.math.ln
import kotlin.math.pow

/**
 * One stepper on a card: a plain-word label, the verb's own range, its
 * default, and how the stepper's 0..1 maps onto it — exponentially where
 * the ear hears ratios (a time, a frequency, a factor), linearly where it
 * hears amounts.
 */
data class Knob(val label: String, val lo: Float, val hi: Float, val default: Float, val exponential: Boolean) {

    /** Stepper fraction 0..1 → the knob's value. */
    fun value(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return if (exponential) lo * (hi / lo).pow(f) else lo + (hi - lo) * f
    }

    /** The knob's value → stepper fraction; the inverse of [value]. */
    fun fraction(value: Float): Float {
        val v = value.coerceIn(lo, hi)
        return if (exponential) (ln(v / lo) / ln(hi / lo)).toFloat() else (v - lo) / (hi - lo)
    }

    /** Where the stepper rests when nothing has been dialed. */
    val defaultFraction: Float get() = fraction(default)
}
