package com.snipsnap.synth

import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Eight named factory sources from the palette audition. No source file or recording is needed for replay. */
object PrismMotionSources {
    data class Source(val id: String, val title: String, val category: String, val patch: Patch)

    private fun source(id: String, category: String, patch: Patch) = Source(id, patch.name, category, patch)

    private val choices: List<Source> = Collections.unmodifiableList(listOf(
        source("fork-soft-keys", "electric-keys", ForkPresets.forVoice(ForkVoice.TINE).first { it.name == "SOFT KEYS" }),
        source("fork-mellow-reed", "electric-keys", ForkPresets.forVoice(ForkVoice.REED).first { it.name == "MELLOW REED" }),
        source("tessera-wooden-arrival", "wood-strings", TesseraPresets.forVoice(TesseraVoice.WOOD).first { it.name == "Wooden Arrival" }),
        source("tessera-paired-wire", "wood-strings", TesseraPresets.forVoice(TesseraVoice.COURSE).first { it.name == "Paired Wire" }),
        source("embouchure-soft-entry", "breath-friction", EmbouchurePresets.forVoice(EmbouchureVoice.SOFT).first { it.name == "Soft Entry" }),
        source("embouchure-relaxed-bow", "breath-friction", EmbouchurePresets.forVoice(EmbouchureVoice.BOW).first { it.name == "Relaxed Bow" }),
        source("fathom-hollow-growl", "dark-saturated", FathomPresets.forVoice(FathomVoice.GRIND).first { it.name == "HOLLOW GROWL" }),
        source("fathom-driven-sub", "dark-saturated", FathomPresets.forVoice(FathomVoice.DEEP).first { it.name == "DRIVEN SUB" }),
    ))

    private val endpoints = EndpointCache(16) { patch, frequencies, cancelled ->
        checkCancelled(cancelled)
        val donor = patch.render()
        checkCancelled(cancelled)
        val result = PrismImprint.endpoint(donor, frequencies)
        checkCancelled(cancelled)
        result
    }

    fun all(): List<Source> = choices
    fun byId(id: String?): Source? = choices.firstOrNull { it.id == id }
    fun originalSource(patch: PrismPatch): Patch? = byId(patch.motion?.sourceId)?.patch

    /**
     * Blocking source preparation: call in runInterruptible on a worker dispatcher, never the UI thread.
     * A cache miss renders and analyzes once. Its full source recipe and all 32 carrier frequencies
     * form the key, so a changed note or source recipe cannot reuse the wrong fixed-Hz envelope.
     * The resulting A/B are saved in the PRISM recipe; sourceId only enables an original-source preview.
     */
    fun applySource(patch: PrismPatch, source: Source, cancelled: () -> Boolean = { false }): PrismPatch {
        checkCancelled(cancelled)
        require(choices.any { it === source }) { "Select a source from the PRISM factory catalog" }
        val frequencies = Prism.partialFrequencies(patch.voice, patch.macros, patch.template)
        val destination = endpoints.endpoint(source.patch, frequencies, cancelled)
        checkCancelled(cancelled)
        val controls = requireNotNull(PrismMotionEditor.ensureMotion(patch).motion)
        val selected = PrismMotionEditor.withMotion(patch, controls.copy(amplitudesB = destination,
            amplitudesA = PrismOpeningPresets.bodyAmplitudes(destination), sourceId = source.id))
        checkCancelled(cancelled)
        return selected
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("PRISM source preparation cancelled")
    }

    /** Single-flight, bounded LRU. Only 32 coefficients per entry are retained, never donor PCM. */
    internal class EndpointCache(
        private val capacity: Int,
        private val analyze: (Patch, List<Float>, () -> Boolean) -> List<Float>,
    ) {
        private data class Key(val recipe: String, val frequencies: List<Float>)
        private val lock = ReentrantLock()
        private val values = LinkedHashMap<Key, List<Float>>(16, .75f, true)

        init { require(capacity > 0) { "PRISM source cache capacity must be positive" } }

        fun endpoint(patch: Patch, frequencies: List<Float>, cancelled: () -> Boolean = { false }): List<Float> {
            checkCancelled(cancelled)
            require(frequencies.size == Prism.PARTIALS && frequencies.all { it.isFinite() && it > 0f }) {
                "PRISM source cache needs all 32 positive carrier frequencies"
            }
            val key = Key(patch.toJsonText(), frequencies.toList())
            // Interruptible waits also recheck a caller's cancellation callback while another source is prepared.
            try {
                while (!lock.tryLock(25, TimeUnit.MILLISECONDS)) checkCancelled(cancelled)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("PRISM source preparation cancelled")
            }
            try {
                checkCancelled(cancelled)
                values[key]?.let { return it }
                val result = Collections.unmodifiableList(analyze(patch, key.frequencies, cancelled).toList())
                checkCancelled(cancelled)
                require(result.size == Prism.PARTIALS && result.all { it.isFinite() && it in 0f..2f } &&
                    result.first() >= .05f) { "PRISM source analysis returned an invalid endpoint" }
                values[key] = result
                if (values.size > capacity) values.remove(values.keys.first())
                return result
            } finally {
                lock.unlock()
            }
        }
    }
}
