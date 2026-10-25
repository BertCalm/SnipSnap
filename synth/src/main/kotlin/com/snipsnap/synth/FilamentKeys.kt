package com.snipsnap.synth

import kotlin.math.pow

/**
 * The first playable FILAMENT instrument: model-three THREAD at C3–C5.
 *
 * The same periodic excitation starts from a quiet network and continues until the real
 * delay, halo and activity states settle. The returned [KeyNote] retains that onset and
 * marks one settled cycle for the keygroup host to repeat; the host owns note-off release.
 * Only HOLD is forced to its top step. All other controls, geometry and native playing
 * energy come from the complete saved patch. Ordinary patch rendering is unchanged.
 *
 * [note] retains native velocity timbre and velocity^.72 level. [layer] removes only that
 * final level law so a velocity-sensitive keygroup host can apply its own gain once.
 * It retains velocity's effects on launch, articulation and the reactive junction.
 */
object FilamentKeys {
    const val MIN_MIDI = 48
    const val MAX_MIDI = 72

    fun note(
        patch: FilamentPatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): KeyNote {
        validate(patch, midi)
        return Filament.keyNote(patch, midi, cancelled)
    }

    /** The instrument host supplies note-velocity gain; the renderer still supplies its timbre. */
    fun layer(
        patch: FilamentPatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): KeyNote {
        val note = note(patch, midi, cancelled)
        if (patch.velocity > 0f) {
            val nativeGain = patch.velocity.toDouble().pow(.72).toFloat()
            for (i in note.snip.samples.indices) note.snip.samples[i] /= nativeGain
        }
        return note
    }

    private fun validate(patch: FilamentPatch, midi: Int) {
        require(patch.voice == FilamentVoice.THREAD) { "FILAMENT playable keys currently support THREAD" }
        require(patch.model == Filament.MODEL_VERSION) {
            "FILAMENT playable keys require model ${Filament.MODEL_VERSION}; saved model ${patch.model} remains available through its original renderer"
        }
        require(midi in MIN_MIDI..MAX_MIDI) { "FILAMENT THREAD keys require absolute MIDI $MIN_MIDI..$MAX_MIDI: $midi" }
    }
}
