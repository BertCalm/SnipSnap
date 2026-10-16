package com.snipsnap.synth

import com.snipsnap.audio.Snip

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
    const val SAMPLE_RATE = 44_100

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
    fun macrosFor(topology: OverlapTopology): List<MacroSpec> = OverlapV2.macrosFor(topology)
    fun defaults(topology: OverlapTopology): Map<String, Float> = OverlapV2.defaults(topology)

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
