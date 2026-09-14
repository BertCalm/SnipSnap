package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateTest {

    private val steady = Snip(FloatArray(44_100) { 0.5f }, 1, 44_100)

    private fun peakIn(s: Snip, fromSec: Float, toSec: Float): Float {
        var p = 0f
        val from = (fromSec * s.sampleRate).toInt().coerceIn(0, s.frameCount)
        val to = (toSec * s.sampleRate).toInt().coerceIn(from, s.frameCount)
        for (i in from * s.channels until to * s.channels) p = maxOf(p, abs(s.samples[i]))
        return p
    }

    @Test
    fun `AMOUNT zero is a copy`() {
        val out = Gate.chop(steady, 92f, "1/16", 0f)
        assertTrue(out.samples.contentEquals(steady.samples), "GATE at 0 is not a copy")
    }

    @Test
    fun `a gate cuts holes without changing the length`() {
        val out = Gate.chop(steady, 92f, "1/8", 1f)
        assertEquals(steady.frameCount, out.frameCount, "GATE changed the length")
        assertTrue(out.samples.any { it < 0.05f }, "GATE never closed")
        assertTrue(out.samples.any { it > 0.4f }, "GATE never opened")
    }

    @Test
    fun `the edges of the gate do not click`() {
        val out = Gate.chop(steady, 92f, "1/8", 1f)
        for (i in 1 until out.frameCount) {
            assertTrue(
                kotlin.math.abs(out.samples[i] - out.samples[i - 1]) < 0.1f,
                "a step of ${out.samples[i] - out.samples[i - 1]} at $i is a click",
            )
        }
    }

    @Test
    fun `the gate does not fade the hit's own attack`() {
        // A DC fixture is blind to this: phase 0's ramp-up only shows up
        // against a real transient, whose peak sits inside the first edge.
        val kick = Thump.render(ThumpVoice.KICK)
        val out = Gate.chop(kick, 300f, "1/16", 1f)
        // Deep inside the edge fade (well short of EDGE_SEC itself, where the
        // old ramp would already have climbed back near 1) - peak-match
        // rescales the whole buffer, so compare against each buffer's own
        // peak rather than the raw levels.
        val window = Gate.EDGE_SEC / 6f
        val inRatio = peakIn(kick, 0f, window) / kick.peak()
        val outRatio = peakIn(out, 0f, window) / out.peak()
        assertTrue(
            outRatio > inRatio * 0.8f,
            "the gate faded the hit's own attack: source's early/peak ratio $inRatio, gated $outRatio",
        )
    }

    @Test
    fun `a hit shorter than one division is refused in words`() {
        val tiny = Snip(FloatArray(64), 1, 44_100)
        val why = Gate.refusal(tiny, 92f, "1/4")
        assertTrue(why != null && why.contains("1/4"), "the refusal does not name the division: $why")
        assertNull(Gate.refusal(steady, 92f, "1/16"), "a long enough hit was refused")
    }
}
