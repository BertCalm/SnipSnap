package com.snipsnap.synth

/**
 * Completed finite performances are owned by the cache, never by the caller's pad.
 * The byte limit includes only retained sample data; map keys are small recipe values.
 */
internal class TidalAudioCache<K>(val maxBytes: Long) {
    init { require(maxBytes >= 0) }

    private val entries = LinkedHashMap<K, FloatArray>(8, .75f, true)
    private var bytes = 0L

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

    fun clear() = synchronized(this) { entries.clear(); bytes = 0L }

    fun snapshot(): Tidal.AudioCacheSnapshot = synchronized(this) {
        Tidal.AudioCacheSnapshot(entries.size, bytes, maxBytes)
    }
}
