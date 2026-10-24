package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Playable RIBBON capture, using the endpoint search heard in [FlockRibbonSustain].
 * Two seconds of genuine onset precede a roughly six-second captured movement. Its
 * final half-second blends toward the source immediately before the loop start;
 * the loop ends in that source's natural one-sample context. The flock is captured,
 * not made periodic. Note-off release belongs to the sample player.
 *
 * Only 8.1 seconds plus 16 output frames of decimator context are simulated, using
 * FLOCK's one Cloud/carrier implementation. Level is measured on this capture and
 * applied once to onset and body, preserving their relationship. This deliberately
 * differs from the audition's common gain measured on a 31.5-second comparison.
 */
object FlockHeld {
    const val VERSION = 1
    const val MIN_RELEASE_SECONDS = .1f
    const val MAX_RELEASE_SECONDS = 1.5f
    const val DEFAULT_RELEASE_SECONDS = .6f
    const val CACHE_MAX_ENTRIES = 12
    const val CACHE_MAX_BYTES = 16 * 1024 * 1024
    private const val LOOP_SECONDS = 6.0
    private const val CROSSFADE_SECONDS = .5
    // Cascaded 2:1 kernels need 48 oversampled samples (12 output frames) to
    // the right. Sixteen output frames keep every returned endpoint tap native.
    private const val CONTEXT_FRAMES = 16

    data class Diagnostics(
        val sourceFrames: Int,
        val commonGain: Double,
        val outputPeak: Double,
        val blendCorrelation: Double,
        val endpointSearch: FlockRibbonSustain.EndpointSearch,
    )

    data class Held(
        val snip: Snip,
        val loopStartFrame: Int,
        val loopEndExclusive: Int,
        val diagnostics: Diagnostics,
    )

    private data class Key(
        val macros: List<Int>, val midi: Int, val velocity: Int, val seed: Long,
        val model: Int, val version: Int, val oversample: Int,
    )

    private data class Capture(val samples: FloatArray, val loopStart: Int, val diagnostics: Diagnostics) {
        val bytes get() = samples.size * Float.SIZE_BYTES
        // Audio containers expose mutable arrays. Never lend the cache's only copy.
        fun ownedCopy() = Held(Snip(samples.copyOf(), channels = 1, sampleRate = Dsp.RATE),
            loopStart, samples.size, diagnostics)
    }

