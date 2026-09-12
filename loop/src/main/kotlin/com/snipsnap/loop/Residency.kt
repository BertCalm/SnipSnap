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

    /**
     * A session that has been baked for but is not playing yet — see [warm].
     *
     * It exists so that [retain], whose job is to throw away everything the
     * engine does not need, does not throw away the very buffers a tempo
     * change is about to need. Cleared by [update] the moment that session
     * becomes current, and overwritten by a later [warm]: only one change can
     * be on its way in at a time, because only one can be [LoopEngine.apply]'d
     * next.
     */
    private val incoming = AtomicReference<Session?>(null)

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

    /**
     * Bake [session] for [intervals] before it is playing, so that applying it
     * costs nothing on the audio thread.
     *
     * This exists for one edit: a tempo change. Everything else the grid can
     * do is free — mutes and levels are read by the mixer, and a chain edit
     * re-keys the cache by itself — but a new BPM resizes the interval, which
     * makes every baked buffer the wrong length at once. Without this, the
     * first [buffersFor] after the change finds an empty cache and bakes six
     * blocks **on the caller's thread**, which is the audio thread: an audible
     * stall every time someone nudges the tempo.
     *
     * Warmed entries are stamped with [session]'s own interval length, so they
     * are unreachable until it becomes current — a warm for a change the
     * player undoes before it lands is wasted work, not a wrong buffer. The
     * caller warms, then [LoopEngine.apply]s; the engine's own [update] at the
     * next boundary keeps what was warmed and drops what went stale.
     */
    fun warm(session: Session, vararg intervals: Int) {
        incoming.set(session)
        for (i in intervals) {
            for (track in session.tracks) {
                val block = Arrangement.blockAt(track, i)
                val key = CacheKey(block, session.intervalFrames)
                if (baked.containsKey(key)) continue
                executor.execute {
                    // Still wanted? The same early-out prefetch makes, against
                    // both sessions this cache serves: the one playing and the
                    // one on its way in.
                    val wanted = session.intervalFrames == current.get().intervalFrames ||
                        session.intervalFrames == incoming.get()?.intervalFrames
                    if (wanted) baked.putIfAbsent(key, BlockBaker.bake(block, session, source))
                }
            }
        }
    }

    /**
     * Drop every buffer not needed by one of [intervals].
     *
     * "Needed" counts the session on its way in as well as the one playing
     * (see [warm]): this runs every interval, so without that a tempo change
     * warmed a moment ago would be swept before the engine ever applied it,
     * and the stall the warm exists to prevent would happen anyway.
     */
    fun retain(vararg intervals: Int) {
        val s = current.get()
        val next = incoming.get()
        val keep = HashSet<CacheKey>()
        for (i in intervals) {
            for (track in s.tracks) keep.add(CacheKey(Arrangement.blockAt(track, i), s.intervalFrames))
            if (next != null) {
                for (track in next.tracks) keep.add(CacheKey(Arrangement.blockAt(track, i), next.intervalFrames))
            }
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
        // This session has arrived, so it is no longer on its way in.
        incoming.compareAndSet(session, null)
        if (previous.intervalFrames == session.intervalFrames) return
        // Evict by stamp rather than clearing outright: a clear would also
        // throw away whatever [warm] baked for this very session, which is the
        // one case where the cache has exactly what is needed at the moment
        // the length changes. Anything left over from the old length is
        // unreachable either way — the keys are stamped — so this is about
        // memory, and about keeping the warm.
        baked.keys.removeIf { it.intervalFrames != session.intervalFrames }
    }
}
