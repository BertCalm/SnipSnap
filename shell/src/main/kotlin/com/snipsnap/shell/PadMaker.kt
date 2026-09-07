package com.snipsnap.shell

import com.snipsnap.kit.PadFromAnything
import kotlin.math.roundToInt

/**
 * The PAD SHEET's PAD FROM ANYTHING card, as data: two knobs over
 * [PadFromAnything]. DEPTH is how far the hit is stretched, exponential
 * because the ear hears ratios; BLOOM is how long the arrival takes.
 */
object PadMaker {

    val DEPTH: Knob = Knob("DEPTH", PadFromAnything.DEPTH_MIN, PadFromAnything.DEPTH_MAX, PadFromAnything.DEPTH_DEFAULT, exponential = true)
    val BLOOM: Knob = Knob("BLOOM", 0f, 1f, 0.3f, exponential = false)

    fun depthLabel(value: Float): String = "×${value.roundToInt()}"
    fun bloomLabel(value: Float): String = "${(value * PadFromAnything.BLOOM_MAX_SEC * 1000).roundToInt()} ms"

    /** The card's two steppers as a spec; a fresh seed every MAKE, like every other door that renders. */
    fun spec(depthFraction: Float, bloomFraction: Float, seed: Long): PadFromAnything.Spec =
        PadFromAnything.Spec(depth = DEPTH.value(depthFraction).roundToInt().toFloat().coerceIn(PadFromAnything.DEPTH_MIN, PadFromAnything.DEPTH_MAX), bloom = BLOOM.value(bloomFraction), seed = seed)
}
