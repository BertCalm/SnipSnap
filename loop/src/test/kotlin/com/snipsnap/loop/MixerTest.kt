package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MixerTest {

    /**
     * Left/right channels differ by default (right is left's negative half),
     * so a bug that swaps channels, applies the wrong gain to a channel, or
     * reads the wrong index is never invisible to a test that only bothers to
     * check one value across the whole buffer. Pass `right` explicitly to
     * override.
     */
    private fun buf(frames: Int, level: Float, right: Float = -level / 2f) =
        Snip(FloatArray(frames * 2) { if (it % 2 == 0) level else right }, 2, 48_000)

    private fun tracks(vararg engaged: Boolean) =
        engaged.mapIndexed { i, on ->
            Track("t$i", listOf(LoopBlock("a.wav")), engaged = on)
        }

    @Test
    fun `sums engaged tracks`() {
        // 3 tracks, each buf(10, 0.2f): left=0.2, right=-0.1, gains 1/1.
        // Summed: left = 3*0.2 = 0.6, right = 3*-0.1 = -0.3.
        val out = FloatArray(20)
        Mixer.mix(List(3) { buf(10, 0.2f) }, tracks(true, true, true), out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.6f) < 1e-6f, "left got ${out[f * 2]}")
            assertTrue(abs(out[f * 2 + 1] - -0.3f) < 1e-6f, "right got ${out[f * 2 + 1]}")
        }
    }

    @Test
    fun `skips muted tracks`() {
        // 2 engaged tracks (of 3) contribute: left = 2*0.2 = 0.4, right = 2*-0.1 = -0.2.
        val out = FloatArray(20)
        Mixer.mix(List(3) { buf(10, 0.2f) }, tracks(true, false, true), out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.4f) < 1e-6f, "left got ${out[f * 2]}")
            assertTrue(abs(out[f * 2 + 1] - -0.2f) < 1e-6f, "right got ${out[f * 2 + 1]}")
        }
    }

    @Test
    fun `applies track level`() {
        // buf(10, 0.8f): left=0.8, right=-0.4. level=0.5, pan=0 -> gains 0.5/0.5.
        // left = 0.8*0.5 = 0.4, right = -0.4*0.5 = -0.2.
        val out = FloatArray(20)
        val t = listOf(Track("t", listOf(LoopBlock("a.wav")), level = 0.5f))
        Mixer.mix(listOf(buf(10, 0.8f)), t, out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.4f) < 1e-6f, "left got ${out[f * 2]}")
            assertTrue(abs(out[f * 2 + 1] - -0.2f) < 1e-6f, "right got ${out[f * 2 + 1]}")
        }
    }

    @Test
    fun `pans hard left`() {
        // pan=-1 -> leftGain=min(1,1-(-1))=1, rightGain=min(1,1+(-1))=0.
        // Right output is 0 regardless of the source's right-channel value.
        val out = FloatArray(20)
        val t = listOf(Track("t", listOf(LoopBlock("a.wav")), pan = -1f))
        Mixer.mix(listOf(buf(10, 0.5f)), t, out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.5f) < 1e-6f, "left should be untouched")
            assertEquals(0f, out[f * 2 + 1], "right should be silent")
        }
    }

    @Test
    fun `clears the output buffer before summing`() {
        // The audio thread reuses one buffer forever. Stale audio from the last
        // block must not survive into this one.
        // buf(10, 0.1f): left=0.1, right=-0.05, gains 1/1.
        val out = FloatArray(20) { 9f }
        Mixer.mix(listOf(buf(10, 0.1f)), tracks(true), out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.1f) < 1e-6f, "stale left survived: ${out[f * 2]}")
            assertTrue(abs(out[f * 2 + 1] - -0.05f) < 1e-6f, "stale right survived: ${out[f * 2 + 1]}")
        }
    }

    @Test
    fun `all tracks muted gives silence`() {
        val out = FloatArray(20) { 9f }
        Mixer.mix(List(2) { buf(10, 0.5f) }, tracks(false, false), out)
        for (f in 0 until 10) {
            assertEquals(0f, out[f * 2], "left should be silent")
            assertEquals(0f, out[f * 2 + 1], "right should be silent")
        }
    }

    @Test
    fun `rejects a buffer and track count mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            Mixer.mix(listOf(buf(10, 0.1f)), tracks(true, true), FloatArray(20))
        }
    }
}
