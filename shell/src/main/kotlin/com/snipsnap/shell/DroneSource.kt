package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import com.snipsnap.loop.SampleSource
import com.snipsnap.loop.Session
import com.snipsnap.synth.ResinDrone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutionException

/**
 * The loop grid's drone renderer, handed in from outside so the grid still
 * doesn't know what RESIN is
 * (docs/superpowers/specs/2026-09-25-resin-drone-design.md, Decided 4).
 *
 * Everything but [drone] is [inner]'s. A drone recipe this can't read goes
 * to [inner] too, so a later engine's renderer can wrap this one.
 *
 * **One render per drone.** A drone's n slices are n blocks, and the grid
 * bakes blocks in parallel; without this every slice would render the whole
 * drone again, n times the seconds. The first slice to ask renders; the
 * others wait on the same future. A render that fails is forgotten, so the
 * next bake tries again, and answers null, which the baker plays as silence.
 */
class DroneSource(
    private val inner: SampleSource,
    private val renderer: (ResinDrone.Spec, Int, Long, Int) -> FloatArray = ResinDrone::render,
) : SampleSource by inner {

    private data class Key(val recipe: JsonValue, val rootMidi: Int, val frames: Long, val sampleRate: Int)

    private val renders = ConcurrentHashMap<Key, CompletableFuture<Snip>>()

    /** Completed renders, least recently asked for first, for eviction. */
    private val finished = ConcurrentLinkedDeque<Key>()

    fun residentCount(): Int = finished.size

    override fun drone(recipe: JsonValue, rootMidi: Int, frames: Long, sampleRate: Int): Snip? {
        val spec = ResinDrone.Spec.fromJson(recipe) ?: return inner.drone(recipe, rootMidi, frames, sampleRate)
        val key = Key(recipe, rootMidi, frames, sampleRate)
        var mine = false
        val future = renders.computeIfAbsent(key) {
            mine = true
            CompletableFuture()
        }
        if (mine) {
            try {
                val mono = renderer(spec, rootMidi, frames, sampleRate)
                val stereo = FloatArray(mono.size * 2)
                for (i in mono.indices) {
                    stereo[i * 2] = mono[i]
                    stereo[i * 2 + 1] = mono[i]
                }
                future.complete(Snip(stereo, 2, sampleRate))
                remember(key)
            } catch (e: Throwable) {
                renders.remove(key, future)
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get().also { if (!mine) touch(key) }
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Keep [key], drop the same drone at any other length (a tempo change
     * made it stale; nothing plays it again), then the oldest past
     * [MAX_RESIDENT].
     */
    private fun remember(key: Key) {
        for (old in finished) {
            if (old != key && old.recipe == key.recipe && old.rootMidi == key.rootMidi && old.sampleRate == key.sampleRate) {
                forget(old)
            }
        }
        finished.addLast(key)
        while (finished.size > MAX_RESIDENT) forget(finished.peekFirst() ?: break)
    }

    private fun touch(key: Key) {
        if (finished.remove(key)) finished.addLast(key)
    }

    private fun forget(key: Key) {
        finished.remove(key)
        renders.remove(key)
    }

    companion object {
        /**
         * One render per track: a drone's slices keep asking for its render
         * for as long as it plays, so holding fewer than the grid can play
         * would re-render a live drone every interval. The longest drone is
         * tens of MB, which is why it is this and not a cache of everything.
         */
        const val MAX_RESIDENT = Session.TRACK_COUNT
    }
}
