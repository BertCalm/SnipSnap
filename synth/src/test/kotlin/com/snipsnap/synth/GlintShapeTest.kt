package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2 and §3.2. */
class GlintShapeTest {

    private fun shape(depth: Float, gain: Float = 0.2f): GlintShape = GlintShape.of(depth) { gain }!!

    @Test
    fun `DEPTH 0 has no shape - the render loops keep their literal saw`() {
        assertNull(GlintShape.of(0f) { error("sineGain must not be asked for at DEPTH 0") })
    }

    @Test
    fun `any DEPTH above 0, however small, has a shape`() {
        assertNotNull(GlintShape.of(1e-9f) { error("no sine below DEPTH 0.5") })
        assertNotNull(GlintShape.of(Float.MIN_VALUE) { error("no sine below DEPTH 0.5") })
    }

    @Test
    fun `the sine's gain is only asked for above DEPTH 0_5`() {
        var asked = 0
        for (d in listOf(0.1f, 0.25f, 0.5f)) GlintShape.of(d) { asked++; 1f }
        assertEquals(0, asked)
        for (d in listOf(0.5001f, 0.75f, 1f)) GlintShape.of(d) { asked++; 1f }
        assertEquals(3, asked)
    }

    @Test
    fun `the weights follow the spec's table`() {
        for (d in listOf(0.1f, 0.25f, 0.5f)) {
            val s = shape(d)
            assertEquals(1f, s.burstWeight, "the burst alone at DEPTH $d")
            assertEquals(0f, s.sineWeight, "no sine at DEPTH $d")
        }
        val quarter = shape(0.75f, gain = 0.2f)            // u = 0.5
        assertEquals(sqrt(0.75f), quarter.burstWeight, 1e-6f)
        assertEquals(0.5f * 0.2f, quarter.sineWeight, 1e-6f)
        val most = shape(0.9f, gain = 0.2f)                // u = 0.8
        assertEquals(0.6f, most.burstWeight, 1e-6f)
        assertEquals(0.8f * 0.2f, most.sineWeight, 1e-6f)
        val one = shape(1f, gain = 0.2f)                   // the sine alone, exactly
        assertEquals(0f, one.burstWeight)
        assertEquals(0.2f, one.sineWeight)
    }

    @Test
    fun `the equal-power weights keep the sum of squares at one`() {
        for (d in listOf(0.55f, 0.6f, 0.75f, 0.9f, 0.99f, 1f)) {
            val s = shape(d, gain = 1f)
            assertEquals(1f, s.burstWeight * s.burstWeight + s.sineWeight * s.sineWeight, 1e-5f, "DEPTH $d")
        }
    }

    @Test
    fun `the window is exactly zero at both ends of the cycle, at every DEPTH`() {
        for (d in listOf(Float.MIN_VALUE, 1e-9f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val s = shape(d)
            assertEquals(0f, s.window(0f), "the window at phase 0, DEPTH $d")
            assertEquals(0f, s.window(1f), "the window at phase 1, DEPTH $d")
            assertTrue(s.window(Math.nextDown(1f)) < 1e-3f, "DEPTH $d: the window just before the wrap is not near zero")
        }
    }

    @Test
    fun `the table reproduces the analytic window`() {
        for (d in listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val e = if (d <= 0.5f) 2.0 * d else 1.0
            val s = shape(d)
            var worst = 0.0
            for (i in 0..2000) {
                val p = i / 2000.0
                worst = maxOf(worst, abs(s.window(p.toFloat()) - GlintShape.analyticWindow(p, e)))
            }
            assertTrue(worst < 2e-5, "DEPTH $d: the table is off the analytic window by $worst")
        }
    }

    @Test
    fun `a tiny DEPTH is the saw, except that it starts from zero`() {
        val s = shape(1e-9f)
        for (i in 1 until 2000) {
            val p = i / 2000f
            assertEquals(Glint.windowAt(p), s.window(p), 1e-5f, "phase $p")
        }
    }

    @Test
    fun `the attack is a raised cosine over the first half of e`() {
        // DEPTH 0.5: e = 1, so the attack spans the first half of the cycle.
        val s = shape(0.5f)
        // A quarter of the way through the attack the cosine is at its midpoint: 0.5 * (1 - 0.25)^2.
        assertEquals(0.5f * 0.5625f, s.window(0.25f), 1e-4f)
        // And where the attack ends it has reached 1, so the window is the fall alone: (1 - 0.5)^2.
        assertEquals(0.25f, s.window(0.5f), 1e-4f)
    }

    @Test
    fun `the window never leaves 0 to 1`() {
        for (d in listOf(1e-9f, 0.2f, 0.5f, 1f)) {
            val s = shape(d)
            for (i in 0..4000) {
                val w = s.window(i / 4000f)
                assertTrue(w in 0f..1.0000001f, "DEPTH $d phase ${i / 4000f}: $w")
            }
        }
    }

    @Test
    fun `analyticWindow is zero at both ends, and a real rounding leaves the wrap flat`() {
        for (e in listOf(1e-6, 0.3, 1.0)) {
            assertEquals(0.0, GlintShape.analyticWindow(0.0, e), "e $e at phase 0")
            assertEquals(0.0, GlintShape.analyticWindow(1.0, e), "e $e at phase 1")
        }
        // The saw falls into the wrap at a slope of 1. At e = 0.3 (slope about 0.09 here) and e = 1
        // (about 0.0003) the rounding has taken that slope away; a tiny e is still nearly the saw.
        for (e in listOf(0.3, 1.0)) {
            val h = 1e-4
            val slope = (GlintShape.analyticWindow(1.0 - 2 * h, e) - GlintShape.analyticWindow(1.0 - h, e)) / h
            assertTrue(slope < 0.15, "e $e: the window still falls into the wrap at a slope of $slope")
        }
    }
}
