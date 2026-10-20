package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.DrumClass
import kotlin.random.Random

/** Six audible delay lines, with topology-specific scattering. */
enum class OverlapTopology { SELF, TREFOIL, MIRROR, TORUS }

/**
 * Versioned finite OVERLAP prototype. Model 1 preserves the first audition;
 * model 2 uses orthogonal scattering, separate cycle families and phase-driven excitation.
 */
object Overlap {
    const val MODEL_VERSION = 2
    const val MIN_MIDI = 24
    const val MAX_MIDI = 84
    const val SAMPLE_RATE = WavWriter.MPC_SAMPLE_RATE

    data class Diagnostics(
        val rawPeak: Double, val rawRms: Double, val rawDc: Double,
        val earlyRms: Double, val lateRms: Double,
        val phaseOrderStart: Double, val phaseOrderEnd: Double,
        val delaySamples: List<Double>, val maxLoopGain: Double,
        val maxBufferAbs: Double, val targetHz: Double, val excitationSeconds: Double,
        val phaseOrderHistory: List<Double>,
    )

    /** Raw: antialias-filtered, DC-blocked audio, BEFORE tail fade and house leveling. */
    data class Probe(
        val raw: FloatArray, val samples: FloatArray, val sampleRate: Int,
        val diagnostics: Diagnostics, val channels: Int = 1,
    )

    fun rootMidi(topology: OverlapTopology): Int = 48
    fun macrosFor(topology: OverlapTopology, model: Int = MODEL_VERSION): List<MacroSpec> = when (model) {
        1 -> OverlapV1.macrosFor(topology)
        2 -> OverlapV2.macrosFor(topology)
        else -> throw IllegalArgumentException("unsupported OVERLAP model $model")
    }
    fun defaults(topology: OverlapTopology, model: Int = MODEL_VERSION): Map<String, Float> =
        macrosFor(topology, model).associate { it.name to it.default }

    /** All renders have a finite ending, including maximum HOLD. */
    fun drumClassFor(topology: OverlapTopology, macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL

    fun scramble(topology: OverlapTopology, random: Random, temperature: Float = .35f): Map<String, Float> =
        Dsp.scrambleNear(defaults(topology), (temperature.takeIf { it.isFinite() } ?: .35f).coerceIn(0f, 1f), random)

    fun probe(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(topology), velocity: Float = 1f, seconds: Double? = null,
        model: Int = MODEL_VERSION,
    ): Probe = when (model) {
        1 -> OverlapV1.probe(topology, macros, midi, velocity, seconds)
        2 -> OverlapV2.probe(topology, macros, midi, velocity, seconds)
        else -> throw IllegalArgumentException("unsupported OVERLAP model $model")
    }

    fun render(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(topology), velocity: Float = 1f, seconds: Double? = null,
        model: Int = MODEL_VERSION,
    ): Snip = when (model) {
        1 -> OverlapV1.render(topology, macros, midi, velocity, seconds)
        2 -> OverlapV2.render(topology, macros, midi, velocity, seconds)
        else -> throw IllegalArgumentException("unsupported OVERLAP model $model")
    }
}
