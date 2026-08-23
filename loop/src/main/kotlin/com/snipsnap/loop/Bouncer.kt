package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.Closeable
import java.io.File

/**
 * Renders a session offline.
 *
 * Deliberately the same path as playback: bake the blocks for an interval, mix
 * them, hand the block to a sink. Only the sink differs. If bounce and playback
 * ever disagree, it is a bug in one sink, not two engines that drifted.
 */
object Bouncer {

    /**
     * Render [intervals] of [session], or one full phase cycle when 0.
     *
     * A cycle is the least common multiple of the chain lengths, which grows
     * fast — chains of 5, 7, 8 and 3 give 840 intervals. Call
     * [Arrangement.cycleIntervals] and show the number before rendering one.
     */
    fun render(session: Session, source: SampleSource, intervals: Int = 0): Snip {
        val count = if (intervals > 0) intervals else Arrangement.cycleIntervals(session)
        val sink = BufferSink(session.sampleRate)
        toSink(session, source, sink, count)
        return sink.toSnip()
    }

    fun toSink(session: Session, source: SampleSource, sink: AudioSink, intervals: Int) {
        require(intervals > 0) { "intervals must be positive: $intervals" }
        val block = FloatArray(session.intervalFrames * 2)

        // Bake once per distinct block rather than once per interval: a 1-block
        // track on an 840-interval cycle would otherwise be baked 840 times.
        val cache = HashMap<Block, Snip>()

        for (i in 0 until intervals) {
            val buffers = session.tracks.map { track ->
                val b = Arrangement.blockAt(track, i)
                cache.getOrPut(b) { BlockBaker.bake(b, session, source) }
            }
            Mixer.mix(buffers, session.tracks, block)
            sink.write(block)
        }
    }

    /** Accumulates blocks in memory so [render] can hand back a Snip. */
    private class BufferSink(override val sampleRate: Int) : AudioSink {
        override val channels = 2
        private val chunks = ArrayList<FloatArray>()

        override fun write(block: FloatArray) { chunks.add(block.copyOf()) }
        override fun close() {}

        fun toSnip(): Snip {
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var at = 0
            for (c in chunks) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
            return Snip(out, 2, sampleRate)
        }
    }
}

/**
 * A sink that writes a WAV.
 *
 * Buffers the whole render and writes on [close] because WavWriter takes a
 * finished Snip. A full cycle at 4 bars and 90 BPM is roughly 4 MB per
 * interval — fine for a bounce, which is not something you do sixty times a
 * second.
 */
class WavSink(
    private val file: File,
    override val sampleRate: Int,
    private val depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24,
) : AudioSink, Closeable {

    override val channels = 2
    private val chunks = ArrayList<FloatArray>()

    override fun write(block: FloatArray) { chunks.add(block.copyOf()) }

    override fun close() {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (c in chunks) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
        // allowNonMpcRate: a bounce follows the device rate, not the MPC's.
        WavWriter.write(file, Snip(out, 2, sampleRate), depth, allowNonMpcRate = true)
        chunks.clear()
    }
}
