package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs

/**
 * The approved steady MPC articulation, shared by phone preview and keygroup export.
 * A 20 ms ramp changes only the first copy of the settled period; the second copy
 * remains the whole measured sustain. Note-off belongs to the sample player.
 *
 * Native prototype leveling happens once before assembly. An only-down scalar
 * min(1, .9/nativePeak) protects stronger captures without releveling either copy.
 * The approved velocity-.8 trial had collection gain 1, and keeps those samples.
 * Cold recurrence runs outside the cache lock. The fixed 1.5-second source period,
 * source algorithm and articulation are identified together by VERSION.
 */
object OverlapHeld {
    const val VERSION = 1
    const val DEFAULT_RELEASE_SECONDS = .6f
    const val MIN_RELEASE_SECONDS = .1f
    const val MAX_RELEASE_SECONDS = 1.5f
    const val CACHE_MAX_ENTRIES = 12
    const val CACHE_MAX_BYTES = 8 * 1024 * 1024
    const val PERIOD_SECONDS = 1.5
    const val ONSET_SECONDS = .020

    data class Diagnostics(
        val sourceDiagnostics: OverlapHeldPrototype.Diagnostics,
        val gain: Double,
        val outputPeak: Double,
    )

    data class Held(val snip: Snip, val loopStartFrame: Int, val loopEndExclusive: Int, val diagnostics: Diagnostics)

    fun defaults(topology: OverlapTopology): Map<String, Float> = linkedMapOf(
        "KNOT" to if (topology == OverlapTopology.SELF) 0f else 1f,
        "PULSE" to 0f, "LOCK" to 0f, "GLOW" to 0f, "RING" to .6f, "HOLD" to 1f,
    )

    /** RING is active everywhere; KNOT changes routed topologies. Release is a patch field. */
    fun macrosFor(topology: OverlapTopology): List<MacroSpec> = buildList {
        if (topology != OverlapTopology.SELF) add(MacroSpec("KNOT", defaults(topology).getValue("KNOT"), 0f))
        add(MacroSpec("RING", .6f, .4f))
    }

    fun supports(patch: OverlapPatch): Boolean = patch.playback == OverlapPlayback.HELD && patch.model == 2 &&
        patch.heldVersion == VERSION && (defaults(patch.topology) + patch.macros).let { m ->
            m.getValue("HOLD") == 1f && listOf("PULSE", "GLOW", "LOCK").all { m.getValue(it) == 0f }
        }

    /** Three octave-spaced native roots; preserve the selected reference at either edge. */
    fun midis(patch: OverlapPatch): List<Int> {
        require(supports(patch)) { "OVERLAP instruments require steady held playback" }
        val first = when {
            patch.midi < 36 -> patch.midi
            patch.midi > 72 -> patch.midi - 24
            else -> patch.midi - 12
        }
        return listOf(first, first + 12, first + 24)
    }

    private data class Key(val topology: OverlapTopology, val knot: Int, val ring: Int,
        val midi: Int, val velocity: Int, val model: Int, val version: Int, val oversample: Int)

    private data class Capture(val samples: FloatArray, val loopStart: Int, val diagnostics: Diagnostics) {
        val bytes get() = samples.size * Float.SIZE_BYTES
        fun ownedCopy(): Held {
            val source = diagnostics.sourceDiagnostics
            return Held(Snip(samples.copyOf(), 1, Dsp.RATE), loopStart, samples.size,
                diagnostics.copy(sourceDiagnostics = source.copy(delaySamples = source.delaySamples.toList())))
        }
    }

    private val cache = LinkedHashMap<Key, Capture>(16, .75f, true)
    private var bytes = 0
    private var generation = 0L
    private var hits = 0L
    private var misses = 0L
    internal data class CacheStats(val entries: Int, val bytes: Int, val hits: Long, val misses: Long)
    internal fun cacheStats(): CacheStats = synchronized(cache) { CacheStats(cache.size, bytes, hits, misses) }
    internal fun clearCache() = synchronized(cache) {
        cache.clear(); bytes = 0; hits = 0; misses = 0; generation++
    }

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("OVERLAP held render no longer wanted")
    }

    /** Returns caller-owned audio, including on cache hits. Release and name never alter source audio. */
    fun render(patch: OverlapPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): Held {
        require(supports(patch)) { "OVERLAP capture requires a model-2 steady held patch" }
        require(midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) { "OVERLAP held MIDI must be ${Overlap.MIN_MIDI}..${Overlap.MAX_MIDI}" }
        stop(cancelled)
        val m = defaults(patch.topology) + patch.macros
        val key = Key(patch.topology, m.getValue("KNOT").toBits(), m.getValue("RING").toBits(), midi,
            patch.velocity.toBits(), patch.model, patch.heldVersion, Dsp.OVERSAMPLE)
        val (observedGeneration, hit) = synchronized(cache) {
            val cached = cache[key]
            if (cached != null) hits++ else misses++
            generation to cached
        }
        if (hit != null) {
            val owned = hit.ownedCopy()
            stop(cancelled)
            return owned
        }
        val source = OverlapHeldPrototype.renderLoopMeasured(patch.topology, m, midi, patch.velocity,
            PERIOD_SECONDS, cancelled)
        stop(cancelled)
        val native = source.snip.samples
        val peak = native.maxOf { abs(it.toDouble()) }
        val gain = if (peak > 0.0) minOf(1.0, .90 / peak) else 1.0
        val body = FloatArray(native.size) { (native[it] * gain).toFloat() }
        val audio = body + body
        val ramp = (ONSET_SECONDS * Dsp.RATE).toInt()
        var outputPeak = 0.0
        for (i in audio.indices) {
            if (i % 4096 == 0) stop(cancelled)
            if (i < ramp) {
                val t = i.toDouble() / (ramp - 1)
                audio[i] = (audio[i] * t * t * (3.0 - 2.0 * t)).toFloat()
            }
            check(audio[i].isFinite()) { "OVERLAP held capture produced nonfinite audio" }
            outputPeak = maxOf(outputPeak, abs(audio[i].toDouble()))
        }
        val captured = Capture(audio, body.size, Diagnostics(
            source.diagnostics.copy(delaySamples = source.diagnostics.delaySamples.toList()), gain, outputPeak))
        val owned = captured.ownedCopy()
        stop(cancelled)
        synchronized(cache) {
            stop(cancelled)
            if (generation == observedGeneration) {
                cache.put(key, captured)?.let { bytes -= it.bytes }
                bytes += captured.bytes
                while (cache.size > CACHE_MAX_ENTRIES || bytes > CACHE_MAX_BYTES) {
                    val oldest = cache.entries.iterator()
                    bytes -= oldest.next().value.bytes
                    oldest.remove()
                }
            }
        }
        try {
            stop(cancelled)
        } catch (e: CancellationException) {
            synchronized(cache) {
                if (cache[key] === captured) { cache.remove(key); bytes -= captured.bytes }
            }
            throw e
        }
        return owned
    }
}
