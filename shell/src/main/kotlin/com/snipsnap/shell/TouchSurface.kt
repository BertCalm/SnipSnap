package com.snipsnap.shell

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.PI

/**
 * The tactile surface's arithmetic, kept out of the Android layer so it
 * is tested: fingers on a rectangle become normalised axes, a pinch
 * becomes a depth, a position becomes four corner weights, and a
 * one-pole smoother takes the steps out of all of it. The Compose pad
 * does nothing but feed touches in and paint what comes out; the native
 * engine smooths again at audio rate (`ParameterSmoother` in
 * `app/src/main/cpp`), so the UI-side pass here only has to make the
 * *painted* puck calm at screen rate.
 *
 * Every output is `0..1`. Y is flipped so up is more, the way a knob
 * reads, not the way a screen counts pixels.
 */
object TouchSurface {

    /** How many axes the pad drives. */
    enum class Mode(val axes: Int) {
        /** One finger: X and Y. */
        XY(2),

        /** One finger for X/Y, a second finger's distance from it for Z. */
        XYZ(3),

        /** A vector pad: the puck's position weights four corner states. */
        MORPH(4),
    }

    /** A finger on the pad, in pixels of the pad's own rectangle. */
    data class Touch(val id: Long, val x: Float, val y: Float)

    /**
     * What the pad puts out per event. [z] is only meaningful in [Mode.XYZ];
     * the corner weights only in [Mode.MORPH] (they always sum to 1 there).
     */
    data class Reading(
        val x: Float,
        val y: Float,
        val z: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        /** Any finger down at all. */
        val touching: Boolean,
    ) {
        companion object {
            /** Nobody touching: the centre, no depth, the four corners even. */
            val REST = Reading(0.5f, 0.5f, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = false)
        }
    }

    /**
     * Read the fingers on a [width]×[height] pad.
     *
     * - X/Y come from the *first* finger down ([touches] is in press
     *   order), so a second finger for pinch never steals the puck.
     * - Z is the distance between the first two fingers over the pad's
     *   diagonal, so a full-pad stretch is 1 whatever the aspect ratio.
     *   With only one finger down Z **holds** [previous]'s value: lifting
     *   the pinch finger is letting go of a knob, not turning it to zero,
     *   which is what a player expects of a depth control.
     * - The morph weights are bilinear in (x, y): A top-left, B top-right,
     *   C bottom-left, D bottom-right. Each corner reads 1 exactly at its
     *   corner, the centre is an even quarter each.
     *
     * No fingers returns [Reading.REST] with Z carried from [previous],
     * for the same reason.
     */
    fun read(mode: Mode, touches: List<Touch>, width: Float, height: Float, previous: Reading): Reading {
        require(width > 0f && height > 0f) { "the pad has no size: ${width}x$height" }
        if (touches.isEmpty()) return Reading.REST.copy(z = if (mode == Mode.XYZ) previous.z else 0f)
        val first = touches[0]
        val x = (first.x / width).coerceIn(0f, 1f)
        val y = (1f - first.y / height).coerceIn(0f, 1f)
        val z = when (mode) {
            Mode.XYZ -> if (touches.size >= 2) {
                val second = touches[1]
                (hypot(second.x - first.x, second.y - first.y) / hypot(width, height)).coerceIn(0f, 1f)
            } else {
                previous.z
            }
            else -> 0f
        }
        val (a, b, c, d) = if (mode == Mode.MORPH) morphWeights(x, y) else listOf(0.25f, 0.25f, 0.25f, 0.25f)
        return Reading(x, y, z, a, b, c, d, touching = true)
    }

    /** Bilinear corner weights for a puck at ([x], [y]), both `0..1`, y up. Sum to 1. */
    fun morphWeights(x: Float, y: Float): List<Float> {
        val px = x.coerceIn(0f, 1f)
        val py = y.coerceIn(0f, 1f)
        return listOf(
            (1f - px) * py, // A: top-left
            px * py, // B: top-right
            (1f - px) * (1f - py), // C: bottom-left
            px * (1f - py), // D: bottom-right
        )
    }

    /**
     * A one-pole lowpass: `y += k·(x − y)` per step. [k] is the fraction of
     * the remaining distance closed each step; [coefficient] turns a
     * cutoff in Hz at a given step rate into that fraction. The same
     * equation runs at audio rate in the native engine, so a value that
     * looks calm on screen is the value the DSP is heading for.
     */
    class Smoother(val k: Float, initial: Float = 0f) {
        init {
            require(k > 0f && k <= 1f) { "k is a fraction in (0, 1], got $k" }
        }

        var value: Float = initial
            private set

        /** Step toward [target]; returns the new value. */
        fun step(target: Float): Float {
            value += k * (target - value)
            return value
        }

        /** Jump straight to [target] (a mode change, a reset) with no glide. */
        fun snap(target: Float) {
            value = target
        }

        companion object {
            /** The fraction per step that gives a [cutoffHz] lowpass at [rateHz] steps per second. */
            fun coefficient(cutoffHz: Float, rateHz: Float): Float {
                require(cutoffHz > 0f && rateHz > 0f) { "cutoff and rate are positive: $cutoffHz Hz at $rateHz/s" }
                return (1.0 - exp(-2.0 * PI * cutoffHz / rateHz)).toFloat().coerceIn(1e-6f, 1f)
            }
        }
    }

    /** A [Reading] smoothed axis by axis, for painting the puck at screen rate. */
    class SmoothedReading(k: Float) {
        private val x = Smoother(k, 0.5f)
        private val y = Smoother(k, 0.5f)
        private val z = Smoother(k, 0f)
        private val a = Smoother(k, 0.25f)
        private val b = Smoother(k, 0.25f)
        private val c = Smoother(k, 0.25f)
        private val d = Smoother(k, 0.25f)

        fun step(target: Reading): Reading = Reading(
            x.step(target.x), y.step(target.y), z.step(target.z),
            a.step(target.a), b.step(target.b), c.step(target.c), d.step(target.d),
            touching = target.touching,
        )

        fun snap(target: Reading) {
            x.snap(target.x); y.snap(target.y); z.snap(target.z)
            a.snap(target.a); b.snap(target.b); c.snap(target.c); d.snap(target.d)
        }
    }
}
