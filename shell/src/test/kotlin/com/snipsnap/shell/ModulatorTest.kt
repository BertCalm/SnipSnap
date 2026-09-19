package com.snipsnap.shell

import com.snipsnap.kit.KitPreview
import com.snipsnap.shell.Modulator.Shape
import com.snipsnap.shell.Modulator.Slot
import com.snipsnap.shell.Modulator.Target
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModulatorTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-5f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun `a bar at the kit's tempo is PrintLength's bar, and a kit without one runs at the default`() {
        // 120 BPM, 4/4: one bar is two seconds, so 1/4 bar is half a second
        // and 4 bars are eight - the same arithmetic BARS on PRINT uses.
        near(2f, Modulator.periodSeconds(Modulator.DEFAULT_RATE_INDEX, 120f))
        near(0.5f, Modulator.periodSeconds(2, 120f))
        near(8f, Modulator.periodSeconds(6, 120f))
        near(PrintLength.seconds(1, KitPreview.DEFAULT_BPM), Modulator.periodSeconds(Modulator.DEFAULT_RATE_INDEX, null))
        assertEquals("1 BAR", Modulator.rateLabel(4))
        assertEquals("4 BARS", Modulator.rateLabel(6))
        assertEquals("1/16 BAR", Modulator.rateLabel(0))
    }

    @Test
    fun `sine rises from zero, ramp climbs and drops, and both stay inside one`() {
        near(0f, Modulator.wave(Shape.SINE, 0f, 0))
        near(1f, Modulator.wave(Shape.SINE, 0.25f, 0))
        near(-1f, Modulator.wave(Shape.SINE, 0.75f, 0))
        near(-1f, Modulator.wave(Shape.RAMP, 0f, 0))
        near(0f, Modulator.wave(Shape.RAMP, 0.5f, 0))
        near(1f, Modulator.wave(Shape.RAMP, 1f, 0))
        for (i in 0..100) {
            val p = i / 100f
            for (shape in Shape.entries) {
                val v = Modulator.wave(shape, p, 3)
                assertTrue(v in -1f..1f, "$shape at $p gave $v")
            }
        }
    }

    @Test
    fun `random holds one value for a whole cycle and throws again on the next`() {
        val held = Modulator.wave(Shape.RANDOM, 0f, 5)
        for (i in 0..20) near(held, Modulator.wave(Shape.RANDOM, i / 20f, 5))
        // Deterministic: the same cycle is the same die every time, which is
        // what lets a print made twice move the same way.
        near(held, Modulator.wave(Shape.RANDOM, 0.3f, 5))
        // Neighbouring cycles do not walk together, and two seeds on one
        // cycle are two dice, not one.
        val values = (0L until 16L).map { Modulator.wave(Shape.RANDOM, 0f, it) }
        assertTrue(values.distinct().size > 12, "cycles are too alike: $values")
        assertNotEquals(Modulator.wave(Shape.RANDOM, 0f, 7, seed = 0), Modulator.wave(Shape.RANDOM, 0f, 7, seed = 1))
    }

    @Test
    fun `an offset is the wave at the slot's rate, scaled by depth and half the travel`() {
        val slot = Slot(Target.CUTOFF, Shape.SINE, rateIndex = 4, depth = 1f)  // one bar at 120 = 2 s
        near(0f, Modulator.offset(slot, 0.0, 120f))
        near(Modulator.HALF_SWING, Modulator.offset(slot, 0.5, 120f))     // a quarter bar in: the crest
        near(-Modulator.HALF_SWING, Modulator.offset(slot, 1.5, 120f))    // three quarters: the trough
        near(0f, Modulator.offset(slot, 2.0, 120f), 1e-4f)                  // the next bar starts over
        near(0.25f, Modulator.offset(slot.copy(depth = 0.5f), 0.5, 120f))  // half depth, half swing
    }

    @Test
    fun `depth zero is exactly nothing, whatever the time`() {
        val off = Slot(Target.PITCH, Shape.RANDOM, depth = 0f)
        for (t in listOf(0.0, 0.37, 12.5, 1e6)) assertEquals(0f, Modulator.offset(off, t, 120f))
        val all = Modulator.offsets(Modulator.OFF, 3.3, 92f)
        assertTrue(all.all { it == 0f })
        assertEquals(Target.entries.size, all.size)
    }

    @Test
    fun `offsets land on their own target, two on one target add up, and the sum clamps`() {
        val cutoff = Slot(Target.CUTOFF, Shape.SINE, 4, depth = 1f)
        val position = Slot(Target.POSITION, Shape.RAMP, 4, depth = 1f)
        val at = Modulator.offsets(listOf(cutoff, position), 0.5, 120f)
        near(Modulator.HALF_SWING, at[Target.CUTOFF.ordinal])
        // A quarter bar into a ramp is a quarter of the way up from the
        // trough (-1 + 0.5 = -0.5 of the wave, so -0.25 at full depth);
        // the ramp's own zero is the half bar, checked below.
        near(-0.25f, at[Target.POSITION.ordinal])
        for (t in Target.entries) if (t != Target.CUTOFF && t != Target.POSITION) near(0f, at[t.ordinal])
        val half = Modulator.offsets(listOf(cutoff, position), 1.0, 120f)
        near(0f, half[Target.POSITION.ordinal], 1e-4f)
        near(0f, half[Target.CUTOFF.ordinal], 1e-4f)  // and the sine is at its own zero crossing there
        // Two full-depth sines on one target reach a whole unit at the crest,
        // and never past it.
        val both = Modulator.offsets(listOf(cutoff, cutoff), 0.5, 120f)
        near(1f, both[Target.CUTOFF.ordinal])
        assertTrue(both.all { it in -1f..1f })
    }

    @Test
    fun `time before the origin, or none at all, reads as the start`() {
        val slot = Slot(Target.DRIVE, Shape.RAMP, 4, depth = 1f)
        near(Modulator.offset(slot, 0.0, 120f), Modulator.offset(slot, -5.0, 120f))
        near(Modulator.offset(slot, 0.0, 120f), Modulator.offset(slot, Double.NaN, 120f))
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { Slot(rateIndex = -1) }
        assertFailsWith<IllegalArgumentException> { Slot(rateIndex = Modulator.RATES.size) }
        assertFailsWith<IllegalArgumentException> { Slot(depth = 1.5f) }
        assertFailsWith<IllegalArgumentException> { Slot(depth = Float.NaN) }
        assertEquals(Modulator.SLOTS, Modulator.OFF.size)
    }

    @Test
    fun `X and Y are the finger's own targets - last, off the engine, and the engine's slice is the first eleven`() {
        // Eleven is also SurfaceEngine.MOD_TARGETS and SurfaceEngine.h's
        // kModTargets, by hand: change all three together or the bridge
        // will read a POSITION offset as something else.
        assertEquals(11, Modulator.ENGINE_TARGETS)
        assertTrue(Target.entries.take(Modulator.ENGINE_TARGETS).all { it.onEngine }, "the engine's targets come first, by ordinal")
        assertTrue(Target.entries.drop(Modulator.ENGINE_TARGETS).none { it.onEngine }, "and the finger's come after")
        assertEquals(Modulator.ENGINE_TARGETS, Target.X.ordinal)
        assertEquals(Modulator.ENGINE_TARGETS + 1, Target.Y.ordinal)
        assertEquals(Target.POSITION, Target.entries[Modulator.ENGINE_TARGETS - 1], "POSITION is the engine's last")

        val x = Slot(Target.X, Shape.RAMP, 4, depth = 1f)
        val cutoff = Slot(Target.CUTOFF, Shape.SINE, 4, depth = 1f)
        val all = Modulator.offsets(listOf(x, cutoff), 0.5, 120f)
        assertEquals(Target.entries.size, all.size)
        near(-0.25f, all[Target.X.ordinal])
        near(Modulator.HALF_SWING, all[Target.CUTOFF.ordinal])
        val engine = Modulator.engineOffsets(all)
        assertEquals(Modulator.ENGINE_TARGETS, engine.size)
        near(Modulator.HALF_SWING, engine[Target.CUTOFF.ordinal])
        assertTrue(engine.indices.all { engine[it] == all[it] }, "the slice is a prefix, value for value")
        assertFailsWith<IllegalArgumentException> { Modulator.engineOffsets(FloatArray(Modulator.ENGINE_TARGETS)) }
    }

    @Test
    fun `follow and duck run on the room, not the bar, and a silent room is exactly the finger`() {
        assertTrue(Shape.FOLLOW.followsRoom && Shape.DUCK.followsRoom)
        assertTrue(listOf(Shape.SINE, Shape.RAMP, Shape.RANDOM).none { it.followsRoom })
        assertEquals(listOf(Shape.FOLLOW, Shape.DUCK), Shape.entries.subList(3, 5), "appended after the clocked three: the MOD row cycles shapes by ordinal")

        val follow = Slot(Target.CUTOFF, Shape.FOLLOW, 4, depth = 1f)
        val duck = Slot(Target.CUTOFF, Shape.DUCK, 4, depth = 1f)
        // Half the travel at a full room, one-sided, and the clock is not consulted.
        near(Modulator.HALF_SWING, Modulator.offset(follow, 0.0, 120f, follow = 1f))
        near(0.3f, Modulator.offset(follow, 0.0, 120f, follow = 0.6f))
        near(0.3f, Modulator.offset(follow, 17.3, 120f, follow = 0.6f))
        near(0.3f, Modulator.offset(follow.copy(rateIndex = 0), 17.3, 92f, follow = 0.6f))
        near(-0.3f, Modulator.offset(duck, 0.0, 120f, follow = 0.6f))
        near(0.15f, Modulator.offset(follow.copy(depth = 0.5f), 0.0, 120f, follow = 0.6f))
        // Silence, a room that is not a number, and a room past full.
        assertEquals(0f, Modulator.offset(follow, 0.0, 120f, follow = 0f))
        assertEquals(0f, Modulator.offset(follow, 0.0, 120f, follow = Float.NaN))
        near(Modulator.HALF_SWING, Modulator.offset(follow, 0.0, 120f, follow = 3f))
        // The clocked shapes do not hear the room.
        near(0f, Modulator.offset(Slot(Target.PITCH, Shape.SINE, 4, depth = 1f), 0.0, 120f, follow = 1f))
        // Through offsets: FOLLOW and DUCK on one target cancel at any level.
        val both = Modulator.offsets(listOf(follow, duck), 4.2, 120f, follow = 0.8f)
        near(0f, both[Target.CUTOFF.ordinal])
        val one = Modulator.offsets(listOf(follow), 4.2, 120f, follow = 0.8f)
        near(0.4f, one[Target.CUTOFF.ordinal])
    }

    @Test
    fun `the follower opens at once and lets go over a quarter of a second`() {
        val f = Modulator.Follower()
        assertEquals(0f, f.value)
        val frame = 1f / 60f
        f.step(1f, frame)
        f.step(1f, frame)
        assertTrue(f.value > 0.9f, "two frames of a clap should be nearly all the way up, got ${f.value}")
        val top = f.value
        // One release constant later it has fallen to about a third...
        val third = f.step(0f, Modulator.RELEASE_SECONDS)
        assertTrue(third > top * 0.3f && third < top * 0.45f, "one release constant should leave about 1/e, got $third of $top")
        // ...and a second later it is gone.
        assertTrue(f.step(0f, 1f) < 0.02f)
        // A level that is not a number holds; a frame of no length, or of
        // nonsense length, holds; a level past full stays inside 0..1.
        f.step(1f, frame)
        val held = f.value
        assertEquals(held, f.step(Float.NaN, frame))
        assertEquals(held, f.step(0f, 0f))
        assertEquals(held, f.step(0f, -1f))
        assertEquals(held, f.step(0f, Float.NaN))
        f.step(5f, 1f)
        assertTrue(f.value <= 1f)
        f.reset()
        assertEquals(0f, f.value)
        assertFailsWith<IllegalArgumentException> { Modulator.Follower(attackSeconds = 0f) }
        assertFailsWith<IllegalArgumentException> { Modulator.Follower(releaseSeconds = Float.NaN) }
    }

    @Test
    fun `gesture plays the recorded finger over its own bars, and at full depth from rest it is the finger itself`() {
        assertTrue(Shape.GESTURE.playsGesture)
        assertTrue(Shape.entries.filter { it != Shape.GESTURE }.none { it.playsGesture })
        assertEquals(Shape.GESTURE, Shape.entries.last(), "appended last: the MOD row cycles shapes by ordinal")
        val n = Gesture.POINTS_PER_BAR
        // Two bars: X sweeps 0..1 across the two bars, Y holds 0.75.
        val g = Gesture(2, FloatArray(2 * n) { it.toFloat() / (2 * n) }, FloatArray(2 * n) { 0.75f })
        val onX = Slot(Target.X, Shape.GESTURE, rateIndex = 0, depth = 1f)  // RATE is ignored
        val onY = Slot(Target.Y, Shape.GESTURE, 4, depth = 1f)
        val onCutoff = Slot(Target.CUTOFF, Shape.GESTURE, 4, depth = 1f)
        // 120 BPM: a bar is 2 s, the gesture 4 s. Halfway (2 s) X is 0.5, so
        // the offset is 0; a quarter in (1 s) X is 0.25, offset -0.25 -
        // rest (0.5) plus that is 0.25, the finger's own place.
        near(0f, Modulator.offset(onX, 2.0, 120f, gesture = g))
        near(-0.25f, Modulator.offset(onX, 1.0, 120f, gesture = g))
        near(0.25f, Modulator.offset(onX, 3.0, 120f, gesture = g))
        // It loops on its own length: 5 s is 1 s into the second time round.
        near(-0.25f, Modulator.offset(onX, 5.0, 120f, gesture = g))
        // Y and any other target read the Y track: 0.75 is +0.25 at full depth, half that at half.
        near(0.25f, Modulator.offset(onY, 0.7, 120f, gesture = g))
        near(0.25f, Modulator.offset(onCutoff, 0.7, 120f, gesture = g))
        near(0.125f, Modulator.offset(onCutoff.copy(depth = 0.5f), 0.7, 120f, gesture = g))
        // No gesture recorded is nothing; the clocked shapes ignore the gesture.
        assertEquals(0f, Modulator.offset(onX, 1.0, 120f, gesture = null))
        near(0f, Modulator.offset(Slot(Target.X, Shape.SINE, 4, depth = 1f), 0.0, 120f, gesture = g))
        // Through offsets, by index.
        val all = Modulator.offsets(listOf(onX, onY), 1.0, 120f, gesture = g)
        near(-0.25f, all[Target.X.ordinal])
        near(0.25f, all[Target.Y.ordinal])
    }
}
