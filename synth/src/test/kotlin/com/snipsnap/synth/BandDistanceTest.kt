package com.snipsnap.synth

import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The measure every GLINT separation and breath claim is read on ([BandDistance]), held to what
 * it says it is: 0 for the same sound, 2 for disjoint bands, symmetric, tiled across the whole
 * span, and unable to read a silent one. The fixtures are one second of sines at `Dsp.RATE`.
 */
class BandDistanceTest {
    private val rate = Dsp.RATE

    private fun sine(hz: Double): FloatArray = FloatArray(rate) { i -> sin(2 * PI * hz * i / rate).toFloat() }

    /** [first] for its first [samples] samples, then [second], both as [sine] reads them. */
    private fun switching(first: Double, second: Double, samples: Int): FloatArray =
        FloatArray(rate) { i -> sin(2 * PI * (if (i < samples) first else second) * i / rate).toFloat() }

    private fun fixed(v: Double) = "%.4f".format(Locale.ROOT, v)

    @Test
    fun `identical input is exactly zero apart`() {
        val a = sine(440.0)
        assertEquals(0.0, BandDistance.whole(a, a.copyOf(), rate))
    }

    @Test
    fun `the distance is symmetric`() {
        val a = sine(440.0)
        val b = sine(1000.0)
        assertEquals(BandDistance.whole(b, a, rate), BandDistance.whole(a, b, rate))
    }

    @Test
    fun `two tones in different bands are nearly the full 2 apart`() {
        val d = BandDistance.whole(sine(200.0), sine(5000.0), rate)
        println("BandDistance 200 Hz against 5 kHz, whole: ${fixed(d)} (bar 1.9, disjoint bands read 2)")
        assertTrue(d > 1.9, "200 Hz against 5 kHz reads $d, not nearly the 2 of disjoint bands")
    }

    @Test
    fun `the whole span is tiled, not just its first frame`() {
        // b is a's 440 Hz for its first 5000 samples, so its first 4096-sample frame is a's own:
        // a measure that read only that frame would put the two at 0, and the last 0.89 s of b
        // is 3 kHz.
        val a = sine(440.0)
        val b = switching(440.0, 3000.0, 5000)
        val d = BandDistance.whole(a, b, rate)
        println("BandDistance 440 Hz against 440 Hz then 3 kHz from sample 5000, whole: ${fixed(d)} (bar 0.5, a first-frame-only measure reads 0)")
        assertTrue(d > 0.5, "440 Hz against 440 Hz then 3 kHz reads $d: the tail of the span is not being measured")
    }

    @Test
    fun `path is the mean of the segment distances`() {
        val a = sine(440.0)
        val b = switching(440.0, 3000.0, 5000)
        assertEquals(BandDistance.segmentDistances(a, b, rate).average(), BandDistance.path(a, b, rate))
    }

    @Test
    fun `a silent span is refused`() {
        assertFailsWith<IllegalArgumentException> { BandDistance.bands(FloatArray(8192), rate) }
    }
}
