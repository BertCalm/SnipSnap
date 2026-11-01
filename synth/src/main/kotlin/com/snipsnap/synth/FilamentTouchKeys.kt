package com.snipsnap.synth

import java.util.concurrent.CancellationException

/**
 * Three native playing strengths for the existing model-three held network.
 * The saved velocity and INJECT are the hard-touch anchors. Softer captures lower
 * both, so touch changes the existing exciter's upper partials, launch distribution,
 * articulation and reactive exchange while keeping the bend profile and seed.
 * Each capture retains its own onset and independently settled sustain marker.
 *
 * [FilamentKeys.layer] removes only velocity^.72, exactly once. No loudness matching
 * or new normalization conceals branch depletion; the player supplies note gain.
 * The hard layer is the existing single-layer instrument, sample for sample.
 */
object FilamentTouchKeys {
    const val RENDER_VERSION = 1
    // Musical capture anchors and switch points, not a calibrated force model.
    val TOUCHES: List<Float> = listOf(.35f, .65f, 1f)
    val VELOCITY_WINDOWS: List<IntRange> = listOf(1..42, 43..84, 85..127)

    data class Layer(
        val touch: Float,
        val velocityWindow: IntRange,
        val sourcePatch: FilamentPatch,
        val note: KeyNote,
    ) {
        val excitationVelocity: Float get() = sourcePatch.velocity
    }

    /** Exact held-key recipe for a layer; source patches themselves retain their historical schema. */
    fun sourcePatch(patch: FilamentPatch, midi: Int, touch: Float): FilamentPatch {
        require(FilamentKeys.supports(patch)) { "FILAMENT touch keys require a supported model-3 recipe" }
        require(midi in FilamentKeys.MIN_MIDI..FilamentKeys.MAX_MIDI) {
            "FILAMENT touch keys require MIDI ${FilamentKeys.MIN_MIDI}..${FilamentKeys.MAX_MIDI}: $midi"
        }
        require(touch in TOUCHES) { "FILAMENT touch strength must be one of $TOUCHES" }
        return patch.copy(midi = midi, velocity = patch.velocity * touch,
            macros = patch.macros + mapOf("INJECT" to Filament.settled(patch.voice, patch.macros).getValue("INJECT") * touch, "HOLD" to 1f))
    }

    fun render(patch: FilamentPatch, midi: Int = patch.midi,
               cancelled: () -> Boolean = { false }): List<KeyNote> =
        capture(patch, midi, cancelled).map { it.note }

    fun capture(patch: FilamentPatch, midi: Int = patch.midi,
                cancelled: () -> Boolean = { false }): List<Layer> {
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("FILAMENT touch capture no longer wanted")
        }
        checkCancelled()
        require(FilamentKeys.supports(patch)) { "FILAMENT touch keys require a supported model-3 recipe" }
        return TOUCHES.mapIndexed { index, touch ->
            checkCancelled()
            val source = sourcePatch(patch, midi, touch)
            val note = FilamentKeys.layer(source, midi, cancelled)
            checkCancelled()
            Layer(touch, VELOCITY_WINDOWS[index], source, note)
        }
    }
}
