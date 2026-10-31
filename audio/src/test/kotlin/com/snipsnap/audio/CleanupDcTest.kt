package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One non-finite sample must not poison DC removal: the mean is taken
 * over the finite samples only, the same rule [Snip.peak] already holds.
 */
class CleanupDcTest {

    @Test
    fun `a NaN sample does not turn the whole snip NaN`() {
        val samples = FloatArray(101) { 0.5f }.also { it[50] = Float.NaN }
        val out = Cleanup.removeDcOffset(Snip(samples, channels = 1, sampleRate = 44_100))
        val finite = out.samples.filter { it.isFinite() }
        assertEquals(100, finite.size)
        assertTrue(finite.all { kotlin.math.abs(it) < 1e-6f }, "DC still removed around the hole: ${finite.first()}")
    }

    @Test
    fun `an all-non-finite channel is left alone rather than divided by zero`() {
        val snip = Snip(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), channels = 1, sampleRate = 44_100)
        val out = Cleanup.removeDcOffset(snip)
        assertEquals(2, out.samples.size)
    }
}
