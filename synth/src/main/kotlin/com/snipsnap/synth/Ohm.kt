package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.DrumClass

/** STEADY keeps the relationship fixed; GATHER authors a finite solo, argument, then gathering. */
enum class OhmGesture { STEADY, GATHER }

/** Versioned OHM renderer: saved recipes select their original synthesis and source path. */
object Ohm {
    const val MODEL_VERSION = 5
    /** Keep existing callers on their original finite-note renderer. New recipes opt into model 5. */
    const val DEFAULT_MODEL = 2
    /** Source and sustain upgrades have their own endpoint, independent of the newest finite model. */
    const val SOURCE_MODEL = 4
    const val ROOT_MIDI = 60
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72

    val MACROS: List<MacroSpec> get() = OhmV2.MACROS
    fun defaults(): Map<String, Float> = OhmV2.defaults()
    fun macrosFor(model: Int): List<MacroSpec> {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        return when (model) {
            3, 4 -> OhmV3.MACROS
            5 -> OhmV5.MACROS
            else -> MACROS
        }
    }
    fun defaults(model: Int): Map<String, Float> = macrosFor(model).associate { it.name to it.default }
    fun supportsModel(model: Int) = model in 1..MODEL_VERSION
    fun supportsSources(model: Int) = model == 3 || model == 4
    fun supportsHeld(model: Int) = model == 3 || model == 4

    /** The complete accepted middle encounter; no legacy recipe is upgraded implicitly. */
    fun replyRingPatch(name: String = "Natural Replies"): OhmPatch = OhmPatch(
        name, OhmV5.defaults(), midi = 60, velocity = .8f, seed = 731,
        gesture = OhmGesture.STEADY, model = 5, reply = .5f, ring = .5f,
    )

    internal fun validatePerformance(
        macros: Map<String, Float>, gesture: OhmGesture, model: Int, options: OhmOptions,
        reply: Float, ring: Float,
    ) {
        require(supportsModel(model)) { "unsupported OHM model $model" }
        require(reply.isFinite() && reply in 0f..1f) { "OHM REPLY must be finite and in 0..1" }
        require(ring.isFinite() && ring in 0f..1f) { "OHM RING must be finite and in 0..1" }
        require(model == 5 || (reply == .5f && ring == .5f)) { "OHM REPLY and RING require model 5" }
        require(supportsSources(model) || options == OhmOptions()) { "OHM source options require model 3 or 4" }
        if (model == 5) {
            require(gesture == OhmGesture.STEADY) { "OHM model 5 supports STEADY only" }
            require("HOLD" !in macros) { "OHM model 5 is finite and does not support HOLD" }
        }
    }

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = DEFAULT_MODEL,
    ): Snip = renderRaw(macros, midi, velocity, seed, gesture, model, OhmOptions())

    fun renderRaw(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions,
    ): Snip = renderRaw(macros, midi, velocity, seed, gesture, model, options, .5f, .5f)

    fun renderRaw(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions, reply: Float, ring: Float,
    ): Snip {
        validatePerformance(macros, gesture, model, options, reply, ring)
        return when (model) {
            1 -> OhmV1.renderRaw(macros, midi, velocity, seed, gesture)
            2 -> OhmV2.renderRaw(macros, midi, velocity, seed, gesture)
            3 -> OhmV3.renderRaw(macros, midi, velocity, seed, gesture, options)
            4 -> OhmV3.renderRaw(macros, midi, velocity, seed, gesture, options, textureRevision = 4)
            5 -> OhmV5.renderRaw(macros, midi, velocity, seed, reply, ring)
            else -> error("unsupported OHM model $model")
        }
    }

    fun render(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, model: Int = DEFAULT_MODEL,
    ): Snip = render(macros, midi, velocity, seed, gesture, model, OhmOptions())

    fun render(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions,
    ): Snip = render(macros, midi, velocity, seed, gesture, model, options, .5f, .5f)

    fun render(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        gesture: OhmGesture, model: Int, options: OhmOptions, reply: Float, ring: Float,
    ): Snip {
        validatePerformance(macros, gesture, model, options, reply, ring)
        return when (model) {
            1 -> OhmV1.render(macros, midi, velocity, seed, gesture)
            2 -> OhmV2.render(macros, midi, velocity, seed, gesture)
            3 -> OhmV3.render(macros, midi, velocity, seed, gesture, options)
            4 -> OhmV3.render(macros, midi, velocity, seed, gesture, options, textureRevision = 4)
            5 -> OhmV5.render(macros, midi, velocity, seed, reply, ring)
            else -> error("unsupported OHM model $model")
        }
    }

    fun isHeld(patch: OhmPatch): Boolean = supportsHeld(patch.model) && (patch.macros["HOLD"] ?: 0f) >= .99f

    fun drumClassFor(patch: OhmPatch): DrumClass = if (isHeld(patch)) DrumClass.LOOP else DrumClass.TONAL

    /** Native attack plus sustain; the existing instrument player owns note-off and release. */
    fun renderHeld(patch: OhmPatch, cancelled: () -> Boolean = { false }): KeyNote {
        require(supportsHeld(patch.model)) { "OHM sustain requires model 3 or 4" }
        return OhmV3.renderHeld(patch.macros + ("HOLD" to 1f), patch.midi, patch.velocity,
            patch.seed, patch.gesture, patch.options, cancelled, textureRevision = patch.model)
    }

    /** Conditioned source-level attack and sustain for raw listening evidence. */
    fun renderHeldRaw(patch: OhmPatch, cancelled: () -> Boolean = { false }): KeyNote {
        require(supportsHeld(patch.model)) { "OHM sustain requires model 3 or 4" }
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
