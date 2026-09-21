package com.snipsnap.shell

import kotlin.math.abs

/**
 * TILT's own cursor: the phone's roll/pitch, dead-banded and smoothed,
 * onto a field's normalized 0..1 (x, y) — `GrainFieldScreen`'s control-rate
 * loop (`:app`) calls [step] once per tick, the same shape DUET's own loop
 * already follows for its auto-cursor, and this is the arithmetic behind
 * it, proved here since that loop has no JVM test of its own.
 */
object TiltCursor {

    /**
     * Below this much change from the last position, a reading is a still
     * hand's jitter, not a move — flat is 0.5 both ways, and a hand is
     * never perfectly still, so without a dead band the cursor would
     * shimmer at rest.
     */
    const val DEAD_ZONE = 0.02f

    /** How much of a reading past the dead zone moves the cursor per tick, 0..1 — DUET's own smoothing factor, shared. */
    const val SMOOTHING = 0.5f

    /**
     * [roll]/[pitch] (`TiltSource`'s own 0..1 convention) against the
     * cursor's last position ([prevX], [prevY]): unchanged
     * when the reading hasn't moved past [DEAD_ZONE] from there, otherwise
     * eased toward it by [SMOOTHING] — never snapped straight to the raw
     * reading, or a hand's own small tremor would read as a jump.
     */
    fun step(roll: Float, pitch: Float, prevX: Float, prevY: Float): Pair<Float, Float> {
        val dx = roll - prevX
        val dy = pitch - prevY
        val nx = if (abs(dx) < DEAD_ZONE) prevX else prevX + dx * SMOOTHING
        val ny = if (abs(dy) < DEAD_ZONE) prevY else prevY + dy * SMOOTHING
        return nx to ny
    }
}
