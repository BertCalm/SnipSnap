package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResamplerTest {

    private fun dc(frames: Int, level: Float, channels: Int = 1, rate: Int = 44_100) =
        Snip(FloatArray(frames * channels) { level }, channels, rate)

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
    }

    @Test
    fun `scales frame count by the rate ratio when downsampling`() {
        val input = dc(48_000, 0.1f, rate = 48_000)
        val output = Resampler.resample(input, 44_100)
        assertEquals(44_100, output.sampleRate)
        assertTrue(abs(output.frameCount - 44_100) <= 1, "got ${output.frameCount} frames")
    }

    @Test
    fun `preserves channel count`() {
        val output = Resampler.resample(dc(1_000, 0.2f, channels = 2), 48_000)
        assertEquals(2, output.channels)
        assertEquals(0, output.samples.size % 2, "stereo output must be whole frames")
    }

    @Test
    fun `rejects a non positive target rate`() {
        assertFailsWith<IllegalArgumentException> { Resampler.resample(dc(10, 0.1f), 0) }
    }
}
