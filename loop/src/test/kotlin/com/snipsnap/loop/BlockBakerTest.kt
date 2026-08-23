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
        val long = dc((s.intervalFrames * 1.01).toInt(), 0.5f)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to long)))
        assertEquals(s.intervalFrames, baked.frameCount)
    }

    @Test
    fun `resamples a loop recorded at a different rate`() {
        val s = session(rate = 48_000)
        // Same musical length, wrong rate: 10.667 s at 44.1 kHz.
        val at441 = dc((10.6667 * 44_100).toInt(), 0.5f, rate = 44_100)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to at441)))

        assertEquals(48_000, baked.sampleRate)
        assertEquals(s.intervalFrames, baked.frameCount)
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
    }
}
