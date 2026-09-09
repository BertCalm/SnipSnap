package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class SpliceNeedleTest {

    private val rate = 44_100

    @Test
    fun `snaps to the nearer onset within range, from either take`() {
        val mono = FloatArray(rate)
        val onsetsA = intArrayOf(1_000, 5_000)
        val onsetsB = intArrayOf(1_040)
        // 1_040 (in B) is nearer to 1_030 than either onset in A.
        val snapped = SpliceNeedle.snap(1_030, rate - 1, mono, onsetsA, mono, onsetsB, rate)
        assertEquals(1_040, snapped)
    }

    @Test
    fun `an onset outside the snap window is ignored`() {
        val mono = FloatArray(rate)
        // 0.12s window at 44.1kHz is ~5292 frames; put the only onset well outside it.
        val onsetsA = intArrayOf(20_000)
        val snapped = SpliceNeedle.snap(1_000, rate - 1, mono, onsetsA, mono, IntArray(0), rate)
        // No onset in range: falls through to zero-crossing search, which
        // finds none in flat silence either, so the frame is untouched.
        assertEquals(1_000, snapped)
    }

    @Test
    fun `with no onset nearby, snaps to the nearest zero crossing in either take`() {
        val a = FloatArray(rate) { if (it < 1_020) 1f else -1f } // crosses at 1020
        val b = FloatArray(rate) { -1f } // never crosses
        val snapped = SpliceNeedle.snap(1_000, rate - 1, a, IntArray(0), b, IntArray(0), rate)
        assertEquals(1_020, snapped)
    }

    @Test
    fun `a zero crossing in the second take wins when it is nearer`() {
        val a = FloatArray(rate) { -1f } // never crosses
        val b = FloatArray(rate) { if (it < 1_005) 1f else -1f } // crosses at 1005
        val snapped = SpliceNeedle.snap(1_000, rate - 1, a, IntArray(0), b, IntArray(0), rate)
        assertEquals(1_005, snapped)
    }

    @Test
    fun `with nothing to snap to, the clamped frame is returned unchanged`() {
        val mono = FloatArray(rate) // silence: no crossings, no onsets
        assertEquals(500, SpliceNeedle.snap(500, rate - 1, mono, IntArray(0), mono, IntArray(0), rate))
    }

    @Test
    fun `clamps to the range even when the requested frame is out of bounds`() {
        val mono = FloatArray(1_000)
        assertEquals(0, SpliceNeedle.snap(-50, 999, mono, IntArray(0), mono, IntArray(0), rate))
        assertEquals(999, SpliceNeedle.snap(5_000, 999, mono, IntArray(0), mono, IntArray(0), rate))
    }
}
