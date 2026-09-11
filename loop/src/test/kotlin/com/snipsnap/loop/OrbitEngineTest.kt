package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitEngineTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000 // frames per 16th at 120 BPM, 48 kHz

    /**
     * Pads are single-sample clicks whose height encodes the slot (slot 1 =
     * 0.1, slot 2 = 0.2), so the rendered audio says which pad fired and
     * exactly on which frame. Loops are a ramp so a wrap is visible.
     */
    private class ClickSource(private val loopFrames: Int = 0) : SampleSource {
        override fun loop(sampleFile: String): Snip? {
            if (loopFrames == 0) return null
            return Snip(FloatArray(loopFrames) { it.toFloat() / loopFrames }, 1, 48_000)
        }
        override fun pad(kit: String, slot: Int): Snip? =
            if (kit == "kit" && slot in 1..9) Snip(floatArrayOf(slot / 10f), 1, 48_000) else null
    }

    private fun pattern(name: String, steps: Int, lock: Boolean, vararg hits: Pair<Int, Int>) =
        Orbit(name, steps, PatternOrbit("kit", hits.map { (s, slot) -> OrbitHit(s, slot) }), span = if (lock) OrbitSpan.ONE else OrbitSpan.FREE)

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), bpm, rate)

    private fun render(s: OrbitSet, frames: Int, source: SampleSource = ClickSource()): FloatArray =
        OrbitEngine.render(s, OrbitBank.prepare(s, source), frames).samples

    /** Frames on which the left channel is non-zero, with their values. */
    private fun clicks(samples: FloatArray): List<Pair<Int, Float>> =
        (0 until samples.size / 2).filter { samples[it * 2] != 0f }.map { it to samples[it * 2] }

    @Test
    fun `a hit lands on exactly its frame - across block boundaries`() {
        // Block is 2048 frames; step 3 fires at frame 18,000, well inside
        // block 8 and nowhere near a boundary; step 0 sits on one.
        val s = set(pattern("a", 16, false, 0 to 1, 3 to 2))
        val out = render(s, 16 * step)
        assertEquals(listOf(0 to 0.1f, 3 * step to 0.2f), clicks(out))
    }

    @Test
    fun `free rings - 4 against 5 drift and realign after five bars`() {
        val s = set(
            pattern("four", 16, false, 0 to 1),
            pattern("five", 20, false, 0 to 2),
        )
        val out = render(s, 80 * step + 1)
        val frames = clicks(out)
        val fours = frames.filter { abs(it.second - 0.1f) < 1e-6 || abs(it.second - 0.3f) < 1e-6 }.map { it.first }
        val fives = frames.filter { abs(it.second - 0.2f) < 1e-6 || abs(it.second - 0.3f) < 1e-6 }.map { it.first }
        assertEquals((0..5).map { it * 16 * step }, fours)
        assertEquals((0..4).map { it * 20 * step }, fives)
        // Both on the same frame only at 0 and at the cycle's end (their sum, 0.3).
        assertEquals(listOf(0, 80 * step), frames.filter { abs(it.second - 0.3f) < 1e-6 }.map { it.first })
    }

    @Test
    fun `locked ring - a 3-step ring plays a triplet inside the bar`() {
        val s = set(pattern("three", 3, true, 0 to 1, 1 to 1, 2 to 1))
        val lap = 16 * step
        val out = render(s, lap)
        val frames = clicks(out).map { it.first }
        assertEquals(listOf(0, Math.round(lap / 3.0).toInt(), Math.round(2 * lap / 3.0).toInt()), frames)
    }

    @Test
    fun `locking a ring to the bar changes only its period`() {
        val free = set(pattern("r", 4, false, 0 to 1))
        val locked = set(pattern("r", 4, true, 0 to 1))
        val lap = 16 * step
        assertEquals(listOf(0, 4 * step, 8 * step, 12 * step), clicks(render(free, lap)).map { it.first })
        assertEquals(listOf(0), clicks(render(locked, lap)).map { it.first })
    }

    @Test
    fun `a disengaged ring is silent and a missing pad is skipped not thrown`() {
        val s = set(
            pattern("off", 16, false, 0 to 1).copy(engaged = false),
            pattern("ghost", 16, false, 0 to 9).copy(content = PatternOrbit("nokit", listOf(OrbitHit(0, 1)))),
        )
        assertTrue(clicks(render(s, 16 * step)).isEmpty())
    }

    @Test
    fun `velocity level and pan scale the voice`() {
        val ring = Orbit("r", 16, PatternOrbit("kit", listOf(OrbitHit(0, 1, velocity = 0.5f))), level = 0.5f, pan = 1f)
        val out = render(set(ring), 16)
        // 0.1 * 0.5 velocity * 0.5 level = 0.025 on the right; hard-right pan mutes the left.
        assertEquals(0f, out[0])
        assertTrue(abs(out[1] - 0.025f) < 1e-6f, "right was ${out[1]}")
    }

    @Test
    fun `a snip ring wraps at its period and the seam is where the ramp restarts`() {
        // A 20-step snip on a 20-step ring: within tolerance, so it is used as is.
        val period = 20 * step
        val s = set(Orbit("s", 20, SnipOrbit("ramp.wav")))
        val out = render(s, period + 10, ClickSource(loopFrames = period))
        assertEquals(0f, out[0])
        assertTrue(out[(period - 1) * 2] > 0.99f, "just before the seam should be near the ramp's top")
        assertEquals(0f, out[period * 2], "the seam restarts the ramp")
        assertTrue(out[(period + 5) * 2] > 0f)
    }

    @Test
    fun `the bank fits a snip to the ring - a longer ring stretches the loop to its new lap`() {
        // A 16-step snip on a 20-step ring is 25% off: past tolerance, so it
        // is refitted to the ring's period rather than looped short.
        val s = set(Orbit("s", 20, SnipOrbit("ramp.wav")))
        val bank = OrbitBank.prepare(s, ClickSource(loopFrames = 16 * step))
        val fitted = assertNotNull(bank.loop(s, s.orbits[0]))
        assertEquals(20 * step, fitted.frameCount)
        assertEquals(2, fitted.channels)
    }

    @Test
    fun `the bank reuses pads and loops from the previous bank when nothing changed`() {
        val s = set(pattern("r", 16, false, 0 to 1), Orbit("s", 16, SnipOrbit("a.wav")))
        val first = OrbitBank.prepare(s, ClickSource(loopFrames = 16 * step))
        val second = OrbitBank.prepare(s, ClickSource(loopFrames = 16 * step), previous = first)
        assertTrue(first.pad("kit", 1) === second.pad("kit", 1), "pad should be the same object")
        assertTrue(first.loop(s, s.orbits[1]) === second.loop(s, s.orbits[1]), "loop should be the same object")
    }

    @Test
    fun `a tempo change refits a snip ring - the old fit is not reused`() {
        val s = set(Orbit("s", 16, SnipOrbit("a.wav")))
        val faster = s.copy(bpm = 140f)
        val first = OrbitBank.prepare(s, ClickSource(loopFrames = 16 * step))
        val second = OrbitBank.prepare(faster, ClickSource(loopFrames = 16 * step), previous = first)
        val refitted = assertNotNull(second.loop(faster, faster.orbits[0]))
        assertEquals(OrbitClock.periodFrames(faster, faster.orbits[0]).toInt(), refitted.frameCount)
        assertNull(second.loop(s, s.orbits[0]), "the old period's buffer is gone")
    }

    @Test
    fun `apply swaps at a block boundary and rewind returns to the downbeat`() {
        val sink = object : AudioSink {
            override val sampleRate = rate
            override val channels = 2
            val written = ArrayList<FloatArray>()
            override fun write(block: FloatArray) { written.add(block.copyOf()) }
            override fun close() {}
        }
        val a = set(pattern("a", 16, false, 0 to 1))
        val b = set(pattern("b", 16, false, 0 to 2))
        val engine = OrbitEngine(a, OrbitBank.prepare(a, ClickSource()), sink, blockFrames = 1_000)

        engine.runFor(1)
        assertEquals(1_000L, engine.position())
        assertEquals(0.1f, sink.written[0][0])

        engine.apply(OrbitEngine.Prepared(b, OrbitBank.prepare(b, ClickSource())))
        engine.rewind()
        engine.runFor(1)
        // Position restarted at 0 and the new set's downbeat pad (0.2) fired.
        assertEquals(1_000L, engine.position())
        assertEquals(0.2f, sink.written[1][0])
        assertEquals("b", engine.prepared().set.orbits[0].name)
    }

    @Test
    fun `render returns exactly the frames asked for`() {
        val s = set(pattern("a", 16, false, 0 to 1))
        val snip = OrbitEngine.render(s, OrbitBank.prepare(s, ClickSource()), 5_000)
        assertEquals(5_000, snip.frameCount)
        assertEquals(2, snip.channels)
    }
}
