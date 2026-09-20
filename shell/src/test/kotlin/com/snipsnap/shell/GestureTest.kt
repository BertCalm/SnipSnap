package com.snipsnap.shell

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    private val n = Gesture.POINTS_PER_BAR

    /** One bar: X sweeps 0 to 1 across the bar, Y sits at a quarter. */
    private fun sweep() = Gesture(1, FloatArray(n) { it.toFloat() / n }, FloatArray(n) { 0.25f })

    @Test
    fun `a gesture is a fixed grid of points a bar, read between them and round the loop`() {
        val g = sweep()
        assertEquals(n, g.points)
        near(0f, g.x(0f))
        near(0.5f, g.x(0.5f))
        near(0.25f, g.y(0.37f))
        // Between two points, on the line between them...
        near(0.5f / n, g.x(0.5f / n))
        // ...and the loop closes: the last point runs back into the first.
        val lastPoint = (n - 1).toFloat() / n
        near((n - 1).toFloat() / n * 0.5f, g.x(lastPoint + 0.5f / n))
        // Phase wraps, and a phase that is not a number is the start.
        near(g.x(0.25f), g.x(1.25f))
        near(g.x(0f), g.x(Float.NaN))
        assertEquals(8, Gesture.MAX_BARS, "PRINT's own longest BARS")
    }

    @Test
    fun `gestures compare by their points, not by reference`() {
        assertEquals(sweep(), sweep())
        assertEquals(sweep().hashCode(), sweep().hashCode())
        assertNotEquals(sweep(), Gesture(1, FloatArray(n) { 0.5f }, FloatArray(n) { 0.25f }))
        assertEquals("Gesture(1 bars, $n points)", sweep().toString())
    }

    @Test
    fun `the recorder holds the finger onto the grid and keeps its last place while lifted`() {
        val r = Gesture.Recorder(1)
        assertFalse(r.done)
        // Nothing until the pad is touched.
        r.add(0.1f, 0.5f, 0.5f, touching = false)
        assertEquals(0, r.filled)
        // A touch a tenth of a bar in fills every point up to now with it.
        r.add(0.1f, 0.2f, 0.8f, touching = true)
        assertEquals((0.1f * n).toInt() + 1, r.filled)
        // Half a bar in, elsewhere: the points between take the new place
        // (a sample-and-hold, at most a frame late), not a slide.
        r.add(0.5f, 0.6f, 0.4f, touching = true)
        assertEquals((0.5f * n).toInt() + 1, r.filled)
        // Lifted for the rest of the bar: the last place is what is kept.
        r.add(0.9f, Float.NaN, Float.NaN, touching = false)
        r.add(1.2f, 0f, 0f, touching = false)
        assertTrue(r.done)
        val g = assertNotNull(r.finish())
        near(0.2f, g.x(0.05f))
        near(0.8f, g.y(0.05f))
        near(0.6f, g.x(0.5f + 1.5f / n))
        near(0.6f, g.x(0.95f))
        near(0.4f, g.y(0.95f))
        // Further frames after done change nothing.
        r.add(2f, 0.9f, 0.9f, touching = true)
        assertEquals(g, r.finish())
    }

    @Test
    fun `finishing early fills the rest with the last place, and a pad never touched is no gesture`() {
        val r = Gesture.Recorder(2)
        r.add(0.3f, 0.7f, 0.1f, touching = true)
        assertFalse(r.done)
        val g = assertNotNull(r.finish())
        assertEquals(2, g.bars)
        near(0.7f, g.x(0.99f))
        near(0.1f, g.y(0.99f))
        assertNull(Gesture.Recorder(1).finish())
        val untouched = Gesture.Recorder(1)
        untouched.add(0.5f, 0.5f, 0.5f, touching = false)
        assertNull(untouched.finish())
        // A frame from the far future - the screen off for a year - fills
        // to the end and is done, rather than the grid index wrapping past
        // Int.MAX to nothing and the recording never finishing.
        val late = Gesture.Recorder(1)
        late.add(0f, 0.2f, 0.2f, touching = true)
        late.add(1e9f, 0.4f, 0.4f, touching = true)
        assertTrue(late.done)
        near(0.4f, assertNotNull(late.finish()).x(0.5f))
    }

    @Test
    fun `a finger off the pad is clamped, and a time that is not a number is the start`() {
        val r = Gesture.Recorder(1)
        r.add(Float.NaN, 1.5f, -0.5f, touching = true)
        assertEquals(1, r.filled)
        val g = assertNotNull(r.finish())
        near(1f, g.x(0f))
        near(0f, g.y(0f))
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { Gesture(0, FloatArray(0), FloatArray(0)) }
        assertFailsWith<IllegalArgumentException> { Gesture(9, FloatArray(9 * n), FloatArray(9 * n)) }
        assertFailsWith<IllegalArgumentException> { Gesture(1, FloatArray(n - 1), FloatArray(n - 1)) }
        assertFailsWith<IllegalArgumentException> { Gesture(1, FloatArray(n), FloatArray(n + 1)) }
        assertFailsWith<IllegalArgumentException> { Gesture(1, FloatArray(n) { 1.5f }, FloatArray(n)) }
        assertFailsWith<IllegalArgumentException> { Gesture(1, FloatArray(n), FloatArray(n) { Float.NaN }) }
        assertFailsWith<IllegalArgumentException> { Gesture.Recorder(0) }
    }
}
