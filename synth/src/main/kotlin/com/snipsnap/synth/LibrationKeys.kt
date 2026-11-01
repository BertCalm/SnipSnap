package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException

/** Independently versioned recurring readout of the accepted finite physical source. */
object LibrationKeys {
    const val MODEL_VERSION = 1
    const val SOURCE_SECONDS = 6.0

    data class Diagnostics(
        val seamError: Double, val cycleError: Double, val phaseError: Double, val stateError: Double,
        val rootCents: Double, val maxCorrectionHz: Double, val rawPeak: Double,
        val totalMilliseconds: Double, val particleCount: Int, val sourceJacobiDrift: Double,
    )
    data class Rendered(val note: KeyNote, val diagnostics: Diagnostics)

    fun supports(patch: LibrationPatch): Boolean = patch.model == 3
    fun isHeld(hold: Float): Boolean = hold == 1f
    fun drumClass(voice: LibrationVoice, macros: Map<String, Float>): DrumClass =
        if (isHeld(macros["HOLD"] ?: 0f)) DrumClass.LOOP else Libration.drumClassFor(voice, macros)

    /** Sample only the validated register, with the saved root among the minor-third zones. */
    fun zoneMidis(rootMidi: Int): List<Int> {
        require(rootMidi in Libration.MIN_MIDI..Libration.MAX_MIDI)
        val low = maxOf(Libration.MIN_MIDI, rootMidi - 12)
        val high = minOf(Libration.MAX_MIDI, rootMidi + 12)
        return ((-12..12 step 3).map { rootMidi + it }.filter { it in low..high } +
            listOf(low, rootMidi, high)).distinct().sorted()
    }

    fun note(patch: LibrationPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): KeyNote =
        Session().note(patch, midi, cancelled)

    fun loop(patch: LibrationPatch, cancelled: () -> Boolean = { false }): Snip =
        Session().loop(patch, cancelled)

    internal fun padLoop(samples: FloatArray): Snip {
        var cut = 0
        for (i in samples.indices) if (kotlin.math.abs(samples[i]) < kotlin.math.abs(samples[cut])) cut = i
        return Snip(FloatArray(samples.size) { samples[(cut + it) % samples.size] }, 1, Dsp.RATE)
    }

    /** One source table per generation session. No cache becomes part of a saved recipe. */
    class Session {
        private var population: Libration.Population? = null

        @Synchronized
        internal fun probe(patch: LibrationPatch, cancelled: () -> Boolean = { false },
            oversample: Int = 4, normalize: Boolean = true, fitPhases: Boolean = true,
            enforceClosure: Boolean = true): LibrationHeld.Probe {
            require(supports(patch)) { "LIBRATION keys require finite source model 3" }
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("LIBRATION keys cancelled")
            val source = patch.copy(macros = patch.macros.toMap())
            val controls = source.macros + ("HOLD" to 0f)
            val options = Libration.Options(seconds = SOURCE_SECONDS)
            val key = Libration.populationKey(source.voice, controls, options, source.model)
            val cached = population?.takeIf { it.key == key }
            val prepared = cached ?: Libration.population(source.voice, controls, options, source.model)
            val result = LibrationHeld.probe(source, prepared, cancelled, oversample, normalize, fitPhases, enforceClosure)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("LIBRATION keys cancelled")
            population = prepared
            return result
        }

        @Synchronized
        fun render(patch: LibrationPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): Rendered {
            val began = System.nanoTime()
            val p = probe(patch.copy(midi = midi), cancelled)
            return Rendered(p.note, Diagnostics(p.seamError, p.cycleError, p.phaseError, p.stateError,
                p.rootCents, p.maxCorrectionHz, p.rawPeak, (System.nanoTime() - began) / 1e6,
                p.particleCount, p.sourceJacobiDrift))
        }

        fun note(patch: LibrationPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): KeyNote =
            render(patch, midi, cancelled).note

        fun loop(patch: LibrationPatch, cancelled: () -> Boolean = { false }): Snip {
            val samples = probe(patch, cancelled).loop
            // Rotate to a quiet trigger; no repeated sample is faded or overwritten.
            return padLoop(samples)
        }
    }
}
