package com.snipsnap.synth

/**
 * CURL-only release experiment. Both components come from one model-3 network run,
 * with identical onset, sample clock, FIR, output gain and accepted loop marker.
 * Body is COLLECT's weighted through collector; halo is the weighted spill collector
 * (escaped branch plus resonators). Neither stem is normalized or synthesized again.
 *
 * The host fades these captured periodic components independently after note-off.
 * This is an envelope over a baked sustain, not a simulation of unforced modal decay.
 * Ordinary FILAMENT keys, patch serialization and models 1..3 remain unchanged.
 */
object FilamentHaloKeys {
    const val VERSION = 1

    data class Components(val body: KeyNote, val halo: KeyNote, val mixed: KeyNote)

    fun capture(patch: FilamentPatch, midi: Int = patch.midi,
                cancelled: () -> Boolean = { false }): Components {
        require(patch.voice == FilamentVoice.CURL && FilamentKeys.supports(patch)) {
            "independent halo release prototype requires model-3 CURL"
        }
        require(midi in FilamentKeys.MIN_MIDI..FilamentKeys.MAX_MIDI)
        return Filament.keyComponents(patch, midi, cancelled)
    }
}
