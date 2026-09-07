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
 *
 * The cache is stamped with [Session.intervalFrames], not keyed by [Block]
 * alone. `putIfAbsent(key, BlockBaker.bake(...))` evaluates its argument
 * before the call, so [prefetch]'s "is the session still current" check runs
 * ONCE, before the bake, not again before the insert — the check-then-insert
 * is not atomic, and a bake takes tens of milliseconds (decode, resample,
 * chop). An [update] that changes [Session.intervalFrames] can land in that
 * window: it clears the cache, and the in-flight prefetch's late insert would
 * otherwise land under a key that is still "current" (the same [Block]),
 * silently reintroducing a buffer of the wrong length that [retain] would
 * never evict on a short chain. Stamping the key with the [Session] it was
 * baked for makes a stale-length entry structurally unreachable — nothing
 * ever looks it up — rather than merely unlikely. `update`'s `clear()` still
 * matters for memory: without it, orphaned old-stamp entries would
 * accumulate rather than being reclaimed on the next [retain] or the next
 * length change.
 *
 * Bpm affects baking only through [Session.intervalFrames]: `BlockBaker`'s
 * loop path uses it as the fit target and the drift ratio, and its pattern
 * path uses it as the target and to derive step spacing — nothing else in
 * the bake depends on bpm. [Session.stepsPerInterval] depends on
 * `barsPerInterval`, not bpm, and `barsPerInterval` is not live.
 */
class Residency(
    initial: Session,
    private val source: SampleSource,
    private val executor: Executor,
) {

    /** A block stamped with the interval length it was baked for. */
    private data class CacheKey(val block: Block, val intervalFrames: Int)

    private val current = AtomicReference(initial)
    private val baked = ConcurrentHashMap<CacheKey, Snip>()

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
            val key = CacheKey(block, s.intervalFrames)
            baked.getOrPut(key) { BlockBaker.bake(block, s, source) }
        }
    }

    /** Bake what [interval] will need, on the executor. Returns immediately. */
    fun prefetch(interval: Int) {
        val s = current.get()
        for (track in s.tracks) {
            val block = Arrangement.blockAt(track, interval)
            val key = CacheKey(block, s.intervalFrames)
            if (baked.containsKey(key)) continue
            executor.execute {
                // Cheap early-out for a session that's plainly gone stale
                // before baking even starts. Not required for correctness —
                // the stamped key already makes a late insert unreachable —
                // but there is no reason to spend a bake on a length nothing
                // will ever read.
                if (current.get().intervalFrames == s.intervalFrames) {
                    baked.putIfAbsent(key, BlockBaker.bake(block, s, source))
                }
            }
        }
    }

    /** Drop every buffer not needed by one of [intervals]. */
    fun retain(vararg intervals: Int) {
        val s = current.get()
        val keep = HashSet<CacheKey>()
        for (i in intervals) {
            for (track in s.tracks) keep.add(CacheKey(Arrangement.blockAt(track, i), s.intervalFrames))
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
