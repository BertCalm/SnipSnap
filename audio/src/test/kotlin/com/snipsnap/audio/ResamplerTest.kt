package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResamplerTest {

    private fun dc(frames: Int, level: Float, channels: Int = 1, rate: Int = 44_100) =
        Snip(FloatArray(frames * channels) { level }, channels, rate)

    /** Stereo DC with a different constant level per channel, so an interleave swap is visible. */
    private fun stereoDc(frames: Int, left: Float, right: Float, rate: Int = 44_100): Snip {
        val samples = FloatArray(frames * 2)
        for (f in 0 until frames) {
            samples[f * 2] = left
            samples[f * 2 + 1] = right
        }
        return Snip(samples, 2, rate)
    }

    @Test
    fun `returns the same snip when the rate already matches`() {
        val input = dc(100, 0.25f)
        val output = Resampler.resample(input, 44_100)
        assertTrue(output === input, "matching rate should not copy")
    }

    @Test
    fun `scales frame count by the rate ratio when upsampling`() {
        val input = dc(44_100, 0.1f)
        val output = Resampler.resample(input, 48_000)
        assertEquals(48_000, output.sampleRate)
        // One second in, one second out, within a frame of rounding.
        assertTrue(abs(output.frameCount - 48_000) <= 1, "got ${output.frameCount} frames")
        // Interior DC survives any sane interpolator (linear or sinc) — kills a zero-filled output.
        // Sampled away from both edges, where boundary handling doesn't apply.
        val interior = output.samples[output.frameCount / 2]
        assertTrue(abs(interior - 0.1f) < 1e-3f, "interior frame drifted from DC level: $interior")
    }

    @Test
    fun `scales frame count by the rate ratio when downsampling`() {
        val input = dc(48_000, 0.1f, rate = 48_000)
        val output = Resampler.resample(input, 44_100)
        assertEquals(44_100, output.sampleRate)
        assertTrue(abs(output.frameCount - 44_100) <= 1, "got ${output.frameCount} frames")
        val interior = output.samples[output.frameCount / 2]
        assertTrue(abs(interior - 0.1f) < 1e-3f, "interior frame drifted from DC level: $interior")
    }

    @Test
    fun `preserves channel count`() {
        val input = stereoDc(1_000, 0.2f, -0.4f)
        val output = Resampler.resample(input, 48_000)
        assertEquals(2, output.channels)
        assertEquals(0, output.samples.size % 2, "stereo output must be whole frames")
        // Different levels per channel make an interleave swap visible, sampled well inside the buffer.
        val interiorFrame = output.frameCount / 2
        val left = output.samples[interiorFrame * 2]
        val right = output.samples[interiorFrame * 2 + 1]
        assertTrue(abs(left - 0.2f) < 1e-3f, "left channel drifted or was swapped: $left")
        assertTrue(abs(right - -0.4f) < 1e-3f, "right channel drifted or was swapped: $right")
    }

    @Test
    fun `rejects a non positive target rate`() {
        assertFailsWith<IllegalArgumentException> { Resampler.resample(dc(10, 0.1f), 0) }
    }
}
