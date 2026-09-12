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
        /** The kit whose rule this is — a group number means nothing outside it. */
        val kit: String,
        val muteGroup: Int,
        /** Which ring struck it, so a section change can tell whose voice this is. */
        val ring: Int,
    ) {
        var pos = 0 // interleaved index
        /** Interleaved index past which this voice is silent; a choke moves it in. */
        var limit = samples.size
        /**
         * Whether the mixer ramps this voice out at [limit] instead of
         * letting it end on the tail it was recorded with.
         *
         * That is the whole of it, and the only thing that reads it is
         * [mixVoices]. Two things set it — a choke, and a hit's own
         * [OrbitHit.length] — because they are one act, a voice ended
         * before its sample; hence the name, since a flag called `choked`
         * read false on a gated voice and the first cut of the gate
         * stopped flat and clicked.
         *
         * It is deliberately **not** what [choke] asks to decide whether a
         * voice is already leaving. A gated voice sets this the instant it
         * starts and then sits at full gain until its gate arrives, so a
         * flag here said "already going" while the voice was still at
         * full — and a later hit in its mute group rang straight through
         * it. That question is about *time*, not about intent, so [choke]
         * asks the ramp: `at >= limit - fadeLen`.
         */
        var fading = false
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
    private class Scheduled(val orbit: Orbit, val ring: Int, val hit: OrbitHit, val frame: Long)

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

    /**
     * Frames played since the start.
     *
     * Every ring's phase is a function of this one number — through
     * [OrbitClock.localFrame], which on a set with an arrangement counts
     * from where the current section began and on one without hands this
     * number straight back.
     */
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
        // A block is a slice of wall-clock time and a section boundary does
        // not wait for one to end, so a block that straddles one is played
        // as two pieces. Each piece is handed its OWN local frame, which is
        // the whole of what a section does: the rings inside it are the
        // same pure function of the same one number they always were,
        // counted from where the section began rather than from play.
        //
        // A set with no arrangement takes this loop exactly once, with
        // `local == at` and `shift == 0` - the code it ran before.
        var at = from
        while (at < until) {
            val edge = min(until, OrbitClock.nextBoundary(set, at))
            val local = OrbitClock.localFrame(set, at)
            // Back to the transport's own numbering, which is what a voice
            // offset inside this block is measured against.
            val shift = at - local
            hush(set, from, at)
            for ((index, orbit) in set.orbits.withIndex()) {
                // The ring's own mute and the section's choice are different
                // questions: a muted ring stays muted whatever a section says.
                if (!orbit.engaged || orbit.level == 0f) continue
                if (!OrbitClock.playsAt(set, index, at)) continue
                when (orbit.content) {
                    is PatternOrbit -> collect(set, orbit, index, local, local + (edge - at), shift)
                    is SnipOrbit -> addLoop(set, bank, orbit, local, (at - from).toInt(), (edge - from).toInt())
                }
            }
            at = edge
        }
        // Choke means "the newest hit in the group wins", so the hits have
        // to be started in the order they are heard. Each ring's firings
        // are sorted, but rings are not sorted against each other - ring
        // two's downbeat can fall before ring one's third step - so the
        // merged order is what makes "newest" mean newest.
        due.sortBy { it.frame }
        for (s in due) start(set, bank, s, from)
        mixVoices()

        sink.write(block)
        frame.set(until)
    }

    /**
     * Note every hit of [orbit] that lands inside this piece of the block;
     * starting them is [start]'s.
     *
     * [from] and [until] are the section's own frames; [shift] puts the
     * answer back on the transport's numbering, since that is what the
     * voice offset inside the block is measured against. With no
     * arrangement [shift] is zero and this is what it always was.
     */
    private fun collect(set: OrbitSet, orbit: Orbit, ring: Int, from: Long, until: Long, shift: Long) {
        for (firing in OrbitClock.firings(set, orbit, from, until)) {
            due.add(Scheduled(orbit, ring, firing.hit, firing.frame + shift))
        }
    }

    /**
     * End any voice whose ring the section at [at] leaves out.
     *
     * A section says which rings play, and a voice already sounding is not
     * exempt from that: without this, a pad struck just before a boundary
     * rang on into the section after it, and a BREAK - a section that
     * plays nothing at all - was audible. Verified before it was fixed:
     * the buffer read full gain a thousand frames into a break.
     *
     * Over [endAt]'s ramp, and asking the ramp rather than a flag, exactly
     * as [choke] does - this is the same act as a choke, a voice ended
     * before its sample, and a third copy of that arithmetic is how the
     * three of them come to disagree. A ring the next section still plays
     * keeps its tail, which is the point of asking per ring rather than
     * silencing everything at every boundary.
     */
    private fun hush(set: OrbitSet, from: Long, at: Long) {
        if (set.sections.isEmpty()) return
        val offset = (at - from).toInt()
        for (v in voices) {
            if (OrbitClock.playsAt(set, v.ring, at)) continue
            val cut = v.pos + offset * 2
            if (cut < 0) continue
            if (cut >= v.limit - v.fadeLen) continue
            endAt(v, cut)
        }
    }

    /** Start one hit's voice, choking whatever it shares a mute group with. */
    private fun start(set: OrbitSet, bank: OrbitBank, s: Scheduled, from: Long) {
        val content = s.orbit.content as PatternOrbit
        val pad = bank.pad(content.kit, s.hit.slot) ?: return
        val offset = (s.frame - from).toInt()
        val group = bank.muteGroup(content.kit, s.hit.slot)
        if (group != 0) choke(content.kit, group, offset)
        val gain = s.hit.velocity * s.orbit.level
        val voice = Voice(pad.samples, gain * leftLaw(s.orbit.pan), gain * rightLaw(s.orbit.pan), content.kit, group, s.ring)
        // The voice starts partway into the block; anything before its
        // start is not played, which the negative position expresses
        // without a second offset field.
        voice.pos = -(offset * 2)
        gate(voice, set, s.hit)
        if (voices.size >= MAX_VOICES) voices.removeAt(0) // steal the oldest
        voices.add(voice)
    }

    /**
     * End [voice] after [hit]'s own length, when it has one.
     *
     * A hit with [OrbitHit.WHOLE_SAMPLE] is left alone and plays its
     * sample out, which is what every hit did before lengths existed and
     * what a drum wants: a kick is over when the kick is over. A gated hit
     * stops where it is told.
     *
     * Any positive length gates any pad. This deliberately does not consult
     * `KitPad.oneShot`: that is a boolean over what the corpus records as
     * three trigger modes, so it cannot say whether a given pad reads a
     * note's length, and gating on the guess would stop voices live that
     * the hardware plays out. The length is the caller's instruction, and
     * this honours it.
     *
     * It ends over the same ramp a choke uses rather than a fifth fade
     * length of its own — the two are the same act, a voice cut before its
     * sample ran out, and a sample stopped mid-cycle is a click whichever
     * reason stopped it. They share [endAt] for that reason.
     *
     * The voice stays chokeable in the meantime. It is marked
     * [Voice.fading] so the mixer ramps it, but that flag says nothing
     * about whether a later hit may shorten it: this one is at full gain
     * until its gate arrives, and [choke] works that out from the ramp
     * rather than from the flag.
     */
    private fun gate(voice: Voice, set: OrbitSet, hit: OrbitHit) {
        if (!hit.gated) return
        // A plain index into the sample, because that is what [Voice.limit]
        // is. A new voice's `pos` is negative — it encodes where in *this
        // block* the hit lands, not how much sample has played — so adding
        // it here gated a hit by however far into the block it started,
        // which for step 1 at 120 BPM was nearly two thousand frames early.
        //
        // Compared as a `Long` and narrowed only once the answer is known
        // to fit. `sampleRate` is merely required to be positive, so a set
        // at 3 MHz and 40 BPM makes a MAX_LENGTH hit 2.3 billion samples:
        // truncating first wrapped that negative, sailed past this guard,
        // and silenced a voice that should have played its sample out.
        val end = OrbitClock.framesForPulses(set, hit.length) * 2
        if (end >= voice.limit) return // the sample runs out first; nothing to cut
        endAt(voice, end.toInt())
    }

    /**
     * Stop [v] at [at], on a ramp.
     *
     * The one place that shortens a voice, because a choke and a gate do
     * the same thing and writing it twice got it wrong once already: the
     * gate clamped its fade against the stop point instead of against what
     * was left of the sample, so a pad only a little longer than its gate
     * ran the ramp off the end of its own buffer and threw.
     *
     * The fade is whatever is left when less than a full one remains, so
     * the voice still ramps over a shorter slope rather than stopping flat.
     */
    private fun endAt(v: Voice, at: Int) {
        val fade = min(AutoPlace.CHOKE_FADE * 2, v.limit - at)
        v.limit = at + fade
        v.fadeLen = fade
        v.fading = true
    }

    /**
     * The kit's own rule, honoured live: a new voice in [kit]'s [group]
     * ends everything still ringing in it, [offset] frames into this block.
     *
     * Scoped to the kit because a mute group numbers a slot in one program
     * and says nothing about another's. Rings in a set can name different
     * kits, and `AutoPlace` puts every kit's hats in the same group, so
     * comparing the number alone would have one kit's hats silencing
     * another's.
     *
     * The same rule and the same fade as `KitPreview.render`, so an open
     * hat closed by a closed hat sounds the same in ORBIT, in the preview
     * and on the hardware. It ends over [AutoPlace.CHOKE_FADE] frames rather than
     * at once because a sample cut mid-cycle is a click.
     */
    private fun choke(kit: String, group: Int, offset: Int) {
        for (v in voices) {
            if (v.muteGroup != group || v.kit != kit) continue
            // Where that voice will be when this hit lands. Negative means
            // it has not started yet, which only a hit later in this same
            // block could be - and a later hit never chokes an earlier one.
            val at = v.pos + offset * 2
            if (at < 0) continue
            // Already inside its own ramp, whatever put it there. Re-deriving
            // from this instant would set the gain back to full and the voice
            // would jump up mid-fade; it is going to silence either way, and
            // sooner than a fresh fade would take it.
            //
            // Asked of where the ramp starts rather than of a flag. A voice
            // with an end merely SCHEDULED - a hit gated by its own length -
            // is still at full gain until it gets there, and must still be
            // chokeable; a flag said it was already leaving and a later hit
            // in its mute group rang straight through it.
            //
            // For a voice nothing has shortened, `fadeLen` is 0 and this is
            // the plain "has it finished?" it replaces.
            if (at >= v.limit - v.fadeLen) continue
            endAt(v, at)
        }
    }

    /**
     * Add one piece of a snip ring to the block, wrapping at its period.
     *
     * [from] is the section's own frame, so a snip ring restarts with its
     * section exactly as a pattern ring does — a taped loop that carried
     * on through a section change would be the one thing on screen still
     * playing the section before. [blockStart] and [blockEnd] bound the
     * piece inside this block; with no arrangement they are the whole of
     * it and this is what it always was.
     */
    private fun addLoop(
        set: OrbitSet,
        bank: OrbitBank,
        orbit: Orbit,
        from: Long,
        blockStart: Int,
        blockEnd: Int,
    ) {
        val loop = bank.loop(set, orbit) ?: return
        val period = loop.frameCount
        if (period == 0) return
        val gainL = orbit.level * leftLaw(orbit.pan)
        val gainR = orbit.level * rightLaw(orbit.pan)
        var src = Math.floorMod(from, period.toLong()).toInt()
        val samples = loop.samples
        for (f in blockStart until blockEnd) {
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
            // Only a voice being ended ramps — choked, or gated by its
            // hit's own length. One that simply reaches the end of its
            // sample keeps the tail it was recorded with.
            val fading = v.fading
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
