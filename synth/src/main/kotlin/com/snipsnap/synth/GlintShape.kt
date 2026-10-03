package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the DEPTH macro does to one render
 * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2 and §3.2).
 *
 * DEPTH 0 is today's engine and has no shape: [of] returns null, and both
 * render loops (`Glint.synthesize`, `GlintHeld.render`) then run their own
 * literal saw-window lines, because the two associate their multiplies
 * differently and no helper can reproduce both. Above 0 the window's edge is
 * rounded, fully so by DEPTH 0.5, and above 0.5 a plain sine at f0, scaled to
 * the burst's loudness, takes over by an equal-power law until at DEPTH 1 it
 * is all that is left. The rounded window and the sine are both exactly zero
 * at every wrap, so `k` still changes for free and only there.
 *
 * Built once per render and never shared: GLINT's renderers hold no mutable
 * state, and held zones render in parallel.
 */
internal class GlintShape private constructor(
    /** Half the edge rounding `e`: the raised-cosine attack lasts this much of a cycle. */
    private val attackSpan: Double,
    /** `(1 − p)^(1 + e)` at `p = i / FALL_INTERVALS`; the last entry is exactly 0. */
    private val fall: FloatArray,
    /** Multiplies the burst pair: `sqrt(1 − u²)`, exactly 1 while the sine is off. */
    val burstWeight: Float,
    /** Multiplies the sine, which rides the main envelope: `u · sineGain`, exactly 0 while the sine is off. */
    val sineWeight: Float,
) {

    /**
     * The rounded window at [phase]: zero at 0 and at 1, with no slope jump
     * across the wrap. The fall is looked up by phase alone (never by a sample
     * counter: a held loop's cycles differ by a sample and its exact closure
     * needs a pure function of phase); the attack is analytic and runs only
     * while `phase < attackSpan`, because at a small `e` it is narrower than a
     * table cell.
     */
    fun window(phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        val x = p * FALL_INTERVALS
        // phase.toFloat() can round up to 1.0f: the last cell then answers with the last entry, 0.
        val i = x.toInt().coerceAtMost(FALL_INTERVALS - 1)
        val lo = fall[i]
        val fell = lo + (fall[i + 1] - lo) * (x - i)
        if (p >= attackSpan) return fell
        return (0.5 * (1.0 - cos(PI * p / attackSpan))).toFloat() * fell
    }

    companion object {
        const val FALL_INTERVALS = 4096

        /** The DEPTH at which the edge is fully rounded and the sine begins to come in. */
        const val EDGE_FULL_AT = 0.5f

        /**
         * The spec's `w_e(p)` for an edge rounding [edge] above 0, in `Double`. [GlintPath.sineGain] and
         * the tests read it; the table and [window] compute the same two formulas on their own, which is
         * what lets the tests hold them to this.
         */
        fun analyticWindow(phase: Double, edge: Double): Double {
            val p = phase.coerceIn(0.0, 1.0)
            val span = 0.5 * edge
            val attack = if (p >= span) 1.0 else 0.5 * (1.0 - cos(PI * p / span))
            return attack * (1.0 - p).pow(1.0 + edge)
        }

        /**
         * The shape for [depth], or null at DEPTH 0. [sineGain] is asked for
         * only when the sine is on (DEPTH above 0.5): it integrates a cycle.
         */
        fun of(depth: Float, sineGain: () -> Float): GlintShape? {
            val d = depth.coerceIn(0f, 1f)
            if (d == 0f) return null
            val edge = if (d <= EDGE_FULL_AT) 2.0 * d else 1.0
            val u = if (d <= EDGE_FULL_AT) 0.0 else 2.0 * d - 1.0
            val fall = FloatArray(FALL_INTERVALS + 1) { i ->
                (1.0 - i.toDouble() / FALL_INTERVALS).pow(1.0 + edge).toFloat()
            }
            fall[FALL_INTERVALS] = 0f
            return GlintShape(
                attackSpan = 0.5 * edge,
                fall = fall,
                burstWeight = if (u == 0.0) 1f else sqrt(1.0 - u * u).toFloat(),
                sineWeight = if (u == 0.0) 0f else (u * sineGain()).toFloat(),
            )
        }
    }
}