    private val cache = LinkedHashMap<Key, Capture>(16, .75f, true)
    private var cacheBytes = 0
    internal data class CacheSize(val entries: Int, val bytes: Int)
    internal fun cacheSize(): CacheSize = synchronized(cache) { CacheSize(cache.size, cacheBytes) }
    internal fun clearCache() = synchronized(cache) { cache.clear(); cacheBytes = 0 }

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("FLOCK held render no longer wanted")
    }

    private fun validate(patch: FlockPatch, midi: Int) {
        require(patch.playback == FlockPlayback.HELD && patch.voice == FlockVoice.RIBBON) { "FLOCK held capture requires held RIBBON" }
        require(midi in Flock.MIN_MIDI..Flock.MAX_MIDI) { "FLOCK midi must be ${Flock.MIN_MIDI}..${Flock.MAX_MIDI}" }
    }

    /** Nine native roots, including the saved root, shifted inward at the supported register edges. */
    fun midis(patch: FlockPatch): List<Int> {
        validate(patch, patch.midi)
        val remainder = (patch.midi - Flock.MIN_MIDI) % 3
        val low = Flock.MIN_MIDI + remainder
        val high = Flock.MAX_MIDI - 24 - ((Flock.MAX_MIDI - 24 - low) % 3)
        val first = (patch.midi - 12).coerceIn(low, high)
        return (0..8).map { first + it * 3 }
    }

    /** Complete attack-plus-loop sample, with the loop ending exactly at the file end. */
    fun render(patch: FlockPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): Held {
        validate(patch, midi)
        stop(cancelled)
        val m = Flock.settled(patch.macros, patch.voice)
        val key = Key(listOf("SPREAD", "PULL", "FLOW", "SCATTER", "EDGE").map { m.getValue(it).toBits() },
            midi, patch.velocity.toBits(), patch.seed, patch.model, patch.heldVersion, Dsp.OVERSAMPLE)
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) {
            val result = hit.ownedCopy()
            stop(cancelled)
            return result
        }
        val rendered = capture(patch, midi, cancelled, Dsp.OVERSAMPLE)
        stop(cancelled)
        synchronized(cache) {
            cache.put(key, rendered)?.let { cacheBytes -= it.bytes }
            cacheBytes += rendered.bytes
            while (cache.size > CACHE_MAX_ENTRIES || cacheBytes > CACHE_MAX_BYTES) {
                val oldest = cache.entries.iterator()
                val entry = oldest.next()
                cacheBytes -= entry.value.bytes
                oldest.remove()
            }
        }
        val result = rendered.ownedCopy()
        stop(cancelled)
        return result
    }

    /** Analysis/test path bypasses cache; native-rate probes cannot contaminate production entries. */
    internal fun renderUncached(patch: FlockPatch, midi: Int = patch.midi, oversample: Int = 4,
        cancelled: () -> Boolean = { false }): Held {
        validate(patch, midi)
        return capture(patch, midi, cancelled, oversample).ownedCopy()
    }

    private fun capture(patch: FlockPatch, midi: Int, cancelled: () -> Boolean, oversample: Int): Capture {
        stop(cancelled)
        val intro = (FlockRibbonSustain.INTRO_SECONDS * Dsp.RATE).roundToInt()
        val nominal = (LOOP_SECONDS * Dsp.RATE).roundToInt()
        val radius = (FlockRibbonSustain.ENDPOINT_SEARCH_SECONDS * Dsp.RATE).roundToInt()
        val blend = (CROSSFADE_SECONDS * Dsp.RATE).roundToInt()
        val sourceFrames = intro + nominal + radius + CONTEXT_FRAMES
        val source = Flock.captureContinuousRibbon(sourceFrames, patch.macros, midi, patch.velocity, patch.seed,
            patch.model, cancelled, oversample)
        // A virtual long hold retains the audition's exact endpoint bounds without
        // rendering the subsequent repeated or continuous comparison audio.
        val (selected, search) = FlockRibbonSustain.selectEndpoint(source, intro, nominal, blend, radius,
            held = 30 * Dsp.RATE, cancelled = cancelled)
        val samples = source.copyOfRange(0, intro + selected.frames)
        selected.blend.copyInto(samples, samples.size - blend)
        val attack = (FlockRibbonSustain.ATTACK_SECONDS * Dsp.RATE).roundToInt()
        var peak = 0.0
        for (i in samples.indices) {
            if (i % 4096 == 0) stop(cancelled)
            val u = (i.toDouble() / attack).coerceIn(0.0, 1.0)
            samples[i] = (samples[i] * (u * u * (3.0 - 2.0 * u))).toFloat()
            check(samples[i].isFinite()) { "FLOCK held capture produced non-finite audio" }
            peak = max(peak, abs(samples[i].toDouble()))
        }
        samples[0] = 0f
        stop(cancelled)
        val measured = Loudness.of(Snip(samples, channels = 1, sampleRate = Dsp.RATE)).toDouble()
        val wanted = Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.toDouble().pow(.65)
        val gain = if (measured > 1e-6) minOf(wanted / measured, .90 / peak) else 1.0
        var outputPeak = 0.0
        for (i in samples.indices) {
            if (i % 4096 == 0) stop(cancelled)
            samples[i] = (samples[i] * gain).toFloat()
            outputPeak = max(outputPeak, abs(samples[i].toDouble()))
        }
        stop(cancelled)
        return Capture(samples, intro, Diagnostics(sourceFrames, gain, outputPeak, selected.correlation, search))
    }
}
