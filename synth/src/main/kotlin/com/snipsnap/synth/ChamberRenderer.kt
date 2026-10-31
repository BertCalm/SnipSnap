package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

/**
 * A small per-screen cache of completed, full-quality finite room recordings.
 *
 * A changed note or physical control still runs the coupled room. Exact repeats
 * (including preview followed by render-to-pad) reuse only the finished recording;
 * no mutable modal, delay or filter state survives a call. The default budget is
 * eighteen seconds of mono PCM, about 3.2 MB, or four ordinary 4.5-second renders.
 * The private recordings never escape: downstream processing owns its sample array.
 */
class ChamberRenderer internal constructor(
    private val maxCachedSamples: Int,
    private val source: (ChamberPatch, () -> Boolean) -> Snip,
) {
    constructor(maxCachedSamples: Int = DEFAULT_MAX_CACHED_SAMPLES) : this(maxCachedSamples,
        { patch, cancelled -> Chamber.render(patch.voice, patch.macros, patch.midi, patch.velocity,
            patch.configuration, patch.seed, patch.model, cancelled) })

    init { require(maxCachedSamples >= 0) { "CHAMBER cache budget must be nonnegative" } }

    private data class Key(
        val voice: ChamberVoice,
        val macros: List<Int>,
        val midi: Int,
        val velocity: Int,
        val configuration: ChamberConfig,
        val seed: Long,
        val model: Int,
    )
    private data class Entry(val samples: FloatArray, val channels: Int, val sampleRate: Int)
    private data class Lookup(val entry: Entry?, val generation: Long)

    internal data class Stats(val hits: Long, val misses: Long, val entries: Int, val samples: Int)

    private val lock = Any()
    private val completed = LinkedHashMap<Key, Entry>(8, .75f, true)
    private var cachedSamples = 0
    private var generation = 0L
    private var hits = 0L
    private var misses = 0L

    fun render(patch: ChamberPatch, cancelled: () -> Boolean = { false }): Snip {
        checkCancellation(cancelled)
        // ChamberConfig is composed exclusively of immutable value objects. Its
        // list-valued accessors create fresh lists; the caller's macro map is the
        // only mutable recipe input, so snapshot and resolve it before keying work.
        val request = patch.copy(macros = Chamber.settled(patch.voice, patch.macros.toMap()))
        val key = Key(request.voice, request.macros.values.map { it.toRawBits() }, request.midi,
            request.velocity.toRawBits(), request.configuration, request.seed, request.model)
        val lookup = synchronized(lock) {
            checkCancellation(cancelled)
            val entry = completed[key]
            if (entry == null) misses++ else hits++
            Lookup(entry, generation)
        }
        lookup.entry?.let { entry ->
            val samples = entry.samples.copyOf()
            checkCancellation(cancelled)
            return Snip(samples, entry.channels, entry.sampleRate)
        }

        // Render outside the lock, so cancellation or another cache hit never
        // waits for a room simulation. Concurrent misses own independent state.
        val rendered = source(request, cancelled)
        checkCancellation(cancelled)
        if (maxCachedSamples > 0 && rendered.samples.isNotEmpty() &&
            rendered.channels == 1 && rendered.sampleRate == Chamber.RATE &&
            rendered.samples.size <= maxCachedSamples) {
            val snapshot = rendered.samples.copyOf()
            checkCancellation(cancelled)
            val candidate = Entry(snapshot, rendered.channels, rendered.sampleRate)
            synchronized(lock) {
                checkCancellation(cancelled)
                // clear() invalidates work already in flight as well as old hits.
                if (lookup.generation == generation) {
                    completed.remove(key)?.let { cachedSamples -= it.samples.size }
                    while (cachedSamples > maxCachedSamples - snapshot.size) {
                        val oldest = completed.entries.iterator()
                        cachedSamples -= oldest.next().value.samples.size
                        oldest.remove()
                    }
                    completed[key] = candidate
                    cachedSamples += snapshot.size
                }
            }
        }
        checkCancellation(cancelled)
        // The miss result is already owned; only its independent snapshot entered
        // the cache, so a second copy here would waste memory without adding safety.
        return rendered
    }

    fun clear() = synchronized(lock) {
        completed.clear()
        cachedSamples = 0
        generation++
        hits = 0
        misses = 0
    }

    internal fun stats(): Stats = synchronized(lock) {
        Stats(hits, misses, completed.size, cachedSamples)
    }

    private fun checkCancellation(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw CancellationException("CHAMBER render no longer wanted")
        }
    }

    companion object {
        const val DEFAULT_MAX_CACHED_SAMPLES = Chamber.RATE * 18
    }
}
