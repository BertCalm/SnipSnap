package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * BEAT's clock arithmetic, with nothing in it that touches a frame, a
 * screen or an engine: elapsed nanoseconds in, a position in steps out, and
 * the crossings that position implies. It is the arithmetic GROOVE's frame
 * loop used to do inline, lifted out unchanged so the screen that draws the
 * roll and the player that keeps time can both stand on one formula, and so
 * the numbers that were only ever true on a device are pinned in `:shell`.
 *
 * A step is a sixteenth. A position is a Float of steps into the clip's own
 * loop, and the loop is `totalSteps` long (the clip's, or a from-scratch
 * take's `bars * 16`). The types are the old loop's, deliberately: the
 * tempo promotes to a Double for the rate, the increment narrows back to a
 * Float, and the position is a Float throughout. Changing any one of them
 * changes where a rounding lands, so a note that fired on a frame could
 * fire on the next; `BeatClockTest` holds the numbers.
 */
object BeatClock {

    /** Ceiling on one clock tick's elapsed time - a stale `withFrameNanos` gap (backgrounding, a debugger pause) must never fast-forward the needle. */
    const val STEP_MAX_NANOS = 100_000_000L

    /** Steps in one second at [bpm]: a beat is four sixteenths. */
    fun stepsPerSecond(bpm: Float): Double = bpm / 60.0 * 4.0

    /**
     * Nanoseconds the clock advances on the tick that lands at [now], the
     * last having landed at [last]. Floored at zero as well as capped: a
     * frame clock does not run backwards, but a restart can hand this a
     * stale [last] on the other side of [now].
     */
    fun dtNanos(now: Long, last: Long): Long = (now - last).coerceIn(0, STEP_MAX_NANOS)

    /** Where the needle is [dtNanos] after [lastPos], before the wrap: it can be at or past `totalSteps`. */
    fun advance(lastPos: Float, dtNanos: Long, stepsPerSecond: Double): Float {
        val inc = (dtNanos / 1_000_000_000.0 * stepsPerSecond).toFloat()
        return lastPos + inc
    }

    /** True when [np], the position [advance] gave, has gone round the loop. */
    fun wrapped(np: Float, totalSteps: Float): Boolean = np >= totalSteps

    /** [np] folded back into the loop - once, not a modulo: one tick is capped at [STEP_MAX_NANOS], which is never a whole loop. */
    fun wrap(np: Float, totalSteps: Float): Float = if (np >= totalSteps) np - totalSteps else np

    /**
     * Whether a point at [p] steps was passed going from [lastPos] to [np].
     *
     * The first clause is the ordinary case: strictly after where the
     * needle was, up to and including where it is. The second is the wrap:
     * when this tick went round the loop, a point near the top of the next
     * pass (`p + totalSteps`) was passed too, which is how a note at step 0
     * sounds on the pass after the first. Note that a point exactly at
     * [lastPos] has already sounded, so a needle that restarts at 0 never
     * crosses a note at 0 - the first beat of a take anchors with its own
     * click for that reason (GrooveScreen's `recording` branch).
     */
    fun crossed(p: Float, lastPos: Float, np: Float, totalSteps: Float): Boolean =
        (p > lastPos && p <= np) ||
            (np >= totalSteps && p + totalSteps > lastPos && p + totalSteps <= np)

    /** A note's position in steps: its pulses over a step's. */
    fun stepOf(note: Mpc3Note): Float = note.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()

    /** Calls [action] for each note of [clip] this tick passed, in the clip's own order. */
    inline fun forEachNoteCrossed(clip: Mpc3Clip, lastPos: Float, np: Float, totalSteps: Float, action: (Mpc3Note) -> Unit) {
        for (n in clip.notes) {
            if (crossed(stepOf(n), lastPos, np, totalSteps)) action(n)
        }
    }

    /**
     * Calls [action] for each beat (every fourth step) this tick passed,
     * with whether it is a bar's downbeat. A beat is four steps whatever the
     * meter, so a 3/4 clip's bar is twelve steps and its downbeat falls on
     * every third beat.
     */
    inline fun forEachBeatCrossed(totalSteps: Float, lastPos: Float, np: Float, barSteps: Int, action: (accent: Boolean) -> Unit) {
        for (b in 0 until totalSteps.toInt() step 4) {
            if (crossed(b.toFloat(), lastPos, np, totalSteps)) action(b % barSteps == 0)
        }
    }

    /** The bar the needle is in, counted from one. */
    fun bar(posSteps: Float, barSteps: Int): Int = (posSteps / barSteps).toInt() + 1

    /**
     * Where the needle was when a touch landed at [hitNanos], given the last
     * tick's own reading ([anchorNanos], [anchorPos]): the anchor plus the
     * touch's distance from it, at the clock's own rate.
     *
     * The distance is capped both ways. Unlike a tick's dt, which is never
     * negative, a touch can be stamped slightly before the last tick when it
     * lands early in a frame, and that small negative offset is real
     * sub-frame precision, so it is kept rather than floored. A gap of more
     * than [STEP_MAX_NANOS] either side is staleness, not precision.
     */
    fun posAtHit(anchorPos: Float, anchorNanos: Long, hitNanos: Long, stepsPerSecond: Double): Double {
        val dtNanos = (hitNanos - anchorNanos).coerceIn(-STEP_MAX_NANOS, STEP_MAX_NANOS)
        return anchorPos + (dtNanos / 1_000_000_000.0 * stepsPerSecond)
    }
}
