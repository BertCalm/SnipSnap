package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** STEADY keeps the relationship fixed; GATHER authors a finite solo, argument, then gathering. */
enum class OhmGesture { STEADY, GATHER }

/** Versioned OHM renderer: saved model-1 recipes keep their original sound. */
object Ohm {
    const val MODEL_VERSION = 2
    const val ROOT_MIDI = 60
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72

    val MACROS: List<MacroSpec> get() = OhmV2.MACROS
    fun defaults(): Map<String, Float> = OhmV2.defaults()
    fun supportsModel(model: Int) = model in 1..MODEL_VERSION

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = MODEL_VERSION,
    ): Snip {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        return if (model == 1) OhmV1.renderRaw(macros, midi, velocity, seed, gesture)
        else OhmV2.renderRaw(macros, midi, velocity, seed, gesture)
    }

    fun render(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = MODEL_VERSION,
    ): Snip {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        return if (model == 1) OhmV1.render(macros, midi, velocity, seed, gesture)
        else OhmV2.render(macros, midi, velocity, seed, gesture)
    }

    internal fun probe(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY,
        companionExcitation: Boolean = true, recordPorts: Boolean = true,
    ) = OhmV2.probe(macros, midi, velocity, seed, gesture, companionExcitation, recordPorts)

    internal fun relationship(progress: Double, meddling: Float, commune: Float, gesture: OhmGesture) =
        OhmV2.relationship(progress, meddling, commune, gesture)
}
