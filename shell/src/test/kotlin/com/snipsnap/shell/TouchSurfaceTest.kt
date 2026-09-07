package com.snipsnap.shell

import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import com.snipsnap.shell.TouchSurface.Touch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchSurfaceTest {

    private val w = 400f
    private val h = 200f

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun `one finger reads x across and y up`() {
        val r = TouchSurface.read(Mode.XY, listOf(Touch(1, 100f, 150f)), w, h, Reading.REST)
        near(0.25f, r.x)
        near(0.25f, r.y) // 150 px down a 200 px pad is a quarter of the way up
        assertTrue(r.touching)
        near(0f, r.z)
    }

    @Test
    fun `a finger past the edge clamps to the rail`() {
        val r = TouchSurface.read(Mode.XY, listOf(Touch(1, -30f, 900f)), w, h, Reading.REST)
        near(0f, r.x)
        near(0f, r.y)
    }

    @Test
    fun `no fingers rests at the centre`() {
        val r = TouchSurface.read(Mode.XY, emptyList(), w, h, Reading(0.9f, 0.1f, 0.7f, 1f, 0f, 0f, 0f, true))
        assertEquals(Reading.REST, r)
        assertFalse(r.touching)
    }

    @Test
    fun `z is the pinch over the diagonal and the first finger keeps the puck`() {
        val touches = listOf(Touch(1, 0f, 200f), Touch(2, 400f, 0f)) // corner to corner
        val r = TouchSurface.read(Mode.XYZ, touches, w, h, Reading.REST)
        near(1f, r.z)
        near(0f, r.x) // the first finger, bottom-left
        near(0f, r.y)
        val half = TouchSurface.read(Mode.XYZ, listOf(Touch(1, 0f, 0f), Touch(2, 200f, 100f)), w, h, Reading.REST)
        near(0.5f, half.z)
    }

    @Test
    fun `lifting the pinch finger holds z instead of dropping it`() {
        val pinched = TouchSurface.read(Mode.XYZ, listOf(Touch(1, 0f, 0f), Touch(2, 200f, 100f)), w, h, Reading.REST)
        val oneFinger = TouchSurface.read(Mode.XYZ, listOf(Touch(1, 50f, 50f)), w, h, pinched)
        near(pinched.z, oneFinger.z)
        val lifted = TouchSurface.read(Mode.XYZ, emptyList(), w, h, oneFinger)
        near(pinched.z, lifted.z)
        assertFalse(lifted.touching)
    }

    @Test
    fun `z is zero outside XYZ mode even with two fingers`() {
        val r = TouchSurface.read(Mode.XY, listOf(Touch(1, 0f, 0f), Touch(2, 400f, 200f)), w, h, Reading.REST)
        near(0f, r.z)
    }

    @Test
    fun `morph weights are one at each corner and even in the centre`() {
        fun weights(x: Float, y: Float) = TouchSurface.read(Mode.MORPH, listOf(Touch(1, x, y)), w, h, Reading.REST)
        val tl = weights(0f, 0f)
        near(1f, tl.a); near(0f, tl.b); near(0f, tl.c); near(0f, tl.d)
        val tr = weights(400f, 0f)
        near(1f, tr.b)
        val bl = weights(0f, 200f)
        near(1f, bl.c)
        val br = weights(400f, 200f)
        near(1f, br.d)
        val mid = weights(200f, 100f)
        near(0.25f, mid.a); near(0.25f, mid.b); near(0.25f, mid.c); near(0.25f, mid.d)
    }

    @Test
    fun `morph weights always sum to one`() {
        for (x in 0..10) for (y in 0..10) {
            val ws = TouchSurface.morphWeights(x / 10f, y / 10f)
            near(1f, ws.sum())
            assertTrue(ws.all { it >= 0f })
        }
    }

    @Test
    fun `a pad with no size is refused in words`() {
        assertFailsWith<IllegalArgumentException> {
            TouchSurface.read(Mode.XY, emptyList(), 0f, 100f, Reading.REST)
        }
    }

    @Test
    fun `the smoother closes a fixed fraction per step and settles`() {
        val s = TouchSurface.Smoother(0.5f, 0f)
        near(0.5f, s.step(1f))
        near(0.75f, s.step(1f))
        repeat(60) { s.step(1f) }
        near(1f, s.value, 1e-6f)
        s.snap(0f)
        near(0f, s.value)
    }

    @Test
    fun `the coefficient tracks a cutoff at a rate`() {
        // 1/(2*pi) Hz at 1 step/s: exactly one time constant per step, 1 - e^-1.
        near(0.6321f, TouchSurface.Smoother.coefficient((1.0 / (2 * Math.PI)).toFloat(), 1f), 1e-3f)
        // A higher cutoff at the same rate reacts faster.
        val slow = TouchSurface.Smoother.coefficient(5f, 120f)
        val fast = TouchSurface.Smoother.coefficient(30f, 120f)
        assertTrue(fast > slow)
        // Audio-rate smoothing of a 20 Hz cutoff is a small fraction per sample.
        assertTrue(TouchSurface.Smoother.coefficient(20f, 48_000f) < 0.01f)
        assertFailsWith<IllegalArgumentException> { TouchSurface.Smoother.coefficient(0f, 60f) }
        assertFailsWith<IllegalArgumentException> { TouchSurface.Smoother(0f) }
    }

    @Test
    fun `a smoothed reading glides every axis toward the target`() {
        val sr = TouchSurface.SmoothedReading(0.5f)
        val target = Reading(1f, 0f, 1f, 1f, 0f, 0f, 0f, touching = true)
        val one = sr.step(target)
        near(0.75f, one.x)
        near(0.25f, one.y)
        near(0.5f, one.z)
        near(0.625f, one.a)
        assertTrue(one.touching)
        sr.snap(Reading.REST)
        assertEquals(Reading.REST.x, sr.step(Reading.REST).x)
    }
}
