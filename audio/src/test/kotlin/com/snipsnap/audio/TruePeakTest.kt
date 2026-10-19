package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TruePeakTest {

    private fun sine(frames: Int, hz: Double, rate: Int, amp: Double, phase: Double = 0.0) =
        Snip(FloatArray(frames) { (amp * sin(2.0 * PI * hz * it / rate + phase)).toFloat() }, 1, rate)

    @Test
    fun `sees the peak hiding between samples`() {
        // A quarter-rate sine phased 45 degrees between samples: every
        // sample reads amp/sqrt(2), the reconstruction reaches amp.
        val snip = sine(4_410, hz = 11_025.0, rate = 44_100, amp = 0.9, phase = PI / 4)
        assertTrue(abs(snip.peak() - 0.636f) < 0.01f, "sample peak sanity: ${snip.peak()}")
        val tp = TruePeak.of(snip)
        assertTrue(abs(tp - 0.9f) < 0.03f, "true peak $tp should find the hidden 0.9")
    }

    @Test
    fun `an in-phase tone reads the same as its sample peak`() {
        val snip = sine(4_410, hz = 440.0, rate = 44_100, amp = 0.7)
        assertTrue(abs(TruePeak.of(snip) - snip.peak()) < 0.02f, "tp ${TruePeak.of(snip)} vs sample ${snip.peak()}")
    }

    @Test
    fun `silence and DC read honestly`() {
        assertEquals(0f, TruePeak.of(Snip(FloatArray(100), 1, 44_100)))
        val dc = Snip(FloatArray(100) { 0.4f }, 1, 44_100)
        assertTrue(abs(TruePeak.of(dc) - 0.4f) < 0.01f, "DC true peak ${TruePeak.of(dc)}")
    }

    @Test
    fun `non-finite samples read as silence, not as the peak`() {
        val samples = FloatArray(441) { 0.2f }
        samples[10] = Float.NaN
        samples[20] = Float.POSITIVE_INFINITY
        assertTrue(TruePeak.of(Snip(samples, 1, 44_100)).isFinite())
    }

    @Test
    fun `limit brings a hidden peak down to the ceiling`() {
        val snip = sine(4_410, hz = 11_025.0, rate = 44_100, amp = 0.99, phase = PI / 4)
        val limited = TruePeak.limit(snip, ceiling = 0.8f)
        assertTrue(TruePeak.of(limited) <= 0.81f, "limited true peak ${TruePeak.of(limited)}")
        assertTrue(limited.peak() <= 0.8f, "limited sample peak ${limited.peak()}")
    }

    @Test
    fun `limit leaves a quiet snip sample for sample`() {
        val snip = sine(441, hz = 440.0, rate = 44_100, amp = 0.3)
        val limited = TruePeak.limit(snip, ceiling = 0.9f)
        assertTrue(snip.samples.contentEquals(limited.samples), "under-ceiling audio must not move")
    }

    @Test
    fun `stereo is judged by its worst channel`() {
        val frames = 4_410
        val samples = FloatArray(frames * 2)
        for (f in 0 until frames) {
            samples[f * 2] = (0.2 * sin(2.0 * PI * 440.0 * f / 44_100)).toFloat()
            samples[f * 2 + 1] = (0.9 * sin(2.0 * PI * 11_025.0 * f / 44_100 + PI / 4)).toFloat()
        }
        assertTrue(TruePeak.of(Snip(samples, 2, 44_100)) > 0.85f)
    }

    @Test
    fun `rejects a nonsense ceiling`() {
        assertFailsWith<IllegalArgumentException> { TruePeak.limit(sine(10, 440.0, 44_100, 0.5), ceiling = 0f) }
    }
}
