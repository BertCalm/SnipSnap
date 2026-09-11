package com.snipsnap.loop

import com.snipsnap.audio.AutoPlace
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

    private class Voice(
        val samples: FloatArray,
        val gainL: Float,
        val gainR: Float,
        val muteGroup: Int,
    ) {
        var pos = 0 // interleaved index
        /** Interleaved index past which this voice is silent; a choke moves it in. */
        var limit = samples.size
        /**
         * Shortened by a choke, rather than simply running out of sample —
         * tracked, not inferred from [limit]. A choke arriving inside the
         * last fade's worth of a pad leaves the stop point where it already
         * was, so "is it shorter than the sample?" answers no for a voice
         * that really was choked, and it would run out at full level.
         */
        var choked = false
        /**
         * Interleaved length of this voice's ramp: a full fade, or the rest
         * of the sample when less than that remains. Normalised rather than
         * fixed so the ramp always begins at full gain — starting a short
         * tail part-way down the slope is the click the fade exists to avoid.
         */
        var fadeLen = 0
        val done: Boolean get() = pos >= limit
    }

    /** One hit that lands in this block, waiting to be started in time order. */
    private class Scheduled(val orbit: Orbit, val hit: OrbitHit, val frame: Long)

    private val frame = AtomicLong(0)
    private val running = AtomicBoolean(true)
    private val pending = AtomicReference<Prepared?>(null)
    private val rewindRequested = AtomicBoolean(false)
    private val current = AtomicReference(Prepared(initial, initialBank))

    private val block = FloatArray(blockFrames * 2)
    private val voices = ArrayList<Voice>()
    private val due = ArrayList<Scheduled>()

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

        due.clear()
        for (orbit in set.orbits) {
            if (!orbit.engaged || orbit.level == 0f) continue
            when (orbit.content) {
                is PatternOrbit -> collect(set, orbit, from, until)
                is SnipOrbit -> addLoop(set, bank, orbit, from)
            }
        }
        // Choke means "the newest hit in the group wins", so the hits have
        // to be started in the order they are heard. Each ring's firings
        // are sorted, but rings are not sorted against each other - ring
        // two's downbeat can fall before ring one's third step - so the
        // merged order is what makes "newest" mean newest.
        due.sortBy { it.frame }
        for (s in due) start(bank, s, from)
        mixVoices()

        sink.write(block)
        frame.set(until)
    }

    /** Note every hit of [orbit] that lands inside this block; starting them is [start]'s. */
    private fun collect(set: OrbitSet, orbit: Orbit, from: Long, until: Long) {
        for (firing in OrbitClock.firings(set, orbit, from, until)) {
            due.add(Scheduled(orbit, firing.hit, firing.frame))
        }
    }

    /** Start one hit's voice, choking whatever it shares a mute group with. */
    private fun start(bank: OrbitBank, s: Scheduled, from: Long) {
        val content = s.orbit.content as PatternOrbit
        val pad = bank.pad(content.kit, s.hit.slot) ?: return
        val offset = (s.frame - from).toInt()
        val group = bank.muteGroup(content.kit, s.hit.slot)
        if (group != 0) choke(group, offset)
        val gain = s.hit.velocity * s.orbit.level
        val voice = Voice(pad.samples, gain * leftLaw(s.orbit.pan), gain * rightLaw(s.orbit.pan), group)
        // The voice starts partway into the block; anything before its
        // start is not played, which the negative position expresses
        // without a second offset field.
        voice.pos = -(offset * 2)
        if (voices.size >= MAX_VOICES) voices.removeAt(0) // steal the oldest
        voices.add(voice)
    }

    /**
     * The kit's own rule, honoured live: a new voice in [group] ends
     * everything still ringing in it, [offset] frames into this block.
     *
     * The same rule and the same fade as `KitPreview.render`, so an open
     * hat closed by a closed hat sounds the same in ORBIT, in the preview
     * and on the hardware. It ends over [AutoPlace.CHOKE_FADE] frames rather than
     * at once because a sample cut mid-cycle is a click.
     */
    private fun choke(group: Int, offset: Int) {
        for (v in voices) {
            if (v.muteGroup != group) continue
            // Where that voice will be when this hit lands. Negative means
            // it has not started yet, which only a hit later in this same
            // block could be - and a later hit never chokes an earlier one.
            val at = v.pos + offset * 2
            if (at < 0 || at >= v.limit) continue
            // Whatever is left when less than a fade remains: the voice
            // still ramps, over a shorter slope, instead of stopping flat.
            val fade = min(AutoPlace.CHOKE_FADE * 2, v.limit - at)
            v.limit = at + fade
            v.fadeLen = fade
            v.choked = true
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
            val stop = v.limit
            val n = min(block.size - out, stop - src)
            // Only a choked voice ramps. One that simply reaches the end of
            // its sample keeps the tail it was recorded with.
            val fading = v.choked
            val fadeLen = v.fadeLen
            var k = 0
            while (k + 1 < n) {
                // Capped at 1: a voice choked partway through this block
                // is still at full gain for the frames before the hit landed.
                val g = if (fading) min(1f, (stop - src).toFloat() / fadeLen) else 1f
                block[out] += samples[src] * v.gainL * g
                block[out + 1] += samples[src + 1] * v.gainR * g
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
