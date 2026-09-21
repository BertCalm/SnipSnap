package com.snipsnap.shell

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiltCursorTest {

    @Test
    fun `a reading inside the dead zone does not move the cursor`() {
        val (x, y) = TiltCursor.step(0.51f, 0.49f, 0.5f, 0.5f)
        assertEquals(0.5f, x)
        assertEquals(0.5f, y)
    }

    @Test
    fun `a reading past the dead zone moves the cursor toward it, eased not snapped`() {
        val (x, y) = TiltCursor.step(0.9f, 0.5f, 0.5f, 0.5f)
        assertTrue(x > 0.5f && x < 0.9f, "x=$x should have moved partway toward 0.9, not snapped to it")
        assertEquals(0.5f, y)
    }

    @Test
    fun `repeated steps converge to within the dead zone of a held target, and stop there`() {
        // The dead zone is a floor under how close the cursor ever settles
        // — once the remaining distance is under it, a step stops closing
        // the gap on purpose (that's what keeps it still at rest), so
        // "converges" means "lands inside the dead zone," not "reaches
        // the target exactly."
        var x = 0.5f
        var y = 0.5f
        repeat(30) {
            val (nx, ny) = TiltCursor.step(0.9f, 0.1f, x, y)
            x = nx
            y = ny
        }
        assertTrue(abs(x - 0.9f) < TiltCursor.DEAD_ZONE, "x=$x should have settled within the dead zone of 0.9")
        assertTrue(abs(y - 0.1f) < TiltCursor.DEAD_ZONE, "y=$y should have settled within the dead zone of 0.1")
        // And once settled, it stays settled under the same held reading.
        val (x2, y2) = TiltCursor.step(0.9f, 0.1f, x, y)
        assertEquals(x, x2)
        assertEquals(y, y2)
    }

    @Test
    fun `a flat phone held still never drifts, however it jitters inside the dead zone`() {
        var x = 0.5f
        var y = 0.5f
        // Comfortably inside DEAD_ZONE (0.02) even after rounding, so this
        // is testing the dead zone's job, not float rounding at its edge.
        repeat(50) { i ->
            val jitterRoll = 0.5f + ((i % 3) - 1) * 0.005f
            val jitterPitch = 0.5f + ((i % 5) - 2) * 0.005f
            val (nx, ny) = TiltCursor.step(jitterRoll, jitterPitch, x, y)
            x = nx
            y = ny
        }
        assertEquals(0.5f, x)
        assertEquals(0.5f, y)
    }

    @Test
    fun `a reading exactly at the dead zone edge does not move it, just past does`() {
        val (atEdge, _) = TiltCursor.step(0.5f + TiltCursor.DEAD_ZONE, 0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, atEdge)
        val (pastEdge, _) = TiltCursor.step(0.5f + TiltCursor.DEAD_ZONE + 0.001f, 0.5f, 0.5f, 0.5f)
        assertTrue(pastEdge > 0.5f)
    }
}
