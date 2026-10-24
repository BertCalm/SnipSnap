package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Once-only source/reflected balance for the frozen model-4 palette. Source and reflected
 * oscillators run from the same seeded phases at their actual fixed geometric frequencies.
 * The gesture changes their complementary amplitude shares before filtering, leaving the root
 * independent. MOTION selects gesture timing; it never also moves a fold axis in these modes.
 *
 * Two immutable, preconditioning basis sets are retained for fast timing/depth edits. Each has
 * root, source, and reflected samples at 4x; the worst two STILL entries occupy 16.94 MB.
 * The key includes every source/geometry quantity, and excludes timing, depth, and velocity.
 * No processed recording or mutable oscillator state is shared between render calls.
 */
object OrigamiGestureRenderer {
    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val BLOCK = 64
    private const val TAU = 2.0 * PI
    private const val LOG_1000 = 6.907755278982137

    private data class Key(val voice: OrigamiVoice, val midi: Int, val seed: Long,
        val crease: Float, val turn: Float, val warp: Float)

    private data class Basis(val root: FloatArray, val dry: FloatArray, val wet: FloatArray) {
        val bytes get() = (root.size.toLong() + dry.size + wet.size) * Float.SIZE_BYTES
    }

    private data class Oscillator(val hz: Double, val phase: Double, val amplitude: Double,
        val partial: Int, val destination: Int)

    private val cacheLock = ReentrantLock()
    private val cache = LinkedHashMap<Key, Basis>(3, .75f, true)

