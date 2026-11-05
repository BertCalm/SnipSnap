package com.snipsnap.synth

import kotlin.math.roundToInt

/** Audition-only source contact; it does not define or save a new TRIO model. */
object TrioContactProfile {
    /** The accepted lower four fifths are an exact bypass. */
    fun amount(touch: Float): Float {
        require(touch.isFinite()) { "Contact TOUCH must be finite" }
        return when {
            touch <= .8f -> 0f
            touch >= 1f -> .8f
            else -> .8f * ((touch - .8f) / .2f)
        }
    }

    /**
     * A third-period opposing contact hollows the third harmonic relative to the
     * root. The complete delayed tail keeps a zero-mean burst zero-mean. There is
     * no compensation: this two-tap FIR has gain between 1-alpha and 1+alpha.
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

    /** Same model-6 score and exciter, with each actor's course contact transformed. */
    fun render(
        macros: Map<String, Float>, midi: Int = 60, velocity: Float = 1f,
        config: TrioConfig = TrioConfig(seed = 47010, model = 6),
    ): TrioProbe {
        val alpha = amount(macros["TOUCH"] ?: Trio.defaults(TrioVoice.ENSEMBLE).getValue("TOUCH"))
        return Trio.renderContactProbe(macros, midi, velocity, config) { course, source ->
            shape(source, course.hz, Dsp.RATE * Dsp.OVERSAMPLE, alpha)
        }
    }
}
