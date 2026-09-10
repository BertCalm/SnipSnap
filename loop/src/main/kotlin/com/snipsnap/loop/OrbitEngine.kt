package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Plays a set of rings.
 *
 * One fixed-size block per turn: figure out which hits fall inside it, start
 * a voice for each, add every sounding voice and every snip ring into the
 * block, write. The block is small (about 43 ms at 48 kHz) rather than a
 * whole bar because rings of different lengths share no bar — there is no
 * interval at which everything repeats, so the unit of work is simply
 * "the next slice of time". [AudioSink.write] blocking on the device
 * buffer is the pacing, as it is for the loop grid; nothing here has a
 * timer to drift against.
 *
 * Edits arrive through [apply] as a whole ([OrbitSet], [OrbitBank]) pair
 * and are picked up at the top of the next block, never mid-buffer. The
 * bank is prepared by the caller, off this thread, before it is handed in:
 * this loop reads floats and adds them and does nothing else.
 *
 * Only [position], [apply], [rewind] and [stop] are meant for other
 * threads; everything else runs on the one that called [run].
 */
class OrbitEngine(
    initial: OrbitSet,
    initialBank: OrbitBank,
    private val sink: AudioSink,
    val blockFrames: Int = DEFAULT_BLOCK_FRAMES,
) {

    /** A set and the audio prepared for it, swapped in together. */
    data class Prepared(val set: OrbitSet, val bank: OrbitBank)

    private class Voice(val samples: FloatArray, val gainL: Float, val gainR: Float) {
        var pos = 0 // interleaved index
        val done: Boolean get() = pos >= samples.size
    }

    private val frame = AtomicLong(0)
    private val running = AtomicBoolean(true)
    private val pending = AtomicReference<Prepared?>(null)
    private val rewindRequested = AtomicBoolean(false)
    private val current = AtomicReference(Prepared(initial, initialBank))

    private val block = FloatArray(blockFrames * 2)
    private val voices = ArrayList<Voice>()

    init {
        require(blockFrames > 0) { "blockFrames must be positive: $blockFrames" }
    }

    /** Frames played since the start — every ring's phase is a function of this one number. */
    fun position(): Long = frame.get()

    /** What is playing right now — the set the UI should draw. */
    fun prepared(): Prepared = current.get()

    /** Queue an edited set and its prepared audio. Takes effect at the next block. */
    fun apply(prepared: Prepared) { pending.set(prepared) }

    /** Put every ring back on its downbeat at the next block. */
    fun rewind() { rewindRequested.set(true) }

    fun stop() { running.set(false) }

    /** Play until [stop]. Blocking — the caller owns the thread. */
    fun run() {
        while (running.get()) playOne()
    }

    /** Play exactly [blocks] blocks, or until stopped. */
    fun runFor(blocks: Int) {
        require(blocks >= 0) { "blocks must not be negative: $blocks" }
        for (i in 0 until blocks) {
            if (!running.get()) return
            playOne()
        }
    }

    private fun playOne() {
        pending.getAndSet(null)?.let { current.set(it) }
        if (rewindRequested.getAndSet(false)) {
            frame.set(0)
            voices.clear()
        }

        val (set, bank) = current.get()
        val from = frame.get()
        val until = from + blockFrames
        block.fill(0f)

        for (orbit in set.orbits) {
            if (!orbit.engaged || orbit.level == 0f) continue
            when (orbit.content) {
                is PatternOrbit -> schedule(set, bank, orbit, from, until)
                is SnipOrbit -> addLoop(set, bank, orbit, from)
            }
        }
        mixVoices()

        sink.write(block)
        frame.set(until)
    }

    /** Start a voice for every hit of [orbit] that lands inside this block. */
    private fun schedule(set: OrbitSet, bank: OrbitBank, orbit: Orbit, from: Long, until: Long) {
        val content = orbit.content as PatternOrbit
        for (firing in OrbitClock.firings(set, orbit, from, until)) {
            val pad = bank.pad(content.kit, firing.hit.slot) ?: continue
            val gain = firing.hit.velocity * orbit.level
            val voice = Voice(pad.samples, gain * leftLaw(orbit.pan), gain * rightLaw(orbit.pan))
            // The voice starts partway into the block; anything before its
            // start is not played, which the negative position expresses
            // without a second offset field.
            voice.pos = -((firing.frame - from).toInt() * 2)
            if (voices.size >= MAX_VOICES) voices.removeAt(0) // steal the oldest
            voices.add(voice)
        }
    }

    /** Add [blockFrames] of a snip ring, wrapping at its period. */
    private fun addLoop(set: OrbitSet, bank: OrbitBank, orbit: Orbit, from: Long) {
        val loop = bank.loop(set, orbit) ?: return
        val period = loop.frameCount
        if (period == 0) return
        val gainL = orbit.level * leftLaw(orbit.pan)
        val gainR = orbit.level * rightLaw(orbit.pan)
        var src = Math.floorMod(from, period.toLong()).toInt()
        val samples = loop.samples
        for (f in 0 until blockFrames) {
            block[f * 2] += samples[src * 2] * gainL
            block[f * 2 + 1] += samples[src * 2 + 1] * gainR
            src++
            if (src == period) src = 0
        }
    }

    private fun mixVoices() {
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val samples = v.samples
            // Where in the block this voice begins (0 unless it started this block).
            var out = if (v.pos < 0) -v.pos else 0
            var src = if (v.pos < 0) 0 else v.pos
            val n = min(block.size - out, samples.size - src)
            var k = 0
            while (k + 1 < n) {
                block[out] += samples[src] * v.gainL
                block[out + 1] += samples[src + 1] * v.gainR
                out += 2
                src += 2
                k += 2
            }
            v.pos = src
            if (v.done) it.remove()
        }
    }

    // The same linear law as Mixer: two multiplies, inaudibly different from
    // constant-power at the small pans a ring actually uses.
    private fun leftLaw(pan: Float) = min(1f, 1f - pan)
    private fun rightLaw(pan: Float) = min(1f, 1f + pan)

    companion object {
        const val DEFAULT_BLOCK_FRAMES = 2048
        const val MAX_VOICES = 64

        /**
         * Play [frames] of [set] offline into a [Snip] — the bounce, the
         * preview, and the way a test listens without a device.
         */
        fun render(set: OrbitSet, bank: OrbitBank, frames: Int, blockFrames: Int = DEFAULT_BLOCK_FRAMES): Snip {
            require(frames >= 0) { "frames must not be negative: $frames" }
            val sink = CollectingSink(set.sampleRate)
            val engine = OrbitEngine(set, bank, sink, blockFrames)
            val blocks = (frames + blockFrames - 1) / blockFrames
            engine.runFor(blocks)
            return Snip(sink.samples.copyOf(frames * 2), 2, set.sampleRate)
        }

        private class CollectingSink(override val sampleRate: Int) : AudioSink {
            override val channels = 2
            var samples = FloatArray(0)
            private var size = 0
            override fun write(block: FloatArray) {
                if (size + block.size > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + block.size))
                System.arraycopy(block, 0, samples, size, block.size)
                size += block.size
            }
            override fun close() {}
        }
    }
}
