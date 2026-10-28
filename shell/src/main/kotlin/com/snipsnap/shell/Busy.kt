package com.snipsnap.shell

import kotlin.math.PI
import kotlin.math.sin

/**
 * One job in flight, as the dock draws it: a [label] saying what is
 * happening and, when the work can be counted, how far along it is.
 *
 * [done] and [total] are a count of whatever the job is made of (pads
 * rendered, files read). A job that cannot be counted leaves [total] null,
 * and the dock shows a bar with no end in it instead of a fraction.
 * [cancel] is null for a job that cannot be stopped part-way; the dock
 * draws a CANCEL control only when one is given.
 */
data class Busy(
    val label: String,
    val done: Int = 0,
    val total: Int? = null,
    val cancel: (() -> Unit)? = null,
) {
    /**
     * How far along the job is, 0..1: [done] over [total], clamped so a
     * count that overshoots or undershoots still draws a bar that fits its
     * track. Null when [total] is null or not above zero - there is no
     * fraction of nothing, so the caller draws the indeterminate form.
     */
    val fraction: Float?
        get() {
            val t = total ?: return null
            if (t <= 0) return null
            return (done.toFloat() / t).coerceIn(0f, 1f)
        }
}

/**
 * When a progress bar may appear, and how long it has to stay once it has.
 *
 * Two rules, and both exist so the bar is never a flicker:
 *
 *  - **Show delay.** A job is invisible for its first [SHOW_AFTER_MS]. One
 *    that finishes inside that window never draws anything, so the many
 *    operations that are nearly instant do not strobe a bar at the user.
 *  - **Minimum linger.** Once it has shown, it stays for [MIN_VISIBLE_MS]
 *    even if the job ends a moment later, so a bar that appeared is a bar
 *    that could be read.
 *
 * The latch owns no clock and schedules nothing: every call is handed `now`
 * in milliseconds on one monotonic clock (the app passes
 * `SystemClock.uptimeMillis()`), which keeps the timing testable on the JVM.
 * [visible] answers for any `now` from the recorded start and stop alone,
 * and [nextChangeAt] says when to ask again, so the dock can sleep until
 * then instead of polling. Not thread-safe; it belongs to the UI thread.
 */
class BusyLatch {

    /** When the current run of visibility began counting; null before any [start]. */
    private var startedAt: Long? = null

    /** When the job stopped; null while it is running or before any [start]. */
    private var endedAt: Long? = null

    /**
     * The job began. A second call while one is already running changes
     * nothing, so the delay is not pushed out by a caller that says it twice.
     *
     * A start while the previous job is still lingering on screen continues
     * that run rather than beginning a new one: the bar was up, and it stays
     * up, with no blink to hidden and a fresh delay back to shown. A start
     * after the bar has gone, or after a job that never showed, begins a
     * fresh delay of its own.
     */
    fun start(now: Long) {
        if (startedAt != null && endedAt == null) return
        if (!visible(now)) startedAt = now
        endedAt = null
    }

    /** The job ended. A call with nothing running changes nothing. */
    fun stop(now: Long) {
        if (startedAt != null && endedAt == null) endedAt = now
    }

    /**
     * Forget the current run as if it had never started: nothing is visible
     * now or later, nothing is scheduled, and the next [start] begins a fresh
     * delay.
     *
     * For a job that ended after its show delay but whose bar the caller never
     * managed to draw: the delay and the draw are two moments, and a job can end
     * between them (the frame that would have drawn the bar came after the job's
     * result had landed). By [stop] and [visible] alone such a run owes a bar for
     * [MIN_VISIBLE_MS], and drawing it then would put a loading screen over the
     * thing that was loading. A bar that was never up has no minimum to serve.
     */
    fun discard() {
        startedAt = null
        endedAt = null
    }

    /** Whether the bar should be on screen at [now]. */
    fun visible(now: Long): Boolean {
        val showAt = showAt() ?: return false
        if (now < showAt || neverShown(showAt)) return false
        val hideAt = hideAt(showAt) ?: return true
        return now < hideAt
    }

    /**
     * The next moment [visible] will answer differently, strictly after
     * [now], or null when it will not change until the next [start] or
     * [stop]: nothing started, a job that already showed and is still
     * running, a job that finished without ever showing, or a bar that has
     * already gone.
     */
    fun nextChangeAt(now: Long): Long? {
        val showAt = showAt() ?: return null
        if (neverShown(showAt)) return null
        if (now < showAt) return showAt
        val hideAt = hideAt(showAt) ?: return null
        return if (now < hideAt) hideAt else null
    }

    /** The moment the current run is due to appear, or null before any [start]. */
    private fun showAt(): Long? = startedAt?.plus(SHOW_AFTER_MS)

    /** True when the job stopped before [showAt], so it never draws at all. */
    private fun neverShown(showAt: Long): Boolean {
        val ended = endedAt ?: return false
        return ended < showAt
    }

    /**
     * The moment a finished run goes away: the stop, or the end of the
     * minimum linger counted from the show, whichever is later. Null while
     * the job is still running, because it has no end yet. Only meaningful
     * for a run that showed; callers rule out [neverShown] first.
     */
    private fun hideAt(showAt: Long): Long? {
        val ended = endedAt ?: return null
        return maxOf(ended, showAt + MIN_VISIBLE_MS)
    }

    companion object {
        /** How long a job runs before its bar first appears. */
        const val SHOW_AFTER_MS = 200L

        /** How long a bar that has appeared stays up, at the least. */
        const val MIN_VISIBLE_MS = 500L
    }
}

/**
 * The pulse of the sixteen placeholder tiles where a starter kit will land.
 *
 * Each tile's fill sits [amount] of the way from the raised surface to the
 * ink, and [amount] moves as a sine of the loop's phase, each tile a
 * sixteenth of a loop behind the one before it in reading order: a soft
 * wave across the grid, not one lamp. It swings between 0 and about
 * [BASE] + [SWING]. The numbers are chosen so the swing is seen: a tile
 * that only moved a few levels of 255 (the first version breathed alpha on
 * a fill that was nearly the stage's own colour) pulses on a screenshot of
 * the code and not on the phone.
 */
object PlaceholderPulse {
    /** Tiles in the grid: 4 rows of 4, numbered in reading order. */
    const val TILES = 16

    /** The middle of the swing. */
    const val BASE = 0.09f

    /** Half the swing: [BASE] plus or minus this. */
    const val SWING = 0.09f

    /** What reduced motion holds: the middle, so the tiles read as "not yet" without moving. */
    const val STILL = BASE

    /** How far from the raised surface to the ink tile [tile] sits at [phase], 0..1 around the loop. */
    fun amount(phase: Float, tile: Int): Float =
        BASE + SWING * sin(2.0 * PI * (phase - tile.toDouble() / TILES)).toFloat()
}
