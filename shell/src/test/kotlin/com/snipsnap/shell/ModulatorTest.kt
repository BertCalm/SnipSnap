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
}
