package com.snipsnap.shell

import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading

/**
 * SURFACE's frame loop, pure: everything the screen used to work out
 * between one `withFrameNanos` and the next, with the frame's inputs
 * handed in and its outcomes handed back. The screen's own loop is left
 * with what only it can do - read the clock, poll the engine, paint,
 * and send - so a frame's arithmetic is tested here and the trap that
 * loop has already sprung once (a callback captured at first composition
 * seeing that composition's kit for ever) has nothing left to capture.
 *
 * One [step] a frame does, in order:
 *
 * - **The room.** A [Modulator.Follower] hears [Inputs.room] for the
 *   frame's own length, capped at a tenth of a second so a stalled frame
 *   (the screen off, a long GC) is a short step, not a jump to the level.
 * - **The origin.** The first frame is the modulators' origin, so every
 *   slot counts bars from the same instant and two slots at related
 *   rates stay locked.
 * - **EVERY BAR.** While [Inputs.ringOnBar], the bar index ([RingSlot.barIndex])
 *   is watched: the first frame after it turns on only notes the bar (the
 *   toggle froze already), and each change of bar after that is
 *   [Frame.freezeRing].
 * - **The modulators.** [Modulator.offsets] at the time since the origin,
 *   the room, and the kit's gesture - muted while one is being recorded,
 *   or the hand would be recorded fighting it. The engine's slice is
 *   [Frame.engineOffsets], sent while any slot is on and for exactly one
 *   frame after the last turns off, all zero, so the engine is left as it
 *   was before any modulator existed; null otherwise, which keeps the
 *   untouched path free of a bridge call a frame. X and Y stay on this
 *   side and move the finger below.
 * - **The finger.** A mode change snaps the smoother to the target and
 *   forgets [held] - a different instrument, not a glide between two,
 *   and a reading smoothed under the old mode has corner weights that
 *   mean nothing in the new one. Otherwise the smoother glides toward
 *   the target, and a touching frame's smoothed reading becomes [held].
 * - **GESTURE.** With [Inputs.recordBars] set, the next touch-down starts
 *   a [Gesture.Recorder] of that many bars ([Frame.recordingStarted]);
 *   every frame after feeds it the smoothed finger - the hand, not the
 *   nudged position - against the bar clock, and reports the bars
 *   elapsed ([Frame.recordingBars]); when it has run its length the
 *   gesture is [Frame.keptGesture] and the recording is over.
 * - **What plays.** LATCH with no finger down holds [held]; the X and Y
 *   nudges move whichever it is ([TouchSurface.nudged]); that is
 *   [Frame.play] - what the engine, the sample blend and the puck all
 *   see. [held] was taken before the nudge, so SET A..D keeps the
 *   finger's own place.
 */
