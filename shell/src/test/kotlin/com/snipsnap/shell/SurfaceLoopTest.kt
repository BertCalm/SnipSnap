package com.snipsnap.shell

import com.snipsnap.shell.Modulator.Shape
import com.snipsnap.shell.Modulator.Slot
import com.snipsnap.shell.Modulator.Target
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The frame loop's arithmetic, frame by frame with a hand-turned clock.
 * 120 BPM throughout, so a bar is two seconds and the sums are in the
 * head. Most tests run with a smoothing of 1 - the smoother is
 * `TouchSurface`'s and tested there; here the point is what the loop
 * does with the smoothed finger, and a smoother that lands at once makes
 * every position exact.
 */
class SurfaceLoopTest {

    private val bpm = 120f
    private fun seconds(s: Double): Long = (s * 1_000_000_000L).toLong()
    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    private fun finger(x: Float, y: Float) = Reading(x, y, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)

    private fun inputs(
        at: Double,
        target: Reading = Reading.REST,
        mode: Mode = Mode.XY,
        latched: Boolean = false,
        mods: List<Slot> = Modulator.OFF,
        gesture: Gesture? = null,
        room: Float = 0f,
        ringOnBar: Boolean = false,
        recordBars: Int? = null,
    ) = SurfaceLoop.Inputs(seconds(at), mode, target, latched, mods, gesture, bpm, room, ringOnBar, recordBars)

