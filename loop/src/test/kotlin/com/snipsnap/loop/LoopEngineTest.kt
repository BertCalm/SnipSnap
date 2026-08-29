package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopEngineTest {

    /** Records every block handed to it, so we can inspect what was played. */
    private class RecordingSink(override val sampleRate: Int = 48_000) : AudioSink {
        override val channels = 2
        val blocks = ArrayList<FloatArray>()
        var closed = false
        override fun write(block: FloatArray) { blocks.add(block.copyOf()) }
        override fun close() { closed = true }
    }

    private class LevelSource(private val frames: Int) : SampleSource {
        override fun loop(sampleFile: String): Snip {
            // Encode the filename's trailing digit as the level, so a recorded
            // block says which block was playing.
            val level = (sampleFile.filter { it.isDigit() }.lastOrNull()?.digitToInt() ?: 0) / 10f
            return Snip(FloatArray(frames * 2) { level }, 2, 48_000)
        }
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val sameThread = Executor { it.run() }

    private fun session(vararg sizes: Int) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-b$it.wav") }, engaged = i == 0)
        },
        bpm = 200f,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    private fun engine(s: Session): Pair<LoopEngine, RecordingSink> {
        val sink = RecordingSink()
        return LoopEngine(Residency(s, LevelSource(s.intervalFrames), sameThread), sink) to sink
    }

    @Test
    fun `writes one block per interval`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(4)

        assertEquals(4, sink.blocks.size)
        for (b in sink.blocks) assertEquals(s.intervalFrames * 2, b.size)
    }

    @Test
    fun `advances the chain between intervals`() {
        // Track 0 is the only engaged one and has two blocks, b1 then b2, which
        // the source turns into levels 0.1 and 0.2.
        val s = session(2, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(4)

        val levels = sink.blocks.map { it[0] }
        assertTrue(abs(levels[0] - 0.1f) < 1e-5f, "interval 0 was ${levels[0]}")
        assertTrue(abs(levels[1] - 0.2f) < 1e-5f, "interval 1 was ${levels[1]}")
        assertTrue(abs(levels[2] - 0.1f) < 1e-5f, "interval 2 should have wrapped, was ${levels[2]}")
    }

    @Test
    fun `reports its position`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, _) = engine(s)
        assertEquals(0, e.position())
        e.runFor(3)
        assertEquals(3, e.position())
    }

    @Test
    fun `reuses one output buffer`() {
        // The audio thread must not allocate. A RecordingSink copies on write,
        // so identical references here prove the engine handed out the same
        // array every interval.
        val s = session(1, 1, 1, 1, 1, 1)
        val sink = object : AudioSink {
            override val sampleRate = 48_000
            override val channels = 2
            val seen = ArrayList<FloatArray>()
            override fun write(block: FloatArray) { seen.add(block) }
            override fun close() {}
        }
        LoopEngine(Residency(s, LevelSource(s.intervalFrames), sameThread), sink).runFor(3)
        assertTrue(sink.seen[0] === sink.seen[1] && sink.seen[1] === sink.seen[2], "engine allocated per block")
    }

    @Test
    fun `an applied edit lands at the next boundary`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(1)

        // Mute everything: the next block must be silent, the one already
        // written must not have changed.
        e.apply(s.copy(tracks = s.tracks.map { it.copy(engaged = false) }))
        e.runFor(1)

        assertTrue(abs(sink.blocks[0][0] - 0.1f) < 1e-5f, "first block was rewritten")
        assertEquals(0f, sink.blocks[1][0], "edit did not take effect")
    }

    @Test
    fun `prefetch for the next interval is submitted before the blocking write`() {
        // Regression guard for the ordering fix: write() blocks for roughly
        // one interval, so prefetch(i + 1) has to be submitted before it, not
        // after -- otherwise the bake pool only gets the device buffer's
        // drain time (tens of milliseconds) instead of the whole blocking
        // write to bake the next interval, which is exactly what caused the
        // audible dropouts this test exists to catch a regression back to.
        val events = mutableListOf<String>()
        val recordingExecutor = Executor { task ->
            events.add("prefetch-submit")
            task.run()
        }
        val sink = object : AudioSink {
            override val sampleRate = 48_000
            override val channels = 2
            override fun write(block: FloatArray) { events.add("write") }
            override fun close() {}
        }
        // Track 0 has two distinct blocks, so prefetching interval 1 actually
        // has something new to submit rather than finding it already cached.
        val s = session(2, 1, 1, 1, 1, 1)
        val residency = Residency(s, LevelSource(s.intervalFrames), recordingExecutor)
        LoopEngine(residency, sink).runFor(1)

        val submitIndex = events.indexOf("prefetch-submit")
        val writeIndex = events.indexOf("write")
        assertTrue(submitIndex in 0 until writeIndex, "prefetch must be submitted before write, was $events")
    }

    @Test
    fun `stop ends a run`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.stop()
        e.runFor(4)
        assertEquals(0, sink.blocks.size, "a stopped engine should write nothing")
    }

    @Test
    fun `keeps the resident window bounded across a long run`() {
        val s = session(8, 7, 5, 3, 2, 1)
        val sink = RecordingSink()
        val residency = Residency(s, LevelSource(s.intervalFrames), sameThread)
        LoopEngine(residency, sink).runFor(24)

        // Two intervals of six tracks is at most twelve distinct blocks.
        assertTrue(residency.residentCount() <= 12, "held ${residency.residentCount()} buffers")
    }
}
