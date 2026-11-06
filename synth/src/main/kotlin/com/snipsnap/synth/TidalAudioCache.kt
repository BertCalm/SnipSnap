package com.snipsnap.synth

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Completed finite performances are owned by the cache, never by the caller's pad.
 * The byte limit includes only retained sample data; map keys are small recipe values.
 */
internal class TidalAudioCache<K>(val maxBytes: Long) {
    init { require(maxBytes >= 0) }

    private val entries = LinkedHashMap<K, FloatArray>(8, .75f, true)
    private var bytes = 0L
    private class Flight { val lock = ReentrantLock(); var users = 0 }
    private val flights = HashMap<K, Flight>()

    /** Copy outside the lock: filtering a returned buffer must not change a future hit. */
    fun get(key: K): FloatArray? {
        val owned = synchronized(this) { entries[key] } ?: return null
        return owned.copyOf()
    }

    /** The completion check follows the copy and precedes publishing the finished sound. */
    fun put(key: K, samples: FloatArray, completed: () -> Unit = {}) {
        val size = samples.size.toLong() * Float.SIZE_BYTES
        if (size > maxBytes) return
        val owned = samples.copyOf()
        completed()
        synchronized(this) {
            entries.remove(key)?.let { bytes -= it.size.toLong() * Float.SIZE_BYTES }
            entries[key] = owned
            bytes += size
            val iterator = entries.entries.iterator()
            while (bytes > maxBytes && iterator.hasNext()) {
                bytes -= iterator.next().value.size.toLong() * Float.SIZE_BYTES
                iterator.remove()
            }
        }
    }

    /**
     * Runs [block] while no other caller runs it for an equal [key], so a preview and a SEND
     * of one recipe synthesize once rather than side by side; [block] reads the cache first.
     * Other keys never wait. A waiter calls [check] between short waits (it throws to stop
     * waiting), and an interrupt stops it as a cancellation with the thread still interrupted.
     * If the caller inside is cancelled or fails, the next one enters and renders itself.
     * An uncontended entry makes no [check] call and never reads the interrupt flag.
     */
    fun <T> exclusive(key: K, check: () -> Unit, block: () -> T): T {
        val flight = synchronized(flights) { flights.getOrPut(key) { Flight() }.also { it.users++ } }
        try {
            if (!flight.lock.tryLock()) {
                try {
                    while (!flight.lock.tryLock(WAIT_MILLIS, TimeUnit.MILLISECONDS)) check()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("TIDAL render cancelled while an identical render was in flight")
                }
            }
            try {
                return block()
            } finally {
                flight.lock.unlock()
            }
        } finally {
            synchronized(flights) { if (--flight.users == 0) flights.remove(key) }
        }
    }

    /** Keys with a caller inside or waiting in [exclusive]; zero when nothing is rendering. */
    fun inFlight(): Int = synchronized(flights) { flights.size }

    fun clear() = synchronized(this) { entries.clear(); bytes = 0L }

    fun snapshot(): Tidal.AudioCacheSnapshot = synchronized(this) {
        Tidal.AudioCacheSnapshot(entries.size, bytes, maxBytes)
    }

    private companion object {
        const val WAIT_MILLIS = 20L
    }
}