    @Test
    fun `the first frame is the origin, and every slot counts bars from it`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        val sine = listOf(Slot(Target.CUTOFF, Shape.SINE, Modulator.DEFAULT_RATE_INDEX, depth = 1f), Slot())
        // The loop starts at 5 s on the frame clock: that instant is the
        // origin, not zero, so a slot turned up later still counts from
        // where the loop began.
        val first = loop.step(inputs(5.0, mods = sine))
        near(0f, assertNotNull(first.engineOffsets)[Target.CUTOFF.ordinal])
        // A quarter bar later (0.5 s at 120 BPM) a sine is at its peak: half the swing.
        val quarter = loop.step(inputs(5.5, mods = sine))
        near(0.5f, assertNotNull(quarter.engineOffsets)[Target.CUTOFF.ordinal])
        assertEquals(Modulator.ENGINE_TARGETS, quarter.engineOffsets!!.size, "the engine's slice, not the finger's two")
    }

    @Test
    fun `nothing is sent while no slot is on, and one all-zero frame after the last turns off`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        assertNull(loop.step(inputs(0.0)).engineOffsets, "an untouched MOD row costs no bridge call")
        assertNull(loop.step(inputs(0.1)).engineOffsets)
        val on = listOf(Slot(Target.DRIVE, Shape.RAMP, 4, depth = 0.5f), Slot())
        assertNotNull(loop.step(inputs(0.2, mods = on)).engineOffsets)
        // Off again: one last frame of zeros so the engine is left as it
        // was before any modulator existed, then silence on the bridge.
        val last = assertNotNull(loop.step(inputs(0.3)).engineOffsets)
        assertTrue(last.all { it == 0f }, "the last frame is exactly zero")
        assertNull(loop.step(inputs(0.4)).engineOffsets)
    }

    @Test
    fun `x and y move the finger on this side of the bridge, and held is the finger's own place`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        val onX = listOf(Slot(Target.X, Shape.SINE, Modulator.DEFAULT_RATE_INDEX, depth = 1f), Slot())
        loop.step(inputs(0.0, target = finger(0.3f, 0.6f), mods = onX))
        // A quarter bar in the sine is +0.5 on X: the finger at 0.3 plays at 0.8.
        val f = loop.step(inputs(0.5, target = finger(0.3f, 0.6f), mods = onX))
        near(0.8f, f.play.x)
        near(0.6f, f.play.y)
        assertTrue(f.play.touching)
        // The engine's slice carries nothing for X or Y - they never cross.
        assertEquals(Modulator.ENGINE_TARGETS, f.engineOffsets!!.size)
        // SET A..D captures the finger, not where the nudge had it.
        near(0.3f, assertNotNull(loop.held).x)
    }

    @Test
    fun `latch holds where the finger left, and letting go forgets it`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        loop.step(inputs(0.0, target = finger(0.2f, 0.8f)))
        val up = loop.step(inputs(0.1, target = Reading.REST, latched = true))
        near(0.2f, up.play.x)
        near(0.8f, up.play.y)
        assertTrue(up.play.touching, "held as it was taken, finger down and all - the puck stays lit under LATCH")
        // Not latched, the finger up is the rest position - held is only for LATCH and SET.
        val free = loop.step(inputs(0.2, target = Reading.REST, latched = false))
        assertEquals(Reading.REST, free.play)
        near(0.2f, assertNotNull(loop.held).x)
        loop.letGo()
        assertNull(loop.held)
        assertEquals(Reading.REST, loop.step(inputs(0.3, latched = true)).play, "nothing held, nothing to latch")
    }

    @Test
    fun `a mode change snaps to the target and forgets what was held`() {
        // A slow smoother, so a glide would show.
        val loop = SurfaceLoop(smoothingK = 0.1f)
        for (i in 0 until 50) loop.step(inputs(i * 0.016, target = finger(0.9f, 0.9f)))
        assertNotNull(loop.held)
        val jumped = loop.step(inputs(1.0, target = Reading.REST, mode = Mode.MORPH))
        assertEquals(Reading.REST, jumped.play, "a different instrument, not a glide between two")
        assertNull(loop.held, "corner weights smoothed under XY mean nothing in MORPH")
        // The same mode again glides as before.
        val glide = loop.step(inputs(1.016, target = finger(0.9f, 0.9f), mode = Mode.MORPH))
        assertTrue(glide.play.x < 0.9f && glide.play.x > 0.5f, "gliding toward the finger, got ${glide.play.x}")
    }

    @Test
    fun `every bar freezes on each bar line after the first, and forgets the bar while off`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        loop.step(inputs(0.0))
        // Turned on mid-bar: the first frame only notes the bar (the toggle froze already).
        assertFalse(loop.step(inputs(1.0, ringOnBar = true)).freezeRing)
        assertFalse(loop.step(inputs(1.9, ringOnBar = true)).freezeRing)
        assertTrue(loop.step(inputs(2.1, ringOnBar = true)).freezeRing, "the bar line at 2 s")
        assertFalse(loop.step(inputs(3.0, ringOnBar = true)).freezeRing)
        assertTrue(loop.step(inputs(4.5, ringOnBar = true)).freezeRing, "a frame late is still the new bar")
        // Off, then on again two bars later: no freeze for the bars it missed.
        assertFalse(loop.step(inputs(5.0, ringOnBar = false)).freezeRing)
        assertFalse(loop.step(inputs(8.5, ringOnBar = true)).freezeRing)
        assertTrue(loop.step(inputs(10.1, ringOnBar = true)).freezeRing)
    }

    @Test
    fun `a recording starts on the touch-down, mutes the kit's gesture, and is kept when it has run`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        val n = Gesture.POINTS_PER_BAR
        val old = Gesture(1, FloatArray(n) { 0.9f }, FloatArray(n) { 0.9f })
        val plays = listOf(Slot(Target.X, Shape.GESTURE, 4, depth = 1f), Slot())
        // Armed, nobody touching: nothing starts, and the old gesture still plays (0.9 - 0.5 = +0.4 on X).
        val armed = loop.step(inputs(0.0, mods = plays, gesture = old, recordBars = 1))
        assertFalse(armed.recordingStarted)
        assertNull(armed.recordingBars)
        assertFalse(loop.recording)
        near(0.9f, armed.play.x)
        // The touch-down starts it. The modulators are read before the
        // recorder starts, as the screen's loop always did, so this one
        // frame still hears the old gesture (0.2 + 0.4); what is recorded
        // is the hand, so the recording does not.
        val down = loop.step(inputs(0.5, target = finger(0.2f, 0.3f), mods = plays, gesture = old, recordBars = 1))
        assertTrue(down.recordingStarted)
        assertTrue(loop.recording)
        near(0f, assertNotNull(down.recordingBars))
        near(0.6f, down.play.x)
        // From the next frame the old gesture is muted: the hand plays
        // where it is. Half a bar in (1 s at 120 BPM) the finger has
        // moved, and the readout counts bars.
        val half = loop.step(inputs(1.5, target = finger(0.7f, 0.3f), mods = plays, gesture = old))
        assertFalse(half.recordingStarted)
        near(0.5f, assertNotNull(half.recordingBars))
        assertNull(half.keptGesture)
        near(0.7f, half.play.x, eps = 1e-6f)
        // A whole bar: kept, over.
        val done = loop.step(inputs(2.5, target = finger(0.7f, 0.3f), mods = plays, gesture = old))
        val kept = assertNotNull(done.keptGesture)
        assertEquals(1, kept.bars)
        near(0.2f, kept.x(0f), eps = 1e-3f)
        near(0.7f, kept.x(0.75f), eps = 1e-3f)
        assertFalse(loop.recording)
        // The next frame plays whatever gesture the kit now has - the screen hands it back.
        val after = loop.step(inputs(2.6, mods = plays, gesture = kept))
        assertNull(after.recordingBars)
        assertNotNull(after.engineOffsets)
    }

    @Test
    fun `a dropped recording keeps nothing, and a second touch while recording does not restart it`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        loop.step(inputs(0.0, recordBars = 2))
        assertTrue(loop.step(inputs(0.1, target = finger(0.5f, 0.5f), recordBars = 2)).recordingStarted)
        loop.step(inputs(0.2, target = Reading.REST))
        assertFalse(loop.step(inputs(0.3, target = finger(0.5f, 0.5f), recordBars = 2)).recordingStarted, "one recording at a time")
        loop.dropRecording()
        assertFalse(loop.recording)
        assertNull(loop.step(inputs(5.0, target = finger(0.5f, 0.5f))).keptGesture)
        assertNull(loop.step(inputs(5.1)).recordingBars)
    }

    @Test
    fun `the room follows the level by the frame's length, and a stalled frame is a short step`() {
        val loop = SurfaceLoop(smoothingK = 1f)
        val follow = listOf(Slot(Target.CUTOFF, Shape.FOLLOW, 4, depth = 1f), Slot())
        loop.step(inputs(0.0, mods = follow, room = 1f))
        // Ten seconds of a loud room, in frames: the follower is at the level.
        for (i in 1..600) loop.step(inputs(i / 60.0, mods = follow, room = 1f))
        near(1f, loop.room, eps = 1e-3f)
        near(0.5f, loop.step(inputs(10.0, mods = follow, room = 1f)).engineOffsets!![Target.CUTOFF.ordinal], eps = 1e-3f)
        // Then silence, ten seconds later in one frame: a tenth of a second
        // of release, not ten - the room is still mostly there.
        val stalled = loop.step(inputs(20.0, mods = follow, room = 0f))
        assertTrue(loop.room > 0.5f, "capped at ${SurfaceLoop.MAX_FRAME_SECONDS} s, got ${loop.room}")
        near(loop.room * Modulator.HALF_SWING, stalled.engineOffsets!![Target.CUTOFF.ordinal], eps = 1e-4f)
    }

    @Test
    fun `the smoothing is the twelve hertz one-pole the screen always had`() {
        near(TouchSurface.Smoother.coefficient(12f, 60f), TouchSurface.Smoother.coefficient(SurfaceLoop.SMOOTHING_CUTOFF_HZ, SurfaceLoop.SMOOTHING_RATE_HZ))
        val loop = SurfaceLoop()
        loop.step(inputs(0.0))
        val one = loop.step(inputs(0.016, target = finger(1f, 1f)))
        assertTrue(one.play.x > 0.5f && one.play.x < 1f, "one frame in, part way there: ${one.play.x}")
    }
}
