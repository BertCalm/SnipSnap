package com.snipsnap.loop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the grid.
 *
 * One interval per turn of the loop: read the buffers, mix, write, prefetch the
 * next, drop what is no longer needed, advance. The output buffer is allocated
 * once and reused forever — the sink is expected to consume it before returning,
 * exactly as AudioTrack.write does.
 *
 * Edits are handed in through [apply] and picked up at the top of the next
 * interval, never mid-block. That is the whole of spec section 07's "swap
 * atomically at the next interval boundary": a pending reference, read once per
 * interval, at the one instant when nothing is half-written.
 */
class LoopEngine(
    private val residency: Residency,
    private val sink: AudioSink,
) {

    private val interval = AtomicInteger(0)
    private val running = AtomicBoolean(true)
    private val pending = AtomicReference<Session?>(null)

    private var block = FloatArray(residency.session().intervalFrames * 2)

    fun position(): Int = interval.get()

    /** Queue an edited session. Takes effect at the next interval boundary. */
    fun apply(session: Session) { pending.set(session) }

    fun stop() { running.set(false) }

    /** Play until [stop]. Blocking — the caller owns the thread. */
    fun run() {
        while (running.get()) playOne()
    }

    /** Play exactly [intervals] intervals, or until stopped. */
    fun runFor(intervals: Int) {
        require(intervals >= 0) { "intervals must not be negative: $intervals" }
        for (i in 0 until intervals) {
            if (!running.get()) return
            playOne()
        }
    }

    private fun playOne() {
        pending.getAndSet(null)?.let { edited ->
            residency.update(edited)
            // A BPM change resizes the interval, so the reused buffer has to
            // grow or shrink with it. This is the only place it may reallocate.
            val frames = edited.intervalFrames * 2
            if (block.size != frames) block = FloatArray(frames)
        }

        val i = interval.get()
        val session = residency.session()

        Mixer.mix(residency.buffersFor(i), session.tracks, block)
        sink.write(block)

        residency.prefetch(i + 1)
        residency.retain(i, i + 1)
        interval.set(i + 1)
    }
}
