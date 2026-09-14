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
     * Upper bound on the size of the final merged buffer a single [render]
     * call will allocate, in bytes.
     *
     * [render] holds the *entire* rendered cycle as one in-memory buffer
     * before handing it back. A cycle is the least common multiple of the
     * chain lengths, which grows fast — chains of 5, 7, 8 and 3 (all legal:
     * [Session.MAX_CHAIN] is 8) give 840 intervals, and at roughly 4 MB per
     * interval that is gigabytes, an OOM with no useful message. This bound
     * is checked against that final array's size alone; actual peak memory
     * is roughly double it, since the per-interval chunks (in `BufferSink`)
     * are still alive while the merged array is being filled. 512 MB — so a
     * peak near 1 GB — is comfortably above any real bounce (a single
     * track's worth of intervals, or a handful of bars looped) while still
     * catching any render whose total exceeds the bound, with margin left
     * before it threatens typical JVM heap sizes. A render that hits this
     * must go through [toSink] in smaller batches against a streaming
     * [AudioSink] instead — [render] is not the only way to reach a sink;
     * see [WavSink]'s KDoc for the one that deliberately isn't guarded.
     */
    const val MAX_RENDER_BYTES = 512L * 1024 * 1024

    /**
     * How many whole intervals to bounce so the result fits in [maxSeconds]:
     * the whole cycle when it fits, and as many whole intervals as do when it
     * does not — never fewer than one.
     *
     * A cycle is the least common multiple of the chain lengths, so it grows
     * in jumps rather than steps: chains of 5, 7, 8 and 3 give 840 intervals,
     * which is over half an hour of audio at an ordinary tempo. Nothing in the
     * app has anywhere to put that — a bounce lands as a snip, and
     * `SnipStore.import` cuts one at its own ceiling — so the choice is
     * between refusing such a grid outright and bouncing a stated part of it.
     * This is the second: the caller renders [intervalsWithin] intervals and
     * says how many bars that is against how many the whole cycle runs, the
     * same shape a snip too long for a chain is already reported with.
     *
     * Whole intervals, never a fraction of one: every block bakes to exactly
     * one interval, and a bounce that stopped mid-interval would end on a cut
     * waveform — a click on a file whose whole purpose is to be looped again.
     */
    fun intervalsWithin(session: Session, maxSeconds: Float): Int {
        require(maxSeconds > 0f) { "maxSeconds must be positive: $maxSeconds" }
        val intervalSeconds = session.intervalFrames.toDouble() / session.sampleRate
        val fits = (maxSeconds / intervalSeconds).toInt()
        return fits.coerceIn(1, Arrangement.cycleIntervals(session))
    }

    /**
     * Render [intervals] of [session], or one full phase cycle when 0.
     *
     * A cycle is the least common multiple of the chain lengths, which grows
     * fast — chains of 5, 7, 8 and 3 give 840 intervals. Call
     * [Arrangement.cycleIntervals] and show the number before rendering one.
     *
     * Throws [IllegalArgumentException] rather than let the JVM OOM when the
     * requested render would exceed [MAX_RENDER_BYTES]; see that constant.
     */
    fun render(session: Session, source: SampleSource, intervals: Int = 0): Snip {
        val cycle = Arrangement.cycleIntervals(session)
        val count = if (intervals > 0) intervals else cycle
        val bytesPerInterval = session.intervalFrames.toLong() * 2 * Float.SIZE_BYTES
        val totalBytes = bytesPerInterval * count
        require(totalBytes <= MAX_RENDER_BYTES) {
            "render is too large to buffer in memory: cycle length $cycle, " +
                "$count intervals requested, ${totalBytes / (1024 * 1024)} MB " +
                "(limit ${MAX_RENDER_BYTES / (1024 * 1024)} MB). " +
                "Render in smaller batches via Bouncer.toSink against a streaming AudioSink instead."
        }
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
 * finished Snip. That means the whole *cycle* sits in memory at once, not
 * just one interval — a single interval is roughly 4 MB at 4 bars and
 * 90 BPM, fine on its own, but a full cycle multiplies that by the interval
 * count, which [Arrangement.cycleIntervals] can put in the hundreds.
 *
 * `WavSink` is reached only through [Bouncer.toSink], never through
 * [Bouncer.render] — `render` builds its own private in-memory sink and
 * never touches this class. [Bouncer.toSink] is the deliberate escape hatch
 * for a render too large for `render`'s [Bouncer.MAX_RENDER_BYTES] bound
 * (batch it across multiple `toSink` calls against a streaming
 * [AudioSink]), so `toSink` itself is intentionally left unguarded — which
 * also means a caller pointing `toSink` at a `WavSink` for a very large
 * interval count is buffering that whole count in memory here, unchecked,
 * and is responsible for keeping it sane. Fine for a bounce of reasonable
 * length, which is not something you do sixty times a second; a
 * pathological chain configuration handed to `render`'s default is caught
 * before it ever gets this far, but nothing stops the same thing being fed
 * to a `WavSink` directly via `toSink`.
 *
 * Implements [Closeable] to support `use {}`, so [close] must be safe to
 * call more than once — the second call is a no-op rather than clearing
 * [chunks] and overwriting a good file with a 0-sample WAV.
 */
class WavSink(
    private val file: File,
    override val sampleRate: Int,
    private val depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24,
) : AudioSink, Closeable {

    override val channels = 2
    private val chunks = ArrayList<FloatArray>()
    private var closed = false

    override fun write(block: FloatArray) { chunks.add(block.copyOf()) }

    override fun close() {
        if (closed) return
        closed = true
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (c in chunks) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
        // allowNonMpcRate: a bounce follows the device rate, not the MPC's.
        WavWriter.write(file, Snip(out, 2, sampleRate), depth, allowNonMpcRate = true)
        chunks.clear()
    }
}
