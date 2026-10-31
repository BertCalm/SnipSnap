package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.DrumClass

/** STEADY keeps the relationship fixed; GATHER authors a finite solo, argument, then gathering. */
enum class OhmGesture { STEADY, GATHER }

/** Versioned OHM renderer: saved recipes select their original synthesis and source path. */
object Ohm {
    const val MODEL_VERSION = 4
    /** The accepted finite-note instrument remains the default until new sources are auditioned. */
    const val DEFAULT_MODEL = 2
    const val ROOT_MIDI = 60
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72

    val MACROS: List<MacroSpec> get() = OhmV2.MACROS
    fun defaults(): Map<String, Float> = OhmV2.defaults()
    fun macrosFor(model: Int): List<MacroSpec> {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        return if (model >= 3) OhmV3.MACROS else MACROS
    }
    fun defaults(model: Int): Map<String, Float> = macrosFor(model).associate { it.name to it.default }
    fun supportsModel(model: Int) = model in 1..MODEL_VERSION

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = DEFAULT_MODEL,
    ): Snip = renderRaw(macros, midi, velocity, seed, gesture, model, OhmOptions())

    fun renderRaw(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions,
    ): Snip {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        require(model >= 3 || options == OhmOptions()) { "OHM source options require model 3 or later" }
        return when (model) {
            1 -> OhmV1.renderRaw(macros, midi, velocity, seed, gesture)
            2 -> OhmV2.renderRaw(macros, midi, velocity, seed, gesture)
            3 -> OhmV3.renderRaw(macros, midi, velocity, seed, gesture, options)
            else -> OhmV3.renderRaw(macros, midi, velocity, seed, gesture, options, textureRevision = model)
        }
    }

    fun render(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = DEFAULT_MODEL,
    ): Snip = render(macros, midi, velocity, seed, gesture, model, OhmOptions())

    fun render(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions,
    ): Snip {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        require(model >= 3 || options == OhmOptions()) { "OHM source options require model 3 or later" }
        return when (model) {
            1 -> OhmV1.render(macros, midi, velocity, seed, gesture)
            2 -> OhmV2.render(macros, midi, velocity, seed, gesture)
            3 -> OhmV3.render(macros, midi, velocity, seed, gesture, options)
            else -> OhmV3.render(macros, midi, velocity, seed, gesture, options, textureRevision = model)
        }
    }

    fun isHeld(patch: OhmPatch): Boolean = patch.model >= 3 && (patch.macros["HOLD"] ?: 0f) >= .99f

    fun drumClassFor(patch: OhmPatch): DrumClass = if (isHeld(patch)) DrumClass.LOOP else DrumClass.TONAL

    /** Native attack plus sustain; the existing instrument player owns note-off and release. */
    fun renderHeld(patch: OhmPatch, cancelled: () -> Boolean = { false }): KeyNote {
        require(patch.model >= 3) { "OHM instruments require model 3 or later" }
        return OhmV3.renderHeld(patch.macros + ("HOLD" to 1f), patch.midi, patch.velocity,
            patch.seed, patch.gesture, patch.options, cancelled, textureRevision = patch.model)
    }

    /** Conditioned source-level attack and sustain for raw listening evidence. */
    fun renderHeldRaw(patch: OhmPatch, cancelled: () -> Boolean = { false }): KeyNote {
        require(patch.model >= 3) { "OHM instruments require model 3 or later" }
        return OhmV3.renderHeldRaw(patch.macros + ("HOLD" to 1f), patch.midi, patch.velocity,
            patch.seed, patch.gesture, patch.options, cancelled, textureRevision = patch.model)
    }

    internal fun probe(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY,
        companionExcitation: Boolean = true, recordPorts: Boolean = true,
    ) = OhmV2.probe(macros, midi, velocity, seed, gesture, companionExcitation, recordPorts)

    internal fun relationship(progress: Double, meddling: Float, commune: Float, gesture: OhmGesture) =
        OhmV2.relationship(progress, meddling, commune, gesture)
}
