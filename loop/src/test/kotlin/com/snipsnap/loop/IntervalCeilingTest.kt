package com.snipsnap.loop

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.SongBus
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntervalCeilingTest {

    private class Source(
        private val loops: Map<String, Snip> = emptyMap(),
        private val pad: Snip? = null,
    ) : SampleSource {
        override fun loop(sampleFile: String): Snip? = loops[sampleFile]
        override fun pad(kit: String, slot: Int): Snip? = pad
    }

    /** Left at [level], right at minus half of it, so a per-channel slip cannot hide. */
    private fun dc(frames: Int, level: Float) =
        Snip(FloatArray(frames * 2) { if (it % 2 == 0) level else -level / 2f }, 2, 48_000)

    private fun allEngaged(chain: List<Block>, bpm: Float = 200f, rate: Int = 48_000) =
        Session(List(Session.TRACK_COUNT) { Track("t$it", chain) }, bpm, 1, rate)

    /** What the grid summed to before the ceiling: the same bake and the same mix, nothing after. */
    private fun rawMix(s: Session, source: SampleSource, interval: Int): FloatArray {
        val out = FloatArray(s.intervalFrames * 2)
        Mixer.mix(s.tracks.map { BlockBaker.bake(Arrangement.blockAt(it, interval), s, source) }, s.tracks, out)
        return out
    }

    @Test
    fun `six engaged kick tracks bounce under the ceiling`() {
        // The review's measured case: the same kick on every track, peak 5.1
        // summed, straight into WavWriter's clamp.
        val source = Source(pad = DrumSynth.kick())
        val s = allEngaged(listOf(PatternBlock("k", listOf(Step(0, 1), Step(8, 1)))), bpm = 120f, rate = 44_100)
        val raw = SongBus.peakOf(rawMix(s, source, 0))
        assertTrue(raw > 4f, "the fixture must actually sum past full scale: $raw")

        val out = Bouncer.render(s, source, intervals = 2)
        val peak = SongBus.peakOf(out.samples)
        assertTrue(peak <= SongBus.PEAK + 1e-6f, "bounce peak $peak over ${SongBus.PEAK}")
        assertTrue(peak > SongBus.PEAK - 1e-3f, "turned down to the ceiling, not past it: $peak")
    }

    @Test
    fun `a session under the ceiling bounces to the raw mix byte for byte`() {
        val s = allEngaged(listOf(LoopBlock("a.wav")))
        val source = Source(mapOf("a.wav" to dc(s.intervalFrames, 0.1f)))
        val out = Bouncer.render(s, source, intervals = 3)
        val one = rawMix(s, source, 0)
        assertContentEquals(one + one + one, out.samples)
    }

    @Test
    fun `playback and bounce apply the same ceiling`() {
        // Loud, quiet, loud, quiet: every interval either cuts or eases back,
        // so both branches run on both paths.
        val chain = listOf(LoopBlock("loud.wav"), LoopBlock("quiet.wav"))
        val s = allEngaged(chain)
        val source = Source(
            mapOf("loud.wav" to dc(s.intervalFrames, 0.5f), "quiet.wav" to dc(s.intervalFrames, 0.05f)),
        )
        val recorded = ArrayList<FloatArray>()
        val sink = object : AudioSink {
            override val sampleRate = 48_000
            override val channels = 2
            override fun write(block: FloatArray) { recorded += block.copyOf() }
            override fun close() {}
        }
        LoopEngine(Residency(s, source, Executor { it.run() }), sink).runFor(4)

        val played = recorded.fold(FloatArray(0)) { acc, b -> acc + b }
        val bounced = Bouncer.render(s, source, intervals = 4).samples
        assertContentEquals(bounced, played, "a bounce is what was heard, ceiling included")
        assertTrue(SongBus.peakOf(played) <= SongBus.PEAK + 1e-6f, "peak ${SongBus.peakOf(played)}")
    }

    @Test
    fun `a cut lands at the boundary and a rise eases back over the ramp`() {
        val rate = 48_000
        val frames = 4_800
        val ramp = (IntervalCeiling.RAMP_MS * rate / 1000f).toInt()
        val ceiling = IntervalCeiling()
        fun block(level: Float) = FloatArray(frames * 2) { level }

        // Too loud: the whole interval, first frame included, sits on the ceiling.
        val loud = block(2f).also { ceiling.apply(it, rate) }
        assertEquals(SongBus.PEAK / 2f, ceiling.gain, 1e-7f)
        assertEquals(SongBus.PEAK, loud[0], 1e-6f)
        assertTrue(loud.all { abs(it - SongBus.PEAK) < 1e-6f }, "a cut is one gain across the interval")

        // Quiet again: the gain climbs from where it was, never past unity, and holds after the ramp.
        val quiet = block(0.5f).also { ceiling.apply(it, rate) }
        assertTrue(quiet[0] < 0.5f * 0.5f, "the first frame starts near the old gain, not at unity: ${quiet[0]}")
        for (f in 1 until ramp) assertTrue(quiet[f * 2] >= quiet[(f - 1) * 2], "the ramp only rises (frame $f)")
        for (f in ramp until frames) assertEquals(0.5f, quiet[f * 2], "held at unity after the ramp (frame $f)")
        assertEquals(1f, ceiling.gain)

        // Loud straight after: the cut is immediate, so the downbeat frame is already under.
        val again = block(3f).also { ceiling.apply(it, rate) }
        assertEquals(SongBus.PEAK, again[0], 1e-6f)
    }

    @Test
    fun `quiet after quiet is not touched at all`() {
        val ceiling = IntervalCeiling()
        val block = FloatArray(200) { (it % 7) * 0.1f - 0.3f }
        val before = block.copyOf()
        ceiling.apply(block, 48_000)
        ceiling.apply(block, 48_000)
        assertContentEquals(before, block)
        assertEquals(1f, ceiling.gain)
    }
}
