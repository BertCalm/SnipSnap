package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The baked buffers currently in memory.
 *
 * Spec section 06 sizes this at twelve — current and next for each of six
 * tracks, roughly 49MB at 4 bars and 90 BPM. A long cycle can reference far
 * more distinct blocks than that, so [retain] is not an optimisation; without
 * it an 840-interval cycle would try to hold every block at once.
 *
 * Keying the cache by [Block] does more work than it looks like. Blocks are
 * data classes, so swapping a track's block produces a value that simply is
 * not in the map and gets baked on demand, while every untouched block stays
 * cached — block-swap invalidation falls out of equality rather than needing
 * its own bookkeeping.
 */
class Residency(
    initial: Session,
    private val source: SampleSource,
    private val executor: Executor,
) {

    private val current = AtomicReference(initial)
    private val baked = ConcurrentHashMap<Block, Snip>()

    fun session(): Session = current.get()

    fun residentCount(): Int = baked.size

    /**
     * One buffer per track for [interval], baking anything missing on the
     * calling thread. Call [prefetch] an interval ahead so this does not have
     * to bake anything.
     */
    fun buffersFor(interval: Int): List<Snip> {
        val s = current.get()
        return s.tracks.map { track ->
            val block = Arrangement.blockAt(track, interval)
            baked.getOrPut(block) { BlockBaker.bake(block, s, source) }
        }
    }

    /** Bake what [interval] will need, on the executor. Returns immediately. */
    fun prefetch(interval: Int) {
        val s = current.get()
        for (track in s.tracks) {
            val block = Arrangement.blockAt(track, interval)
            if (baked.containsKey(block)) continue
            executor.execute {
                // Re-check the session: a BPM change may have landed while this
                // was queued, and a buffer of the old length is worse than none.
                if (current.get().intervalFrames == s.intervalFrames) {
                    baked.putIfAbsent(block, BlockBaker.bake(block, s, source))
                }
            }
        }
    }

    /** Drop every buffer not needed by one of [intervals]. */
    fun retain(vararg intervals: Int) {
        val s = current.get()
        val keep = HashSet<Block>()
        for (i in intervals) {
            for (track in s.tracks) keep.add(Arrangement.blockAt(track, i))
        }
        baked.keys.retainAll(keep)
    }

    /**
     * Swap in an edited session.
     *
     * Only a change to interval length invalidates audio: every baked buffer is
     * exactly one interval long, so a new BPM makes all of them the wrong size
     * at once. Mutes, levels and pans are read by the mixer and need no re-bake;
     * chain edits and block swaps re-key the cache by themselves.
     */
    fun update(session: Session) {
        val previous = current.getAndSet(session)
        if (previous.intervalFrames != session.intervalFrames) baked.clear()
    }
}
