package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException

/**
 * A bench-local cache, never a saved part of the instrument. Revisited notes reuse
 * audio; root, touch and resonance edits reuse trajectories. Physics edits get a
 * fresh population. Finite notes and held loops use the same renderers as [LibrationPatch].
 *
 * Audio has a byte budget; at most two populations survive a slider drag. Release
 * the session with its screen. Calls are serialized so cancelled previews cannot
 * race a replacement or publish a partly rendered cache entry.
 */
class LibrationRenderSession(
    private val maxAudioBytes: Long = 4L * 1024 * 1024,
    private val maxPopulations: Int = 2,
) {
    init {
        require(maxAudioBytes >= 0)
        require(maxPopulations >= 0)
    }

    data class Timing(
        val totalMilliseconds: Double,
        val physicsMilliseconds: Double,
        val audioMilliseconds: Double,
        val audioCacheHit: Boolean,
        val populationCacheHit: Boolean,
    )

    data class Render(val snip: Snip, val timing: Timing)

    private data class AudioKey(
        val voice: LibrationVoice, val macros: Map<String, Float>, val midi: Int,
        val velocity: Float, val model: Int, val seconds: Double, val heldModel: Int,
    )

    private val populations = LinkedHashMap<Libration.PopulationKey, Libration.Population>(4, .75f, true)
    private val audio = LinkedHashMap<AudioKey, Snip>(8, .75f, true)
    private var audioBytes = 0L

    fun render(patch: LibrationPatch): Snip = renderTimed(patch).snip

    @Synchronized
    fun renderTimed(patch: LibrationPatch): Render {
        val began = System.nanoTime()
        checkCancelled()
        // Snapshot the caller's map: a mutable recipe map must never change a key.
        val held = LibrationKeys.isHeld(patch.macros["HOLD"] ?: 0f)
        val macros = Libration.settled(patch.voice, patch.macros + ("HOLD" to 0f))
        val seconds = if (held) LibrationKeys.SOURCE_SECONDS else patch.sourceSeconds
        val key = AudioKey(patch.voice, macros, patch.midi, patch.velocity, patch.model, seconds,
            if (held) patch.heldModel else 0)
        audio[key]?.let {
            // Snip exposes a mutable FloatArray. A player or processing stage may
            // edit its copy without corrupting the next recall of this sound.
            return Render(it.copy(samples = it.samples.copyOf()), Timing(elapsed(began), 0.0, 0.0, true, false))
        }
        val options = Libration.Options(seconds = seconds)
        val populationKey = Libration.populationKey(patch.voice, macros, options, patch.model)
        val cached = populations[populationKey]
        val physicsBegan = System.nanoTime()
        val population = cached ?: Libration.population(patch.voice, macros, options, patch.model)
        val physicsMs = if (cached == null) elapsed(physicsBegan) else 0.0
        checkCancelled()
        val audioBegan = System.nanoTime()
        val result = if (held) LibrationKeys.padLoop(LibrationHeld.probe(
            patch.copy(macros = macros + ("HOLD" to 1f)), population, { false }).loop)
            else Libration.probe(patch.voice, macros, patch.midi, patch.velocity, options, population, patch.model).samples
        val audioMs = elapsed(audioBegan)
        checkCancelled()
        if (maxPopulations > 0) {
            populations[populationKey] = population
            while (populations.size > maxPopulations) populations.remove(populations.keys.first())
        }
        val bytes = result.samples.size.toLong() * Float.SIZE_BYTES
        if (bytes <= maxAudioBytes) {
            audio[key] = result.copy(samples = result.samples.copyOf())
            audioBytes += bytes
            while (audioBytes > maxAudioBytes) {
                val eldest = audio.keys.first()
                audioBytes -= audio.remove(eldest)!!.samples.size.toLong() * Float.SIZE_BYTES
            }
        }
        return Render(result, Timing(elapsed(began), physicsMs, audioMs, false, cached != null))
    }

    @Synchronized
    fun clear() {
        audio.clear()
        populations.clear()
        audioBytes = 0
    }

    private fun elapsed(began: Long) = (System.nanoTime() - began) / 1e6

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Libration preview cancelled")
    }
}
