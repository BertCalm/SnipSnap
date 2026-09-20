package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Velocity.peakMatch] is the oracle several other tests in this suite lean
 * on (via [Velocity.soften], [Velocity.layerAt]) and it had no test of its
 * own before this - hardening audit item 6.
 */
class VelocityTest {

    private fun snipOf(vararg samples: Float, rate: Int = Dsp.RATE) = Snip(samples, channels = 1, sampleRate = rate)

    @Test
    fun `a normal rescale reaches the reference peak exactly`() {
        val reference = snipOf(0f, 0.8f, -0.8f, 0.2f)
        val candidate = snipOf(0f, 0.2f, -0.2f, 0.05f) // peak 0.2, a quarter of reference's 0.8
        val result = Velocity.peakMatch(reference, candidate)
        var resultPeak = 0f
        for (v in result.samples) resultPeak = maxOf(resultPeak, kotlin.math.abs(v))
        assertEquals(0.8f, resultPeak, 1e-5f, "the rescaled candidate should reach the reference's own peak")
        // Shape preserved: every sample scaled by the same gain (4x here).
        for (i in candidate.samples.indices) {
            assertEquals(candidate.samples[i] * 4f, result.samples[i], 1e-4f)
        }
    }

    @Test
    fun `a near-silent candidate is returned untouched rather than divided toward infinity`() {
        val reference = snipOf(0f, 0.8f, -0.8f)
        val candidate = snipOf(0f, 1e-10f, -1e-10f) // candPeak below the 1e-9f guard
        val result = Velocity.peakMatch(reference, candidate)
        // Same object back, not a copy - Snip's data-class equals compares
        // the FloatArray field by reference, so identity is the precise
        // check here (and the guard clause literally `return candidate`).
        assertTrue(result === candidate, "candPeak ~= 0 must short-circuit, not divide by (near) zero")
    }

    @Test
    fun `a near-silent reference is returned untouched rather than zeroing the candidate`() {
        val reference = snipOf(0f, 1e-10f, -1e-10f) // refPeak below the 1e-9f guard
        val candidate = snipOf(0f, 0.5f, -0.5f)
        val result = Velocity.peakMatch(reference, candidate)
        assertTrue(result === candidate, "refPeak ~= 0 must short-circuit rather than scale the candidate toward silence")
    }

    @Test
    fun `output never exceeds unity even if the rescale would otherwise overshoot`() {
        // reference peak 0.99, candidate peak 0.01 -> a 99x gain would send
        // every other sample well past +-1 if it weren't clamped.
        val reference = snipOf(0f, 0.99f)
        val candidate = snipOf(0.5f, 0.01f)
        val result = Velocity.peakMatch(reference, candidate)
        assertTrue(result.samples.all { it in -1f..1f }, "peakMatch must not produce out-of-range samples")
    }
}
