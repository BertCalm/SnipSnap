package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * One completed dry render, shared by preview and render-to-pad. This saves exact repeats;
 * a changed physical control still computes a fresh trajectory. No simulation history or
 * working solver is retained, and no source labels or display names enter the sonic key.
 *
 * The cached buffer is private. Both a cache hit and a fresh render return an owned copy,
 * so downstream effects, playback and callers cannot change the next request's dry source.
 */
internal object ScannedChainRenderCache {
    const val MAX_CACHED_SAMPLES = Dsp.RATE * 4

    private data class Key(
        val model: Int,
        val voice: ScannedChainVoice,
        val macroBits: List<Int>,
        val pointBits: List<Int>,
        val config: ScannedChainConfig,
        val midi: Int,
        val velocityBits: Int,
    )

    private data class Entry(val key: Key, val samples: FloatArray, val channels: Int, val sampleRate: Int)
    private data class Lookup(val entry: Entry?, val generation: Long)

    internal data class Stats(val hits: Long, val misses: Long, val publications: Long, val retainedSamples: Int)

    private val lock = Any()
    private var completed: Entry? = null
    private var generation = 0L
    private var hits = 0L
    private var misses = 0L
    private var publications = 0L

    fun render(
        model: Int,
        voice: ScannedChainVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float,
        shape: ScannedChainShape,
        config: ScannedChainConfig,
        renderer: () -> Snip,
    ): Snip {
        ScannedChain.checkCancellation()
        val key = key(model, voice, macros, midi, velocity, shape, config)
        val lookup = synchronized(lock) {
            ScannedChain.checkCancellation()
            val entry = completed?.takeIf { it.key == key }
            if (entry == null) misses++ else hits++
            Lookup(entry, generation)
        }
        lookup.entry?.let { entry ->
            val owned = entry.samples.copyOf()
            ScannedChain.checkCancellation()
            return Snip(owned, entry.channels, entry.sampleRate)
        }

        // A new render never holds the cache lock. An unrelated request can still reuse
        // the preceding completed source, and concurrent misses own separate solver state.
        val rendered = renderer()
        ScannedChain.checkCancellation()
        val snapshot = rendered.samples.copyOf()
        ScannedChain.checkCancellation()
        val owned = snapshot.copyOf()
        ScannedChain.checkCancellation()
        if (rendered.channels == 1 && rendered.sampleRate == Dsp.RATE && snapshot.size <= MAX_CACHED_SAMPLES) {
            val candidate = Entry(key, snapshot, rendered.channels, rendered.sampleRate)
            synchronized(lock) {
                ScannedChain.checkCancellation()
                // clear() also invalidates work that was already running when it was called.
                if (lookup.generation == generation) {
                    completed = candidate
                    publications++
                }
            }
        }
        ScannedChain.checkCancellation()
        return Snip(owned, rendered.channels, rendered.sampleRate)
    }

    internal fun clear() = synchronized(lock) {
        completed = null
        generation++
        hits = 0
        misses = 0
        publications = 0
    }

    internal fun stats(): Stats = synchronized(lock) {
        Stats(hits, misses, publications, completed?.samples?.size ?: 0)
    }

    private fun key(
        model: Int,
        voice: ScannedChainVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float,
        shape: ScannedChainShape,
        config: ScannedChainConfig,
    ): Key {
        require(model > 0) { "a scanned-chain render cache key needs a model" }
        require(midi in ScannedChain.MIDI_MIN..ScannedChain.MIDI_MAX) { "SCANNED CHAIN MIDI must be 36..96" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SCANNED CHAIN velocity must be 0..1" }
        config.validateForVoice(voice)
        val defaults = ScannedChain.defaults(voice)
        for ((name, value) in macros) {
            require(name in defaults) { "unknown SCANNED CHAIN macro $name" }
            require(value.isFinite() && value in 0f..1f) { "SCANNED CHAIN $name must be 0..1" }
        }
        val resolved = defaults + macros
        return Key(model, voice, defaults.keys.map { resolved.getValue(it).toRawBits() },
            shape.points.map { it.toRawBits() }, config, midi, velocity.toRawBits())
    }
}