class SurfaceLoop(
    smoothingK: Float = TouchSurface.Smoother.coefficient(cutoffHz = SMOOTHING_CUTOFF_HZ, rateHz = SMOOTHING_RATE_HZ),
) {

    /** What the screen hands the loop this frame. */
    data class Inputs(
        /** The frame's clock, in nanoseconds; only differences matter. */
        val nowNanos: Long,
        val mode: Mode,
        /** The pad's latest reading - the finger, or [Reading.REST]. */
        val target: Reading,
        val latched: Boolean,
        val mods: List<Modulator.Slot>,
        /** The kit's recorded gesture, or none. */
        val gesture: Gesture?,
        val bpm: Float?,
        /** The ring's level right now, 0..1; 0 while nothing is listening. */
        val room: Float,
        val ringOnBar: Boolean,
        /** REC armed: how many bars the next touch-down records; null while it is not. */
        val recordBars: Int?,
    )

    /** What one frame concluded. */
    class Frame(
        /** The position to play, paint and blend from: latched, nudged. */
        val play: Reading,
        /** The engine's modulation offsets to send this frame, or null for nothing. */
        val engineOffsets: FloatArray?,
        /** EVERY BAR crossed a bar line: take the freeze again. */
        val freezeRing: Boolean,
        /** The armed recording began on this frame's touch-down. */
        val recordingStarted: Boolean,
        /** Bars elapsed of the recording in flight; null while none is. */
        val recordingBars: Float?,
        /** The recording ran its length this frame: the gesture to keep. */
        val keptGesture: Gesture?,
    )

    private val smoother = TouchSurface.SmoothedReading(smoothingK)
    private val follower = Modulator.Follower()
    private var lastMode: Mode? = null
    private var lastFrame = -1L
    private var origin = -1L
    private var modsWereOn = false
    private var lastRingBar = -1L
    private var recorder: Gesture.Recorder? = null
    private var recordStart = -1L
    private var wasTouching = false

    /**
     * The finger's last smoothed place while it was down - what LATCH
     * holds and SET A..D captures. Nothing until the pad is touched, after
     * a mode change, or after [letGo].
     */
    var held: Reading? = null
        private set

    /** A gesture recording is in flight. */
    val recording: Boolean get() = recorder != null

    /** The room as the follower hears it now, 0..1. */
    val room: Float get() = follower.value

    fun step(i: Inputs): Frame {
        val now = i.nowNanos
        val dt = if (lastFrame < 0L) 0f else (nanosToSeconds(now - lastFrame)).toFloat().coerceIn(0f, MAX_FRAME_SECONDS)
        lastFrame = now
        val roomNow = follower.step(i.room, dt)
        if (origin < 0L) origin = now
        val sinceOrigin = nanosToSeconds(now - origin)

        var freeze = false
        if (i.ringOnBar) {
            val bar = RingSlot.barIndex(sinceOrigin, RingSlot.barSeconds(i.bpm))
            if (lastRingBar >= 0L && bar != lastRingBar) freeze = true
            lastRingBar = bar
        } else {
            lastRingBar = -1L
        }

        val modsOn = i.mods.any { it.depth > 0f }
        var engineOffsets: FloatArray? = null
        var nudgeX = 0f
        var nudgeY = 0f
        if (modsOn || modsWereOn) {
            val offsets = Modulator.offsets(
                i.mods, sinceOrigin, i.bpm,
                follow = roomNow,
                gesture = if (recorder == null) i.gesture else null,
            )
            engineOffsets = Modulator.engineOffsets(offsets)
            nudgeX = offsets[Modulator.Target.X.ordinal]
            nudgeY = offsets[Modulator.Target.Y.ordinal]
            modsWereOn = modsOn
        }

        if (i.mode != lastMode) {
            smoother.snap(i.target)
            lastMode = i.mode
            held = null
        }
        val smooth = smoother.step(i.target)
        if (i.target.touching) held = smooth

        val touchDown = i.target.touching && !wasTouching
        wasTouching = i.target.touching
        var started = false
        val bars = i.recordBars
        if (bars != null && touchDown && recorder == null) {
            recorder = Gesture.Recorder(bars)
            recordStart = now
            started = true
        }
        var recordingBars: Float? = null
        var kept: Gesture? = null
        recorder?.let { rec ->
            val elapsed = (nanosToSeconds(now - recordStart) / RingSlot.barSeconds(i.bpm)).toFloat()
            rec.add(elapsed, smooth.x, smooth.y, i.target.touching)
            recordingBars = elapsed
            if (rec.done) {
                recorder = null
                kept = rec.finish()
            }
        }

        val base = held.takeIf { i.latched && !i.target.touching } ?: smooth
        val play = TouchSurface.nudged(i.mode, base, nudgeX, nudgeY)
        return Frame(play, engineOffsets, freeze, started, recordingBars, kept)
    }

    /** The finger is forgotten: nothing held for LATCH or SET until the pad is touched again. */
    fun letGo() {
        held = null
    }

    /** A recording in flight is dropped, unkept - it was for the kit that just closed. */
    fun dropRecording() {
        recorder = null
    }

    private fun nanosToSeconds(nanos: Long): Double = nanos / 1_000_000_000.0

    companion object {
        /** The painted puck's smoothing: a 12 Hz one-pole at screen rate, the same as the old loop. */
        const val SMOOTHING_CUTOFF_HZ = 12f
        const val SMOOTHING_RATE_HZ = 60f

        /** The longest a frame counts for the room's follower, so a stalled screen is a short step. */
        const val MAX_FRAME_SECONDS = 0.1f
    }
}
