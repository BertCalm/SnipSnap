package com.snipsnap.synth

/** Attack and periodic sustain for the accepted model-4 FAN/STILL palette. */
object OrigamiKeys {
    const val MODEL_VERSION = 1
    const val MIN_MIDI = 48
    const val MAX_MIDI = 72

    fun supports(patch: Patch): Boolean = patch is OrigamiPatch && patch.model == 4 &&
        (patch.voice == OrigamiVoice.FAN || patch.voice == OrigamiVoice.STILL)

    /** The zone note overrides both the recipe's explicit note and its TUNE macro. */
    fun note(patch: OrigamiPatch, midi: Int, cancelled: () -> Boolean = { false }): KeyNote =
        probe(patch, midi, cancelled).note

    internal fun probe(patch: OrigamiPatch, midi: Int, cancelled: () -> Boolean = { false }): OrigamiHeld.Probe {
        require(supports(patch)) { "ORIGAMI key sustain supports model-4 FAN and STILL only" }
        require(midi in MIN_MIDI..MAX_MIDI) { "ORIGAMI key MIDI must be $MIN_MIDI..$MAX_MIDI: $midi" }
        return OrigamiHeld.probe(patch, midi, cancelled)
    }
}
