package com.snipsnap.kit

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadShapeMathTest {

    private val rate = 44_100
    private val tone = Snip(FloatArray(rate) { i -> (0.5 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() }, 1, rate)

    @Test
    fun `no shape is the input itself`() {
        assertTrue(PadShape.envelope(tone, null, null) === tone)
    }

    @Test
    fun `attack ramps in over up to 400 ms`() {
        assertEquals((0.4f * rate).toInt(), PadShape.attackFrames(1f, rate))
        assertEquals((0.1f * rate).toInt(), PadShape.attackFrames(0.25f, rate))
        val shaped = PadShape.envelope(tone, 1f, null)
        assertEquals(tone.frameCount, shaped.frameCount, "attack never shortens")
        fun peakIn(s: Snip, from: Float, to: Float): Float {
            var p = 0f
            for (i in (from * rate).toInt() until (to * rate).toInt()) p = maxOf(p, Math.abs(s.samples[i]))
            return p
        }
        assertTrue(peakIn(shaped, 0.0f, 0.05f) < 0.1f, "silent at the start")
        assertTrue(peakIn(shaped, 0.5f, 0.6f) > 0.49f, "full after the ramp")
    }

    @Test
    fun `decay fades out by that fraction of the length and trims there`() {
        val shaped = PadShape.envelope(tone, null, 0.3f)
        assertEquals((0.3f * tone.frameCount).toInt(), shaped.frameCount, "trimmed to the shaped length")
        val last = shaped.samples.last()
        assertTrue(Math.abs(last) < 0.01f, "dies at the end: $last")
        assertEquals(0f, PadShape.gainAt(10, 0, 10), "silence from decayEnd on")
    }

    @Test
    fun `cutoff and resonance map the way the SFZ writer declares`() {
        assertEquals(20f, PadShape.cutoffHz(0f), 1e-3f)
        assertEquals(20_000f, PadShape.cutoffHz(1f), 1f)
        assertEquals(632.46f, PadShape.cutoffHz(0.5f), 0.1f)
        assertEquals(12f, PadShape.resonanceDb(1f))
        assertEquals(0.4f, PadShape.attackSeconds(1f))
    }
}
