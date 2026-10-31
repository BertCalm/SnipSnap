package com.snipsnap.synth

import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

/**
 * Small preparation-only LRU. Values must be complete immutable render-independent data.
 * Preparation runs outside the lock, so cancellation or a slow miss never blocks readers.
 * Duplicate concurrent misses may prepare twice; only one complete value is retained.
 */
internal class ApsisPreparationCache<K : Any, V : Any>(
    private val maxEntries: Int = 6,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxEntryBytes: Long = 12L * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxBytes > 0 && maxEntryBytes > 0) }
    private data class Entry<V>(val value: V, val bytes: Long)
    data class Lookup<V>(val value: V, val hit: Boolean, val retained: Boolean)
    data class Snapshot(val entries: Int, val bytes: Long, val hits: Long, val misses: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private var bytes = 0L
    private var hits = 0L
    private var misses = 0L

    private fun checkCancellation(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled())
            throw CancellationException("APSIS preparation cancelled")
    }

    fun getOrPrepare(key: K, estimatedBytes: Long, cancelled: () -> Boolean, prepare: () -> V): Lookup<V> {
        require(estimatedBytes >= 0)
        checkCancellation(cancelled)
        val cached = synchronized(lock) {
            entries[key]?.also { hits++ }?.value.also { if (it == null) misses++ }
        }
        if (cached != null) {
            checkCancellation(cancelled)
            return Lookup(cached, hit = true, retained = true)
        }
        val prepared = prepare()
        checkCancellation(cancelled)
        if (estimatedBytes > maxEntryBytes || estimatedBytes > maxBytes)
            return Lookup(prepared, hit = false, retained = false)
        return synchronized(lock) {
            // A cancelled preparation must never become a shared cache entry.
            checkCancellation(cancelled)
            val raced = entries[key]
            if (raced != null) Lookup(raced.value, hit = false, retained = true)
            else {
                while (entries.isNotEmpty() && (entries.size >= maxEntries || bytes + estimatedBytes > maxBytes)) {
                    val oldest = entries.entries.iterator().next()
                    bytes -= oldest.value.bytes
                    entries.remove(oldest.key)
                }
                entries[key] = Entry(prepared, estimatedBytes)
                bytes += estimatedBytes
                Lookup(prepared, hit = false, retained = true)
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(entries.size, bytes, hits, misses) }
    fun clear() = synchronized(lock) { entries.clear(); bytes = 0; hits = 0; misses = 0 }
}
