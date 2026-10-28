package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentVoice
import java.util.concurrent.CancellationException

/**
 * Dry FILAMENT previews for one SYNTH visit. The key includes everything that
 * changes audio, while a preset's display name does not invalidate a render.
 * Processing stays outside this cache so reopening a different rack cannot
 * reuse a previous pad's effects. Storage is bounded by sample bytes.
 *
 * [Snip.samples] is mutable. Both stored audio and cache hits are owned copies:
 * audition, cleanup and a caller's edits must never change a later preview.
 */
class FilamentPreviewCache(private val maximumBytes: Long = 16L * 1024 * 1024) {
    init { require(maximumBytes >= 0) }

    private companion object { const val MAXIMUM_ENTRIES = 8 }

    private data class Key(
        val voice: FilamentVoice,
        val macros: List<Int>,
        val midi: Int,
        val velocity: Int,
        val config: FilamentConfig,
        val model: Int,
    )

    private fun key(patch: FilamentPatch): Key {
        val settled = Filament.settled(patch.voice, patch.macros)
        return Key(patch.voice, Filament.macrosFor(patch.voice).map { settled.getValue(it.name).toRawBits() },
            patch.midi, patch.velocity.toRawBits(), patch.config, patch.model)
    }

    private val entries = LinkedHashMap<Key, Snip>(16, .75f, true)
    private var bytes = 0L

    val cachedBytes: Long get() = synchronized(this) { bytes }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }

    fun getOrRender(
        patch: FilamentPatch,
        cancelled: () -> Boolean = { false },
        render: () -> Snip,
    ): Snip {
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("FILAMENT preview cancelled")
        }
        checkCancelled()
        val key = key(patch)
        val hit = synchronized(this) { entries[key]?.ownedCopy() }
        if (hit != null) {
            checkCancelled()
            return hit
        }
        // A superseded render can finish late. Never publish it into the cache.
        val rendered = render()
        checkCancelled()
        val size = rendered.samples.size.toLong() * Float.SIZE_BYTES
        if (size > 0 && size <= maximumBytes && maximumBytes > 0) synchronized(this) {
            checkCancelled()
            entries.remove(key)?.let { bytes -= it.samples.size.toLong() * Float.SIZE_BYTES }
            while ((bytes + size > maximumBytes || entries.size >= MAXIMUM_ENTRIES) && entries.isNotEmpty()) {
                val oldest = entries.entries.iterator()
                bytes -= oldest.next().value.samples.size.toLong() * Float.SIZE_BYTES
                oldest.remove()
            }
            entries[key] = rendered.ownedCopy()
            bytes += size
        }
        return rendered
    }

    private fun Snip.ownedCopy() = copy(samples = samples.copyOf())
}
