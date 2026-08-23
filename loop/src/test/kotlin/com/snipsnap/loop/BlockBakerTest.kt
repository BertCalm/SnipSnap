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

    /** Four evenly spaced clicks: unambiguous onsets for the detector. */
    private fun clicks(frames: Int, count: Int, rate: Int = 48_000): Snip {
        val out = FloatArray(frames * 2)
        val every = frames / count
        for (c in 0 until count) {
            val at = c * every
            for (i in 0 until 600) {
                val f = at + i
                if (f >= frames) break
                // Short decaying burst — a transient the onset detector will find.
                val v = (1f - i / 600f) * if (i % 3 == 0) 0.9f else -0.9f
                out[f * 2] = v
                out[f * 2 + 1] = v
            }
        }
        return Snip(out, 2, rate)
    }

    @Test
    fun `slices and re-places a loop that is far too long`() {
        val s = session()
        // 40% too long: well outside tolerance, so this must be sliced.
        val tooLong = clicks((s.intervalFrames * 1.4).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooLong)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(2, baked.channels)
        assertTrue(baked.peak() > 0.3f, "slices should still be audible, peak was ${baked.peak()}")
    }

    @Test
    fun `slices and re-places a loop that is far too short`() {
        val s = session()
        val tooShort = clicks((s.intervalFrames * 0.6).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooShort)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(baked.peak() > 0.3f, "slices should still be audible, peak was ${baked.peak()}")
    }

    @Test
    fun `keeps the first hit at the start of the interval`() {
        val s = session()
        val tooLong = clicks((s.intervalFrames * 1.4).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooLong)))

        // Downbeat integrity: whatever else fitting does, the first slice must
        // land at or near frame 0 or every loop starts late.
        var firstLoud = -1
        for (f in 0 until baked.frameCount) {
            if (abs(baked.samples[f * 2]) > 0.2f) { firstLoud = f; break }
        }
        assertTrue(firstLoud in 0..2_000, "first hit landed at frame $firstLoud")
    }

    @Test
    fun `falls back to trim and pad when there are no transients to slice on`() {
        val s = session()
        // A steady tone 40% too long has no onsets; slicing has nothing to work
        // with and it must not return silence.
        val tone = Snip(
            FloatArray((s.intervalFrames * 1.4).toInt() * 2) { 0.4f },
            2,
            48_000,
        )
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tone)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(baked.peak() > 0.3f, "fallback must not produce silence")
    }

    /**
     * Frame at the start of each contiguous loud region in a mostly-silent
     * buffer. A click's envelope in [clicks] is strictly decaying in
     * magnitude, so a simple threshold crossing (no hysteresis) gives one
     * clean start per burst rather than chattering mid-decay.
     */
    private fun loudRegionStarts(snip: Snip, threshold: Float = 0.2f): List<Int> {
        val starts = mutableListOf<Int>()
        var loud = false
        for (f in 0 until snip.frameCount) {
            val v = abs(snip.samples[f * snip.channels])
            if (v > threshold) {
                if (!loud) starts += f
                loud = true
            } else {
                loud = false
            }
        }
        return starts
    }

    @Test
    fun `scales slice positions to the target grid, not just the first hit`() {
        val s = session()
        // clicks() places hit k at k * (srcFrames / 4). Correct scaling maps
        // that to k * (srcFrames / 4) * (target / srcFrames) = k * (target / 4)
        // regardless of source length, so one expected set covers both a
        // too-long and a too-short source: proof the fit is scale-independent,
        // not a coincidence of one ratio.
        val expected = listOf(0, 128_000, 256_000, 384_000)

        for (factor in listOf(1.4, 0.6)) {
            val src = clicks((s.intervalFrames * factor).toInt(), count = 4)
            val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to src)))

            val starts = loudRegionStarts(baked)
            assertEquals(4, starts.size, "expected 4 loud regions at factor $factor, got $starts")
            for ((i, start) in starts.withIndex()) {
                assertTrue(
                    abs(start - expected[i]) <= 2_000,
                    "hit $i at factor $factor landed at frame $start, expected near ${expected[i]}",
                )
            }
        }
    }

    /**
     * Left/right channels differ by default (right is left's negative half),
     * so a bug that drops, swaps, or zeroes a channel is never invisible to a
     * test that only bothers to check one index. Pass `right` explicitly to
     * override; every existing call site relies on the default and only ever
     * asserts on the left (even) channel, so the default costs them nothing.
     */
    private fun blip(frames: Int, level: Float, right: Float = -level / 2f) =
        Snip(FloatArray(frames * 2) { if (it % 2 == 0) level else right }, 2, 48_000)

    @Test
    fun `renders a pattern block to one interval`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        val block = PatternBlock("kit", listOf(Step(step = 0, slot = 1)))
        val baked = BlockBaker.bake(block, s, src)

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(2, baked.channels)
        assertTrue(abs(baked.samples[0] - 0.5f) < 1e-6f, "hit should land on frame 0")
        assertTrue(abs(baked.samples[1] - -0.25f) < 1e-6f, "right channel should carry its own value, not be silenced")
    }

    @Test
    fun `places a step at its sixteenth of the interval`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        // 64 steps in a 4-bar interval; step 32 is the halfway point.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(32, 1))), s, src)

        val expected = s.intervalFrames / 2
        assertTrue(abs(baked.samples[expected * 2] - 0.5f) < 1e-6f, "no hit at the midpoint")
        assertEquals(0f, baked.samples[0], "nothing should be on the downbeat")
    }

    @Test
    fun `scales a hit by its velocity`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.8f)))
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 1, velocity = 0.5f))), s, src)
        assertTrue(abs(baked.samples[0] - 0.4f) < 1e-6f, "got ${baked.samples[0]}")
        assertTrue(abs(baked.samples[1] - -0.2f) < 1e-6f, "right channel should be scaled by velocity too, got ${baked.samples[1]}")
    }

    @Test
    fun `sums overlapping hits`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.3f)))
        val baked = BlockBaker.bake(
            PatternBlock("kit", listOf(Step(0, 1), Step(0, 1))),
            s,
            src,
        )
        assertTrue(abs(baked.samples[0] - 0.6f) < 1e-6f, "got ${baked.samples[0]}")
    }

    @Test
    fun `applies a micro offset`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(500, 0.5f)))
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 1, microOffset = 480))), s, src)

        assertEquals(0f, baked.samples[0], "nudged hits should not be on the grid")
        assertTrue(abs(baked.samples[480 * 2] - 0.5f) < 1e-6f, "hit should be 480 frames late")
        assertTrue(
            abs(baked.samples[480 * 2 + 1] - -0.25f) < 1e-6f,
            "right channel should also be nudged 480 frames late",
        )
    }

    @Test
    fun `skips a step whose pad is missing`() {
        val s = session()
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 99))), s, FakeSource())
        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(0f, baked.peak(), "a missing pad is silence, not a crash")
    }

    @Test
    fun `clips a hit that would run past the end of the interval`() {
        val s = session()
        val long = blip(s.intervalFrames, 0.5f)
        val src = FakeSource(pads = mapOf(("kit" to 1) to long))
        // Last 16th: most of this pad has nowhere to go.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(63, 1))), s, src)
        assertEquals(s.intervalFrames, baked.frameCount)
    }

    @Test
    fun `ignores a step beyond the interval's resolution`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        // 64 steps exist; step 64 is off the end.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(64, 1))), s, src)
        assertEquals(0f, baked.peak())
    }
}
