package com.snipsnap.loop

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Snip
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Patterns on the grid carry across their boundaries: tails ring on, early
 * hits start early, and chokes reach the bar before
 * (docs/plans/2026-10-30-song-making.md, phase 3).
 */
class PatternTailTest {

    private val rate = 48_000

    private class Source(
        private val pads: Map<Int, Snip>,
        private val groups: Map<Int, Int> = emptyMap(),
    ) : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? = pads[slot]
        override fun muteGroups(kit: String): Map<Int, Int> = groups
    }

    /** A stereo sine, a sound with no seam of its own to hide one in. */
    private fun tone(seconds: Double, hz: Double = 440.0, level: Float = 0.3f): Snip {
        val frames = (seconds * rate).toInt()
        return Snip(FloatArray(frames * 2) { level * sin(2 * PI * hz * (it / 2) / rate).toFloat() }, 2, rate)
    }

    /** A constant stereo level, so the first sample a hit writes is easy to find. */
    private fun dc(frames: Int, level: Float) = Snip(FloatArray(frames * 2) { level }, 2, rate)

    /** One engaged track playing [chain], five silent ones; one bar at 120 BPM is two seconds. */
    private fun session(chain: List<Block>) = Session(
        listOf(Track("t", chain)) + List(5) { Track("s$it", listOf(SilenceBlock)) },
        bpm = 120f, barsPerInterval = 1, sampleRate = rate,
    )

    private fun stepFrame(s: Session, step: Int) = (step * (s.intervalFrames.toDouble() / s.stepsPerInterval)).roundToInt()

    @Test
    fun `a hit ringing past the bar carries into the next pattern without a seam`() {
        // Measured before this: an open hat on the last 16th lost its last
        // 375 ms at the bar line and left a 0.27 step there, every loop.
        val hit = tone(0.3)
        val s = session(listOf(PatternBlock("k", listOf(Step(15, 1))), PatternBlock("k", emptyList())))
        val out = Bouncer.render(s, Source(mapOf(1 to hit)), intervals = 2)

        // The same hit placed once on a plain timeline: what two intervals of
        // this chain should be, sample for sample.
        val expected = FloatArray(s.intervalFrames * 2 * 2)
        val at = stepFrame(s, 15)
        for (i in hit.samples.indices) expected[at * 2 + i] += hit.samples[i]
        for (i in expected.indices) {
            assertEquals(expected[i], out.samples[i], 1e-6f, "sample $i (the boundary is at ${s.intervalFrames * 2})")
        }
    }

    @Test
    fun `a pattern heard alone fades at its end instead of cutting`() {
        // No next pattern to carry it: the hit still cannot run on, but it
        // ramps out over TAIL_FADE_MS rather than stopping on live audio.
        val s = session(listOf(SilenceBlock))
        val baked = BlockBaker.bake(PatternBlock("k", listOf(Step(15, 1))), s, Source(mapOf(1 to dc(rate, 0.5f))))
        assertEquals(s.intervalFrames, baked.frameCount)
        val last = baked.samples[(s.intervalFrames - 1) * 2]
        assertTrue(abs(last) < 0.5f / 100, "the last frame is inside the fade, not at full level: $last")
        assertEquals(0.5f, baked.samples[(s.intervalFrames - 200) * 2], 1e-6f, "full level until the fade begins")
    }

    @Test
    fun `a hit nudged early on the downbeat starts in the interval before it`() {
        val s = session(listOf(PatternBlock("k", emptyList()), PatternBlock("k", listOf(Step(0, 1, microOffset = -200)))))
        val out = Bouncer.render(s, Source(mapOf(1 to dc(1_000, 0.5f))), intervals = 2)
        val first = (0 until out.frameCount).first { out.samples[it * 2] != 0f }
        assertEquals(s.intervalFrames - 200, first, "the hit's head belongs to the end of the bar before")
        assertEquals(0.5f, out.samples[(s.intervalFrames + 100) * 2], 1e-6f, "and it plays on through the downbeat")
    }

    /** Left-channel energy of [snip] from [from] frames for [frames] frames. */
    private fun energy(snip: Snip, from: Int, frames: Int): Double {
        var acc = 0.0
        for (f in from until minOf(from + frames, snip.frameCount)) acc += snip.samples[f * 2] * snip.samples[f * 2].toDouble()
        return acc
    }

    @Test
    fun `a closed hat chokes the previous pattern's open hat across the bar line`() {
        // Open hat (slot 1) on the last 16th, ringing half a second; closed hat
        // (slot 2, ten milliseconds) on the next pattern's downbeat. Same group,
        // same kit: once the closed hat and the choke fade are done, nothing.
        val pads = mapOf(1 to dc(rate / 2, 0.3f), 2 to dc(rate / 100, 0.3f))
        val s = session(listOf(PatternBlock("k", listOf(Step(15, 1))), PatternBlock("k", listOf(Step(0, 2)))))
        val quietFrom = s.intervalFrames + rate / 100 + AutoPlace.CHOKE_FADE

        val grouped = Bouncer.render(s, Source(pads, groups = mapOf(1 to 1, 2 to 1)), intervals = 2)
        assertEquals(0.0, energy(grouped, quietFrom, rate / 10), "the open hat stops at the closed hat")

        // The control: the same chain with no choke group still rings there.
        val ungrouped = Bouncer.render(s, Source(pads), intervals = 2)
        assertTrue(energy(ungrouped, quietFrom, rate / 10) > 1.0, "the window hears an unchoked open hat")
    }

    @Test
    fun `inside one pattern a closed hat chokes its own open hat`() {
        val pads = mapOf(1 to dc(rate, 0.3f), 2 to dc(rate / 100, 0.3f))
        val s = session(listOf(PatternBlock("k", listOf(Step(0, 1), Step(4, 2)))))
        val baked = BlockBaker.bake(Arrangement.neighbourhoodAt(s.tracks[0], 0), s, Source(pads, groups = mapOf(1 to 1, 2 to 1)))
        val quietFrom = stepFrame(s, 4) + rate / 100 + AutoPlace.CHOKE_FADE
        assertEquals(0.0, energy(baked, quietFrom, rate / 10), "the open hat stops at the closed hat")
    }

    @Test
    fun `playback and bounce agree on a chain that carries`() {
        // Tails, an early hit and a choke all crossing boundaries, through the
        // live engine and the bounce: the same audio, sample for sample.
        val pads = mapOf(1 to tone(0.4), 2 to dc(rate / 100, 0.2f))
        val s = session(
            listOf(
                PatternBlock("k", listOf(Step(14, 1), Step(15, 1))),
                PatternBlock("k", listOf(Step(0, 2, microOffset = -300), Step(8, 1))),
                PatternBlock("k", listOf(Step(15, 1))),
            ),
        )
        val source = Source(pads, groups = mapOf(1 to 1, 2 to 1))
        val recorded = ArrayList<FloatArray>()
        val sink = object : AudioSink {
            override val sampleRate = rate
            override val channels = 2
            override fun write(block: FloatArray) { recorded += block.copyOf() }
            override fun close() {}
        }
        LoopEngine(Residency(s, source, Executor { it.run() }), sink).runFor(6)

        val played = recorded.fold(FloatArray(0)) { acc, b -> acc + b }
        assertContentEquals(Bouncer.render(s, source, intervals = 6).samples, played)
    }
}
