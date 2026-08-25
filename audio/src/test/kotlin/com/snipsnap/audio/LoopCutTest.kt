package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopCutTest {

    private val rate = 44_100

    /**
     * A realistic held note: 220 Hz with slow vibrato, harmonics, a slight
     * decay, and a noise floor — everything the synthetic organ never had.
     */
    private fun heldNote(seconds: Float = 2.0f, vibrato: Float = 0.004f): Snip {
        val rng = Random(4)
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val hz = 220.0 * (1.0 + vibrato * sin(2 * PI * 5.0 * t))
            phase += 2 * PI * hz / rate
            val env = (if (t < 0.01) t / 0.01 else Math.exp(-0.25 * (t - 0.01))).toFloat()
            out[i] = ((0.5 * sin(phase) + 0.18 * sin(2 * phase) + 0.06 * sin(3 * phase)).toFloat() +
                (rng.nextFloat() * 2 - 1) * 0.002f) * env
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a held note gains a seamless whole-period loop`() {
        val src = heldNote()
        val looped = LoopCut.sustainLoop(src)
        assertNotNull(looped, "a two-second held note must loop")

        val loopStart = looped.loopStartFrame.toInt()
        val end = looped.snip.frameCount
        assertTrue(end < src.frameCount, "trimmed to end on the loop boundary")
        assertTrue(loopStart >= (0.08 * rate).toInt(), "never loops into the attack")
        assertTrue(end - loopStart >= (0.25 * rate).toInt(), "loop long enough to breathe")

        // Independent continuity check: the wrap jump vs how much the signal
        // typically moves per frame inside the loop.
        val s = looped.snip.samples
        var typ = 0.0
        for (i in loopStart until end - 1) {
            val d = s[i + 1] - s[i]
            typ += d.toDouble() * d
        }
        val typicalRms = sqrt(typ / (end - 1 - loopStart))
        val jump = abs(s[loopStart] - s[end - 1]).toDouble()
        assertTrue(jump < typicalRms * 8, "wrap jump $jump vs typical step $typicalRms")
        assertTrue(looped.seamError < 1f, "seam score ${looped.seamError}")
    }

    @Test
    fun `heavy vibrato gets a baked crossfade rather than a raw seam`() {
        // Strong vibrato makes whole-period seams genuinely misaligned.
        val looped = LoopCut.sustainLoop(heldNote(vibrato = 0.02f))
        assertNotNull(looped)
        assertTrue(looped.crossfaded, "a messy seam should have been baked")
        // And it still wraps cleanly.
        val s = looped.snip.samples
        val end = looped.snip.frameCount
        val loopStart = looped.loopStartFrame.toInt()
        var typ = 0.0
        for (i in loopStart until end - 1) {
            val d = s[i + 1] - s[i]
            typ += d.toDouble() * d
        }
        val jump = abs(s[loopStart] - s[end - 1]).toDouble()
        assertTrue(jump < sqrt(typ / (end - 1 - loopStart)) * 8)
    }

    @Test
    fun `stereo notes loop with both channels intact`() {
        val mono = heldNote()
        val stereo = FloatArray(mono.samples.size * 2)
        for (i in 0 until mono.frameCount) {
            stereo[i * 2] = mono.samples[i]
            stereo[i * 2 + 1] = mono.samples[i] * 0.8f
        }
        val looped = LoopCut.sustainLoop(Snip(stereo, 2, rate))
        assertNotNull(looped)
        assertEquals(2, looped.snip.channels)
        assertEquals(0, looped.snip.samples.size % 2)
    }

    @Test
    fun `noise and too-short notes are refused, not faked`() {
        val rng = Random(7)
        assertNull(LoopCut.sustainLoop(Snip(FloatArray(rate) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, rate)))
        assertNull(LoopCut.sustainLoop(heldNote(seconds = 0.2f)), "nothing to loop in 200 ms")
        assertNull(LoopCut.sustainLoop(Snip(FloatArray(0), 1, rate)))
    }
}
