package com.snipsnap.shell

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Playing the beat's steps on the audio clock instead of the screen's frames.
 *
 * The beat's clock is a `withFrameNanos` loop, so a step used to sound at the
 * first audio callback after the frame that crossed it: its time, rounded up to
 * a vsync (8 ms at 120 Hz, 17 ms at 60) and then up to a callback, plus whatever
 * a late frame added. The engine can start a voice on any frame of a callback
 * (`PadCommand::atFrame`), so the loop instead looks [LOOKAHEAD_NANOS] ahead of
 * the needle, hands the engine each step it finds with the frame it is due on,
 * and the voice starts there.
 *
 * Everything here is arithmetic over numbers the caller reads; nothing touches a
 * clock or a stream, so the whole of it is under test on the JVM.
 *
 *  - [AudioClock] turns "when" (the frame clock's nanoseconds) into "which frame
 *    of the engine".
 *  - [Cursor] remembers how far ahead of the needle steps have been handed over,
 *    so each is handed over once.
 *  - [forEachNoteDue] and [forEachBeatDue] walk a window of the loop, naming each
 *    step's position so the caller can say when it is due.
 */
object BeatSchedule {

    /**
     * How far ahead of the needle steps are handed to the engine. Two frames at 60 Hz: long enough that a
     * step is queued before the callback that has to play it, short enough that a change of mind (a stop,
     * a bounce arming) has little to take back.
     */
    const val LOOKAHEAD_NANOS = 40_000_000L

    /** The window's length in steps at [stepsPerSecond]: how far past the needle [Cursor.window] reaches. 0 when scheduling is off. */
    fun aheadSteps(scheduling: Boolean, stepsPerSecond: Double): Double =
        if (scheduling) LOOKAHEAD_NANOS / 1_000_000_000.0 * stepsPerSecond else 0.0

    /**
     * When a step at [stepPos] is due, in nanoseconds, given that the needle was at [np] at [nowNanos]. Unwrapped:
     * a step past the loop's end is given as its position plus the loop's length, as [forEachNoteDue] reports it.
     */
    fun dueNanos(nowNanos: Long, np: Float, stepPos: Double, stepsPerSecond: Double): Long =
        nowNanos + ((stepPos - np) / stepsPerSecond * 1_000_000_000.0).roundToLong()

    /**
     * A BOUNCE's edges on the engine's frame counter: it prints exactly `[startFrame, stopFrame)`.
     * [startFrame] is the downbeat's own frame, the one the pass's first note is handed over on.
     */
    data class BouncePlan(val startFrame: Long, val stopFrame: Long) {
        val frames: Long get() = stopFrame - startFrame
    }

    /**
     * The length of [passes] passes over a loop of [totalSteps] steps at [stepsPerSecond], in frames at
     * [sampleRate]. Rounded once, over the whole print, never per pass: a loop is rarely a whole number of
     * frames (93 BPM, 32 steps, 44.1 kHz is 227612.9...), and rounding each pass would let the print drift
     * from the music by up to half a frame per pass. Rounded once it is within half a frame of the true
     * length however many passes there are, and one pass is the nearest whole frame to the loop.
     */
    fun bounceFrames(passes: Int, totalSteps: Float, stepsPerSecond: Double, sampleRate: Int): Long =
        (passes.toDouble() * totalSteps.toDouble() / stepsPerSecond * sampleRate).roundToLong()

    /** The print for a bounce whose first pass starts on [downbeatFrame]: it ends [bounceFrames] later. */
    fun bouncePlan(downbeatFrame: Long, passes: Int, totalSteps: Float, stepsPerSecond: Double, sampleRate: Int): BouncePlan =
        BouncePlan(downbeatFrame, downbeatFrame + bounceFrames(passes, totalSteps, stepsPerSecond, sampleRate))

    /**
     * True when the loop's downbeat, the step position [totalSteps] unwrapped, falls in the window `(from, to]`:
     * the one window it is handed over in, as the step-0 note's own `p + total` is by [forEachNoteDue].
     */
    fun downbeatInWindow(from: Double, to: Double, totalSteps: Float): Boolean {
        val total = totalSteps.toDouble()
        return from < total && total <= to
    }

    /**
     * Where the engine's frame counter stands against the frame clock's nanoseconds, learned from samples of the
     * two read together, and what it says a moment in the future will be.
     *
     * The counter is a staircase: it moves a burst at a time, so a sample can lag the true line by up to a
     * burst, and never leads it. The estimate is therefore the *highest* line any recent sample sits on
     * ([window] of them), which is the least-lagged one; the slope is the sample rate, so a window's worth of
     * clock drift (tens of ppm) is below a frame. A sample far from the line ([JUMP_SECONDS]) is not noise: the
     * stream was restarted, and the counter's gap says nothing about the time ahead, so the history is dropped.
     *
     * Not ready until [MIN_SAMPLES] samples have been seen, and not ready is the caller's cue to play at once,
     * as it always did.
     */
    class AudioClock(private val window: Int = 64) {
        private val offsets = DoubleArray(window)
        private var count = 0
        private var next = 0
        private var rate = 0
        private var origin = 0L
        private var best = 0.0
        private var line = 0.0

        /** True once there is a line to trust. */
        val ready: Boolean get() = rate > 0 && count >= MIN_SAMPLES

        /** Forget everything and start over at [sampleRate]; the stream is new. */
        fun reset(sampleRate: Int) {
            rate = sampleRate
            count = 0
            next = 0
            origin = 0L
            best = 0.0
            line = 0.0
        }

        /** One reading: the engine had rendered [framesRendered] frames at [nanos]. */
        fun observe(framesRendered: Long, nanos: Long) {
            if (rate <= 0) return
            if (count == 0) origin = nanos
            val offset = framesRendered - (nanos - origin).toDouble() * rate / 1e9
            if (count > 0 && abs(offset - best) > JUMP_SECONDS * rate) {
                count = 0
                next = 0
            }
            val fresh = count == 0
            offsets[next] = offset
            next = (next + 1) % window
            if (count < window) count++
            var top = offsets[0]
            for (i in 1 until count) if (offsets[i] > top) top = offsets[i]
            best = top
            // The line follows the best sample, but never faster than clock drift needs: a sample leaving
            // the window would otherwise step every step after it by the difference between two bursts' lags.
            val slew = SLEW_SECONDS * rate
            line = if (fresh || count < window) top else line + (top - line).coerceIn(-slew, slew)
        }

        /** The engine's frame that [nanos] falls on. Only meaningful when [ready]. */
        fun frameAt(nanos: Long): Long = (line + (nanos - origin).toDouble() * rate / 1e9).roundToLong()
    }

    /** Fewest samples before [AudioClock.ready]: a few frames, so the first bar plays at once rather than on a guess. */
    const val MIN_SAMPLES = 4

    /** The most the line moves on one reading, in seconds of the stream: 50 microseconds, a thousand times a frame's share of real drift. */
    const val SLEW_SECONDS = 0.00005

    /** A reading this far (in seconds of the stream) off the line is a restart, not jitter. */
    const val JUMP_SECONDS = 0.1

    /**
     * How far ahead of the needle steps have been handed over, so the next window starts where the last ended.
     *
     * Positions are the loop's own, wrapped, the needle's coordinates: [end] is kept in the coordinates the
     * *next* frame will use, which is why [advance] takes the wrap. A window is `(start, end]`, open at the
     * start, the same convention [BeatClock.crossed] has, so a step is in exactly one window.
     *
     * [start] is the previous window's end, never the needle: with scheduling the two differ by the
     * lookahead, and starting from the needle would hand the same steps over again.
     */
    class Cursor {
        private var cursor = Double.NaN

        /** Start of the window for a frame that begins with the needle at [lastPos]: where the last one ended, or the needle when nothing has. */
        fun start(lastPos: Float): Double = if (cursor.isNaN()) lastPos.toDouble() else cursor

        /** End of the window: [np] plus [ahead], and never before [start], so a window never runs backwards when scheduling turns off. */
        fun end(lastPos: Float, np: Float, ahead: Double): Double = maxOf(start(lastPos), np + ahead)

        /** The window just handed over ended at [end]; [wrappedBy] is the loop length the needle was wrapped by (0 when it was not). */
        fun advance(end: Double, wrappedBy: Float) {
            cursor = if (wrappedBy > 0f) end - wrappedBy else end
        }

        /** Nothing is handed over, and the next window starts at the needle: stopped, sought, or restarted. */
        fun reset() {
            cursor = Double.NaN
        }

        /**
         * Scheduling just went off with steps already handed over: take the cursor back to the needle [np], so
         * the steps still ahead of it are walked again by an ordinary window. The caller has cancelled them
         * on the engine; the ones at or behind [np] are due now and are not.
         */
        fun rewindTo(np: Float) {
            if (!cursor.isNaN() && cursor > np) cursor = np.toDouble()
        }

        /** Steps handed over beyond [np]: positive when scheduling is on and has run, zero or less otherwise. */
        fun leadOver(np: Float): Double = if (cursor.isNaN()) 0.0 else cursor - np
    }

    /**
     * Calls [action] with each note of [clip] in the window `(from, to]` and its position, in steps and
     * unwrapped: a note in the next pass is reported at its position plus [totalSteps]. [from] and [to] are
     * the needle's coordinates and `to` may pass `totalSteps` (one wrap), as [BeatClock.crossed]'s `np` may.
     *
     * A note with a chance under 100 is rolled ([com.snipsnap.kit.StepChance]) on [lap], the pass the window is in
     * (0 from the start, +1 each wrap), and handed over only if it sounds; a note found in the next pass is rolled on
     * `lap + 1`, so the same step is never decided twice and never differently from how its own pass will read it.
     */
    inline fun forEachNoteDue(clip: Mpc3Clip, from: Double, to: Double, totalSteps: Float, lap: Long, action: (Mpc3Note, Double) -> Unit) {
        val total = totalSteps.toDouble()
        for (n in clip.notes) {
            val p = BeatClock.stepOf(n).toDouble()
            if (p > from && p <= to) {
                if (com.snipsnap.kit.StepChance.sounds(clip.name, n, lap)) action(n, p)
            } else if (to >= total && p + total > from && p + total <= to) {
                if (com.snipsnap.kit.StepChance.sounds(clip.name, n, lap + 1)) action(n, p + total)
            }
        }
    }

    /** As [forEachNoteDue] for the metronome: each beat (every fourth step) in the window, with whether it is a bar's downbeat. */
    inline fun forEachBeatDue(totalSteps: Float, from: Double, to: Double, barSteps: Int, action: (accent: Boolean, stepPos: Double) -> Unit) {
        val total = totalSteps.toDouble()
        for (b in 0 until totalSteps.toInt() step 4) {
            val p = b.toDouble()
            if (p > from && p <= to) {
                action(b % barSteps == 0, p)
            } else if (to >= total && p + total > from && p + total <= to) {
                action(b % barSteps == 0, p + total)
            }
        }
    }

    /** A step position folded into the loop, for the one reader (AUDITION's bar) that wants the note's own place in it. */
    fun wrapPos(stepPos: Double, totalSteps: Float): Float {
        val total = totalSteps.toDouble()
        return (if (stepPos >= total) stepPos - total else stepPos).toFloat()
    }
}
