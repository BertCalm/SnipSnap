package com.snipsnap.shell

import com.snipsnap.shell.PadVelocity.Curve
import com.snipsnap.shell.PadVelocity.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadVelocityTest {
    private val floor = PadHit.SOFTEST

    @Test fun `every curve keeps the softest touch at the floor and the hardest at full`() {
        for (c in Curve.entries) {
            assertEquals(floor, PadVelocity.curve(floor, c), 1e-6f, c.name)
            assertEquals(1f, PadVelocity.curve(1f, c), 1e-6f, c.name)
        }
    }

    @Test fun `SOFT lifts the middle, HARD lowers it, LINEAR leaves it`() {
        val mid = floor + (1f - floor) * 0.5f
        assertEquals(mid, PadVelocity.curve(mid, Curve.LINEAR), 1e-6f)
        assertTrue(PadVelocity.curve(mid, Curve.SOFT) > mid)
        assertTrue(PadVelocity.curve(mid, Curve.HARD) < mid)
        // HARD never drops below the floor, which is what keeps the softest layer reachable.
        assertTrue(PadVelocity.curve(floor + 0.001f, Curve.HARD) >= floor)
    }

    @Test fun `a curve never decreases as the touch gets harder`() {
        for (c in Curve.entries) {
            var previous = 0f
            for (i in 0..100) {
                val v = PadVelocity.curve(floor + (1f - floor) * i / 100f, c)
                assertTrue(v >= previous - 1e-6f, "${c.name} fell at step $i")
                previous = v
            }
        }
    }

    @Test fun `a velocity that is not a number lands in the middle, not on NaN`() {
        assertEquals(floor + (1f - floor) * 0.5f, PadVelocity.curve(Float.NaN, Curve.HARD), 1e-6f)
    }

    @Test fun `POS passes the position through and FULL ignores it`() {
        val range = OnsetRange()
        assertEquals(0.4f, PadVelocity.shape(Mode.POS, Curve.LINEAR, range, 0.4f, 0.9f), 1e-6f)
        assertEquals(1f, PadVelocity.shape(Mode.FULL, Curve.LINEAR, range, 0.4f, 0.1f), 1e-6f)
        assertEquals(1f, PadVelocity.shape(Mode.FULL, Curve.HARD, range, floor, 0f), 1e-6f)
    }

    @Test fun `AREA uses position until it has learned enough touches`() {
        val range = OnsetRange()
        repeat(OnsetRange.MIN_SAMPLES - 1) { range.observe(0.2f + it * 0.05f) }
        assertNull(range.normalised(0.3f))
        assertEquals(0.5f, PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.5f, 0.3f), 1e-6f)
    }

    @Test fun `AREA ranks a light touch below a heavy one once it has learned`() {
        val range = OnsetRange()
        for (i in 0 until 20) range.observe(0.2f + 0.6f * (i % 10) / 9f) // 0.2 .. 0.8
        val light = PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.9f, 0.2f)
        val heavy = PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.1f, 0.8f)
        assertTrue(light < 0.35f, "light was $light")
        assertTrue(heavy > 0.9f, "heavy was $heavy")
        // And the position it was given is ignored: the finger decides.
        assertEquals(
            PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.1f, 0.5f),
            PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.9f, 0.5f),
            1e-6f,
        )
    }

    @Test fun `a panel that reports one pressure every time never makes the pads all alike`() {
        val range = OnsetRange()
        repeat(30) { range.observe(1f) }
        assertNull(range.normalised(1f))
        assertEquals(0.7f, PadVelocity.shape(Mode.AREA, Curve.LINEAR, range, 0.7f, 1f), 1e-6f, "falls back to position")
    }

    @Test fun `no pressure reported, or not a number, is not a sample and gets position`() {
        val range = OnsetRange()
        repeat(30) { range.observe(0f); range.observe(Float.NaN) }
        assertNull(range.normalised(0.5f))
        assertNull(OnsetRange().also { r -> repeat(30) { r.observe(0.1f + it * 0.02f) } }.normalised(0f))
    }

    @Test fun `only the recent touches count, so a new finger or grip is learned`() {
        val range = OnsetRange(capacity = 16)
        repeat(16) { range.observe(0.1f + 0.1f * (it % 4)) }              // 0.1 .. 0.4
        val before = range.normalised(0.5f)!!
        repeat(16) { range.observe(0.6f + 0.1f * (it % 4)) }              // the old ones are pushed out: 0.6 .. 0.9
        val after = range.normalised(0.5f)!!
        assertEquals(1f, before, 1e-6f)
        assertEquals(0f, after, 1e-6f)
    }

    @Test fun `saved names that are unknown or missing fall back to POS and LINEAR`() {
        assertEquals(Mode.POS, PadVelocity.parseMode(null))
        assertEquals(Mode.POS, PadVelocity.parseMode("STRIKE"))
        assertEquals(Mode.AREA, PadVelocity.parseMode("AREA"))
        assertEquals(Curve.LINEAR, PadVelocity.parseCurve("nonsense"))
        assertEquals(Curve.HARD, PadVelocity.parseCurve("HARD"))
    }
}