    data class CacheStats(val entryCount: Int, val bytes: Long)

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw CancellationException("ORIGAMI fold gesture cancelled")
        }
    }

    private fun lock(cancelled: () -> Boolean) {
        while (true) {
            stop(cancelled)
            try {
                if (cacheLock.tryLock(10, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("ORIGAMI gesture cache wait cancelled")
            }
        }
    }

    fun clearCache() {
        cacheLock.lock()
        try { cache.clear() } finally { cacheLock.unlock() }
    }

    internal fun cacheEntryCount(): Int {
        cacheLock.lock()
        return try { cache.size } finally { cacheLock.unlock() }
    }

    internal fun cacheBytes(): Long {
        cacheLock.lock()
        return try { cache.values.sumOf { it.bytes } } finally { cacheLock.unlock() }
    }

    fun cacheStats(): CacheStats {
        cacheLock.lock()
        return try { CacheStats(cache.size, cache.values.sumOf { it.bytes }) } finally { cacheLock.unlock() }
    }

    private fun basis(key: Key, cancelled: () -> Boolean): Basis {
        lock(cancelled)
        try {
            cache[key]?.let { return it }
            // Evict before allocating a third bank. Building while holding this cancellably
            // acquired lock also prevents duplicate cold renders from multiplying working sets.
            if (cache.size == 2) cache.entries.iterator().run { next(); remove() }
            val value = buildBasis(key, cancelled)
            stop(cancelled)
            cache[key] = value
            return value
        } finally { cacheLock.unlock() }
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    /** The accepted finite contours remain separate from the new spectral-balance envelope. */
    private fun amplitude(voice: OrigamiVoice, partial: Int, time: Double): Double {
        val duration = Origami.durationSeconds(voice)
        if (time <= 0.0 || time >= duration) return 0.0
        return when (voice) {
            OrigamiVoice.CRANE -> {
                val decay = if (partial < 0) 3.0 else 3.1 / (1.0 + .085 * partial)
                smooth(time / .0025) * exp(-LOG_1000 * time / decay) * smooth((duration - time) / .04)
            }
            OrigamiVoice.FAN -> smooth(time / .035) * smooth((duration - time) / .8)
            OrigamiVoice.STILL -> smooth(time / .48) * smooth((duration - time) / 1.0)
        }
    }

    private fun buildBasis(key: Key, cancelled: () -> Boolean): Basis {
        val macros = mapOf("CREASE" to key.crease, "TURN" to key.turn, "WARP" to key.warp,
            "FOLD" to 1f, "MOTION" to 0f)
        // This public model-4 probe is authoritative for each voice's source and pickup law.
        val partials = Origami.partials(key.voice, macros, key.midi, key.seed, model = 4)
        val random = Random(Dsp.seedFor("ORIGAMI", 1, key.voice.name, key.seed))
        if (key.voice == OrigamiVoice.STILL) repeat(128) { random.nextDouble() }
        val phases = DoubleArray(partials.size) { random.nextDouble() * TAU }
        val rootAmplitude = when (key.voice) {
            OrigamiVoice.CRANE -> .14
            OrigamiVoice.FAN -> .12
            OrigamiVoice.STILL -> .09
        }
        val oscillators = ArrayList<Oscillator>(1 + partials.size * 2)
        oscillators += Oscillator(Keys.midiHz(key.midi).toDouble(), 0.0, rootAmplitude, -1, 0)
        partials.forEachIndexed { index, partial ->
            if (partial.sourceAmplitude > 0.0) oscillators +=
                Oscillator(partial.sourceHz.coerceIn(0.0, 18_000.0), phases[index], partial.sourceAmplitude, index, 1)
            if (partial.wetAmplitude > 0.0) oscillators +=
                Oscillator(partial.frequencyHz.coerceIn(0.0, 18_000.0), phases[index], partial.wetAmplitude, index, 2)
        }
        val frames = (Origami.durationSeconds(key.voice) * RATE).roundToInt() * Dsp.OVERSAMPLE
        val root = FloatArray(frames)
        val dry = FloatArray(frames)
        val wet = FloatArray(frames)
        val count = oscillators.size
        val oscillatorSin = DoubleArray(count) { sin(oscillators[it].phase) }
        val oscillatorCos = DoubleArray(count) { cos(oscillators[it].phase) }
        val stepSin = DoubleArray(count) { sin(TAU * oscillators[it].hz / INTERNAL_RATE) }
        val stepCos = DoubleArray(count) { cos(TAU * oscillators[it].hz / INTERNAL_RATE) }
        val gains = DoubleArray(count)
        val increments = DoubleArray(count)
        for (start in 0 until frames step BLOCK) {
            stop(cancelled)
            val length = minOf(BLOCK, frames - start)
            val time = start.toDouble() / INTERNAL_RATE
            val next = (start + length).toDouble() / INTERNAL_RATE
            for (p in 0 until count) {
                val oscillator = oscillators[p]
                gains[p] = oscillator.amplitude * amplitude(key.voice, oscillator.partial, time)
                increments[p] = (oscillator.amplitude * amplitude(key.voice, oscillator.partial, next) - gains[p]) / length
                // Exact rephasing bounds recursion error without restarting any carrier.
                if (start % 4096 == 0) {
                    val phase = oscillator.phase + TAU * oscillator.hz * time
                    oscillatorSin[p] = sin(phase); oscillatorCos[p] = cos(phase)
                }
            }
            for (offset in 0 until length) {
                var rootSample = 0.0; var drySample = 0.0; var wetSample = 0.0
                for (p in 0 until count) {
                    val sample = oscillatorSin[p] * gains[p]
                    when (oscillators[p].destination) {
                        0 -> rootSample += sample
                        1 -> drySample += sample
                        else -> wetSample += sample
                    }
                    val s = oscillatorSin[p] * stepCos[p] + oscillatorCos[p] * stepSin[p]
                    oscillatorCos[p] = oscillatorCos[p] * stepCos[p] - oscillatorSin[p] * stepSin[p]
                    oscillatorSin[p] = s
                    gains[p] += increments[p]
                }
                root[start + offset] = rootSample.toFloat()
                dry[start + offset] = drySample.toFloat()
                wet[start + offset] = wetSample.toFloat()
            }
        }
        return Basis(root, dry, wet)
    }

    fun renderRaw(
        patch: OrigamiPatch,
        midi: Int? = patch.midi,
        diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        stop(cancelled)
        val macros = patch.resolvedMacros()
        val played = midi ?: Origami.midiFor(patch.voice, macros.getValue("TUNE"))
        if (patch.gesture == OrigamiGesture.STEADY || macros.getValue("FOLD") == 0f ||
            patch.velocity == 0f || diagnostic == OrigamiDiagnostic.ROOT_ONLY || diagnostic == OrigamiDiagnostic.SOURCE) {
            // Existing identities, zero depth, and diagnostic references remain literal legacy paths.
            return OrigamiHeld.reference(patch.voice, played, cancelled) {
                Origami.renderRaw(patch.voice, patch.macros, patch.velocity, midi, patch.seed, diagnostic, patch.model)
            }
        }
        require(patch.model == OrigamiGestures.GEOMETRY_MODEL) { "ORIGAMI gestures require model 4" }
        require(played in Origami.MIDI_MIN..Origami.MIDI_MAX) { "ORIGAMI MIDI must be ${Origami.MIDI_MIN}..${Origami.MIDI_MAX}" }
        val basis = basis(Key(patch.voice, played, patch.seed, macros.getValue("CREASE"),
            macros.getValue("TURN"), macros.getValue("WARP")), cancelled)
        val envelope = OrigamiGestures.envelope(patch)
        val raw = FloatArray(basis.root.size)
        for (start in raw.indices step BLOCK) {
            stop(cancelled)
            val length = minOf(BLOCK, raw.size - start)
            var depth = envelope.depthAt(start.toDouble() / INTERNAL_RATE)
            val increment = (envelope.depthAt((start + length).toDouble() / INTERNAL_RATE) - depth) / length
            for (offset in 0 until length) {
                val i = start + offset
                val upper = basis.dry[i] * (1.0 - depth) + basis.wet[i] * depth
                raw[i] = ((if (diagnostic == OrigamiDiagnostic.FOLDED_ONLY) upper else basis.root[i] + upper) * patch.velocity).toFloat()
                depth += increment
            }
        }
        val out = OrigamiHeld.decimate(raw, cancelled)
        val highPass = Dsp.OnePole(RATE)
        for (i in out.indices) {
            if (i % 4096 == 0) stop(cancelled)
            out[i] -= highPass.lp(out[i], 8f)
        }
        Dsp.fadeTail(out)
        stop(cancelled)
        return out
    }

    fun render(patch: OrigamiPatch, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        if (patch.gesture == OrigamiGesture.STEADY || patch.resolvedMacros().getValue("FOLD") == 0f) {
            val played = patch.midi ?: Origami.midiFor(patch.voice, patch.resolvedMacros().getValue("TUNE"))
            val audio = OrigamiHeld.reference(patch.voice, played, cancelled) {
                Origami.render(patch.voice, patch.macros, patch.velocity, patch.midi, patch.seed, patch.model).samples
            }
            return Snip(audio, 1, RATE)
        }
        val raw = renderRaw(patch, cancelled = cancelled)
        if (patch.velocity > 0f) Dsp.levelTo(raw, RATE, Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.pow(.65f))
        stop(cancelled)
        return Snip(raw, 1, RATE)
    }
}
