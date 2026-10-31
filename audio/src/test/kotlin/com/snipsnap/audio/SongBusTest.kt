package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SongBusTest {

    @Test
    fun `a buffer under the ceiling keeps its exact bytes`() {
        val samples = floatArrayOf(0.2f, -0.94f, 0.5f, 0.95f)
        val before = samples.copyOf()
        SongBus.ceiling(samples)
        assertContentEquals(before, samples)
    }

    @Test
    fun `a buffer over the ceiling is scaled as a whole, balance kept`() {
        val samples = floatArrayOf(0.5f, -1.9f, 1.0f)
        SongBus.ceiling(samples)
        assertEquals(SongBus.PEAK, SongBus.peakOf(samples), 1e-6f)
        // One factor for every sample: the ratios inside the buffer survive.
        assertEquals(0.5f / 1.9f, samples[0] / -samples[1], 1e-6f)
        assertEquals(1.0f / 1.9f, samples[2] / -samples[1], 1e-6f)
    }

    @Test
    fun `the ceiling is the arithmetic the four copies used`() {
        // Pinned against the inline form KitPreview, SessionMixdown and the
        // Arranger carried before they shared this, so a refactor of the
        // shared one cannot move any of their bytes.
        val samples = FloatArray(64) { (it - 20) * 0.071f }
        val expected = samples.copyOf()
        var peak = 0f
        for (s in expected) { val a = if (s < 0) -s else s; if (a > peak) peak = a }
        val k = 0.95f / peak
        for (i in expected.indices) expected[i] *= k

        SongBus.ceiling(samples)
        assertContentEquals(expected, samples)
    }

    @Test
    fun `a NaN reads as absent, not as loud`() {
        assertEquals(0.5f, SongBus.peakOf(floatArrayOf(Float.NaN, -0.5f, 0.25f)))
    }
}
