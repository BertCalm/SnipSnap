package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockBakerTest {

    /** Map-backed source: baking should never need a file on disk. */
    private class FakeSource(
        private val loops: Map<String, Snip> = emptyMap(),
        private val pads: Map<Pair<String, Int>, Snip> = emptyMap(),
    ) : SampleSource {
        override fun loop(sampleFile: String): Snip? = loops[sampleFile]
        override fun pad(kit: String, slot: Int): Snip? = pads[kit to slot]
    }

    private fun session(bpm: Float = 90f, bars: Int = 4, rate: Int = 48_000) =
        Session(
            tracks = List(6) { Track("t$it", listOf(LoopBlock("a.wav"))) },
            bpm = bpm,
            barsPerInterval = bars,
            sampleRate = rate,
        )

    private fun dc(frames: Int, level: Float, channels: Int = 2, rate: Int = 48_000) =
        Snip(FloatArray(frames * channels) { level }, channels, rate)

    /**
     * Rises linearly from 0 at frame 0 toward 1 at the end, so trimming from
     * the head and trimming from the tail produce measurably different
     * output. A constant fixture like [dc] cannot tell the two apart: with
     * every frame holding the same value, head-truncation and tail-truncation
     * are byte-identical no matter what gets asserted about them.
     */
    private fun ramp(frames: Int, channels: Int = 2, rate: Int = 48_000): Snip {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = f.toFloat() / frames
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return Snip(out, channels, rate)
    }

    @Test
    fun `bakes a loop that is already exactly one interval`() {
        val s = session()
        val source = FakeSource(loops = mapOf("a.wav" to dc(s.intervalFrames, 0.5f)))
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, source)

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(48_000, baked.sampleRate)
        assertTrue(abs(baked.samples[1000] - 0.5f) < 1e-6f, "audio should pass through untouched")
    }

    @Test
    fun `pads a loop that is a little short`() {
        val s = session()
        // 1% short: inside the 2% tolerance, so trim/pad rather than slice.
        val short = dc((s.intervalFrames * 0.99).toInt(), 0.5f)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to short)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(abs(baked.samples[1000] - 0.5f) < 1e-6f, "head should survive")
        assertEquals(0f, baked.samples[baked.samples.size - 1], "tail should be silence")
    }

    @Test
    fun `trims a loop that is a little long`() {
        val s = session()
        val longFrames = (s.intervalFrames * 1.01).toInt()
        val long = ramp(longFrames)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to long)))
        assertEquals(s.intervalFrames, baked.frameCount)

        // A constant fixture can't distinguish which end got trimmed; this one
        // does. The ramp starts at 0, so the retained head should still start
        // near 0 — not near the source's tail value (close to 1).
        assertTrue(baked.samples[0] < 0.001f, "trim should keep the head (near-zero start), not the tail")
        val expectedLast = (s.intervalFrames - 1).toFloat() / longFrames
        assertTrue(
            abs(baked.samples[baked.samples.size - 2] - expectedLast) < 1e-4f,
            "last retained frame should land where the head ends, not where the source ends",
        )
    }

    @Test
    fun `resamples a loop recorded at a different rate`() {
        val s = session(rate = 48_000)
        // Same musical length, wrong rate: 10.667 s at 44.1 kHz.
        val at441 = ramp((10.6667 * 44_100).toInt(), rate = 44_100)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to at441)))

        assertEquals(48_000, baked.sampleRate)
        assertEquals(s.intervalFrames, baked.frameCount)

        // Resample and conform both run on this path; a constant fixture
        // could not show whether the head or the tail of the source survived.
        // This one can: the ramp's low end should still be at the front.
        assertTrue(baked.samples[0] < 0.1f, "resample+conform should keep the head, not the tail")
        assertTrue(
            baked.samples[0] < baked.samples[baked.samples.size - 2],
            "the ramp's rising shape should still be rising",
        )
    }

    @Test
    fun `bakes silence when the sample is missing`() {
        val s = session()
        val baked = BlockBaker.bake(LoopBlock("gone.wav"), s, FakeSource())
        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(0f, baked.peak(), "a missing sample is silence, not a crash")
    }

    @Test
    fun `always returns stereo so the mixer never has to branch`() {
        val s = session()
        val mono = dc(s.intervalFrames, 0.5f, channels = 1)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to mono)))
        assertEquals(2, baked.channels)
        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(0.5f, baked.samples[1], "right channel should carry the same mono signal as the left")
    }
}
