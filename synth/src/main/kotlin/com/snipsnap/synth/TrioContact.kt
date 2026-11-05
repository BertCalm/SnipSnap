package com.snipsnap.synth

import kotlin.math.roundToInt

/** The owner-approved upper-TOUCH contact color, applied to fresh forces rather than the feedback loop. */
internal object TrioContact {
    fun amount(touch: Float, drama: Float = 1f): Float {
        require(touch.isFinite()) { "Contact TOUCH must be finite" }
        require(drama.isFinite() && drama in 0f..1f) { "Contact DRAMA must be 0..1" }
        val contact = when {
            touch <= .8f -> 0f
            touch >= 1f -> .8f
            else -> .8f * ((touch - .8f) / .2f)
        }
        // Match both approved .5/1 scenes exactly, but approach the original zero-DRAMA sound continuously.
        val blend = (drama * 2f).coerceIn(0f, 1f)
        return contact * (blend * blend * (3f - 2f * blend))
    }

    /**
     * An opposing third-period copy softens every third partial relative to the
     * root. Retaining its whole delayed tail preserves a zero-mean force. The
     * feedforward gain stays between 1-alpha and 1+alpha without changing tuning.
     */
    fun shape(source: FloatArray, hz: Float, rate: Int, amount: Float): FloatArray {
        require(hz.isFinite() && hz > 0f) { "Contact frequency must be positive and finite" }
        require(rate > 0) { "Contact sample rate must be positive" }
        require(amount.isFinite() && amount in 0f.. .8f) { "Contact amount must be 0..0.8" }
        require(source.all { it.isFinite() }) { "Contact source must be finite" }
        if (amount == 0f) return source
        val periodThird = rate.toDouble() / (3.0 * hz)
        require(periodThird <= Int.MAX_VALUE - source.size) { "Contact delay is too long" }
        val delay = periodThird.roundToInt().coerceAtLeast(1)
        val output = source.copyOf(source.size + delay)
        for (i in source.indices) output[i + delay] -= amount * source[i]
        return output
    }
}
