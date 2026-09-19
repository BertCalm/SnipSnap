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
    fun `vector mode computes the same corner weights as morph, not the flat default`() {
        for (x in listOf(0f, 100f, 200f, 300f, 400f)) {
            for (y in listOf(0f, 100f, 200f)) {
                val morph = TouchSurface.read(Mode.MORPH, listOf(Touch(1, x, y)), w, h, Reading.REST)
                val vector = TouchSurface.read(Mode.VECTOR, listOf(Touch(1, x, y)), w, h, Reading.REST)
                near(morph.a, vector.a); near(morph.b, vector.b)
                near(morph.c, vector.c); near(morph.d, vector.d)
            }
        }
        // And it is genuinely the corner blend, not every mode's flat 0.25 fallback.
        val atCorner = TouchSurface.read(Mode.VECTOR, listOf(Touch(1, 0f, 0f)), w, h, Reading.REST)
        near(1f, atCorner.a)
    }

    @Test
    fun `grain reads the finger exactly as XY does - two axes, no depth, flat corners`() {
        // The engine takes GRAIN's x as POSITION and y as pitch; this side
        // only has to hand it the same clean axes XY gets, with none of
        // XYZ's pinch or MORPH's corner arithmetic leaking in.
        for (x in listOf(0f, 100f, 300f, 400f)) {
            for (y in listOf(0f, 50f, 200f)) {
                val xy = TouchSurface.read(Mode.XY, listOf(Touch(1, x, y)), w, h, Reading.REST)
                val grain = TouchSurface.read(Mode.GRAIN, listOf(Touch(1, x, y)), w, h, Reading.REST)
                assertEquals(xy, grain)
            }
        }
        val pinched = TouchSurface.read(Mode.GRAIN, listOf(Touch(1, 0f, 0f), Touch(2, 400f, 200f)), w, h, Reading.REST)
        near(0f, pinched.z)
        near(0.25f, pinched.a)
        assertEquals(2, Mode.GRAIN.axes)
        // The native side takes the mode by ordinal, so GRAIN stays where
        // it was appended - after the four modes that existed before it.
        assertEquals(4, Mode.GRAIN.ordinal)
    }

    @Test
    fun `sample weights are one at each of the four vertices`() {
        val (apex, baseLeft, baseRight, baseMid) = TouchSurface.sampleWeights(0.5f, 1f)
        near(1f, apex); near(0f, baseLeft); near(0f, baseRight); near(0f, baseMid)
        val atBaseLeft = TouchSurface.sampleWeights(0f, 0f)
        near(1f, atBaseLeft[1])
        val atBaseRight = TouchSurface.sampleWeights(1f, 0f)
        near(1f, atBaseRight[2])
        val atBaseMid = TouchSurface.sampleWeights(0.5f, 0f)
        near(1f, atBaseMid[3])
        // Each half-triangle's own centroid (the average of its own three
        // vertices - apex (0.5,1), base-left (0,0), base-mid (0.5,0) for
        // the left half) is an even third apex/base-left/base-mid.
        val leftCentroid = TouchSurface.sampleWeights(1f / 3f, 1f / 3f)
        near(1f / 3f, leftCentroid[0]); near(1f / 3f, leftCentroid[1]); near(1f / 3f, leftCentroid[3])
        near(0f, leftCentroid[2])
    }

    @Test
    fun `sample weights agree exactly at the seam between the two half-triangles`() {
        // The two halves are computed by entirely separate formulas (see
        // sampleWeights), so agreement at x = 0.5 isn't structural - it has
        // to be checked. A discontinuity here would be an audible click
        // sweeping the puck straight across the middle of the pad.
        for (y in 0..10) {
            val py = y / 10f
            val justLeft = TouchSurface.sampleWeights(0.49999f, py)
            val justRight = TouchSurface.sampleWeights(0.50001f, py)
            for (i in 0..3) near(justLeft[i], justRight[i], 1e-3f)
        }
    }

    @Test
    fun `sample weights have no dead zone outside either triangle`() {
        // The pad's own centre sits inside the left half (apex-heavier than
        // that half's own centroid, since it is higher up), and still sums
        // to one.
        val centre = TouchSurface.sampleWeights(0.5f, 0.5f)
        near(1f, centre.sum())
        assertTrue(centre[0] > 1f / 3f)

        // The two top corners sit outside both triangles - one vertex's raw
        // coordinate goes negative there - but the blend stays defined,
        // clamped, and normalised rather than leaving a hole.
        val topLeft = TouchSurface.sampleWeights(0f, 1f)
        near(0f, topLeft[3])  // base-mid: not part of the left half at all
        near(1f, topLeft.sum())
        assertTrue(topLeft[0] > 0f && topLeft[1] > 0f)

        val topRight = TouchSurface.sampleWeights(1f, 1f)
        near(0f, topRight[3])
        near(1f, topRight.sum())
        assertTrue(topRight[0] > 0f && topRight[2] > 0f)
    }

    @Test
    fun `sample weights always sum to one and never go negative`() {
        for (x in 0..20) for (y in 0..10) {
            val w = TouchSurface.sampleWeights(x / 20f, y / 10f)
            near(1f, w.sum())
            assertTrue(w.all { it >= 0f })
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
