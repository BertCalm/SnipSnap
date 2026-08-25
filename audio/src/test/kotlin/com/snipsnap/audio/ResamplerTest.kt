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

    @Test
    fun `rejects a rate ratio too extreme to allocate an output buffer`() {
        // A corrupt header can hand WavReader any sampleRate > 0 (Snip's
        // only constraint), which resample() then divides by. This ratio
        // demands an output buffer far past what any JVM array can hold —
        // it must fail loudly with IllegalArgumentException, matching the
        // contract every other bad-input path in WavReader/Resampler keeps,
        // rather than crash with NegativeArraySizeException or exhaust the
        // heap trying to honor it.
        val input = Snip(FloatArray(1_000), 1, 1)
        assertFailsWith<IllegalArgumentException> { Resampler.resample(input, 2_000_000_000) }
    }

    private fun sine(frames: Int, hz: Double, rate: Int, amplitude: Float = 0.5f) =
        Snip(
            FloatArray(frames) {
                amplitude * kotlin.math.sin(2.0 * Math.PI * hz * it / rate).toFloat()
            },
            1,
            rate,
        )

    /** Root-mean-square level, ignoring the kernel's ramp-in at the edges. */
    private fun rms(snip: Snip, skip: Int = 64): Float {
        var sum = 0.0
        var n = 0
        for (i in skip until snip.samples.size - skip) {
            sum += snip.samples[i] * snip.samples[i]
            n++
        }
        return kotlin.math.sqrt(sum / n).toFloat()
    }

    @Test
    fun `preserves dc level`() {
        // A kernel that is not normalised shows up here first: constant in,
        // same constant out, or the gain is wrong.
        val output = Resampler.resample(dc(4_000, 0.4f), 48_000)
        for (i in 200 until output.samples.size - 200) {
            assertTrue(abs(output.samples[i] - 0.4f) < 0.005f, "sample $i was ${output.samples[i]}")
        }
    }

    @Test
    fun `preserves the level of a mid band tone`() {
        val input = sine(44_100, hz = 1_000.0, rate = 44_100)
        val output = Resampler.resample(input, 48_000)
        val before = rms(input)
        val after = rms(output)
        assertTrue(abs(after - before) / before < 0.02f, "rms $before -> $after")
    }

    @Test
    fun `does not alias a high tone when downsampling`() {
        // 20 kHz at 48 kHz has nowhere to go at 22.05 kHz: it must be filtered
        // out, not folded back down into the audible band as a loud artefact.
        val input = sine(48_000, hz = 20_000.0, rate = 48_000, amplitude = 0.9f)
        val output = Resampler.resample(input, 22_050)
        assertTrue(rms(output) < 0.1f, "aliased energy: rms ${rms(output)}")
    }

    @Test
    fun `keeps stereo channels independent`() {
        val samples = FloatArray(2_000) { if (it % 2 == 0) 0.6f else -0.6f }
        val output = Resampler.resample(Snip(samples, 2, 44_100), 48_000)
        for (f in 100 until output.frameCount - 100) {
            assertTrue(output.samples[f * 2] > 0.55f, "left at frame $f")
            assertTrue(output.samples[f * 2 + 1] < -0.55f, "right at frame $f")
        }
    }
}
