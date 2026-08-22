package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavReaderTest {

    /** Round-trip helper: encode with the writer we already trust, decode with the new reader. */
    private fun roundTrip(snip: Snip, depth: WavWriter.BitDepth): Snip {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, snip, depth)
        return WavReader.read(out.toByteArray())
    }

    private fun ramp(frames: Int, channels: Int = 1) =
        Snip(FloatArray(frames * channels) { (it % 200) / 200f - 0.5f }, channels, 44_100)

    /**
     * Snip.equals compares format and length only, never sample values — so a
     * naive assertEquals would pass on completely different audio.
     */
    private fun assertSamplesClose(expected: Snip, actual: Snip, tolerance: Float) {
        assertEquals(expected.channels, actual.channels, "channels")
        assertEquals(expected.sampleRate, actual.sampleRate, "sampleRate")
        assertEquals(expected.frameCount, actual.frameCount, "frameCount")
        for (i in expected.samples.indices) {
            val d = abs(expected.samples[i] - actual.samples[i])
            assertTrue(d <= tolerance, "sample $i: expected ${expected.samples[i]}, got ${actual.samples[i]}")
        }
    }

    @Test
    fun `reads back a 16 bit mono wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        // 16-bit quantisation step is 1/32768; allow one step.
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }

    @Test
    fun `reads back a 16 bit stereo wav preserving channel order`() {
        // Left rail high, right rail low: a channel swap or interleave bug shows immediately.
        val original = Snip(FloatArray(400) { if (it % 2 == 0) 0.75f else -0.75f }, 2, 44_100)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }
}
