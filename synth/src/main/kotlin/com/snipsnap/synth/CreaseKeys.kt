package com.snipsnap.synth

/** A real onset and continuously evolved settling prefix followed by one sustain period.
 * [previousCycle] is the actual period immediately before the exported loop, at the same
 * final gain. Boundary snapshots include pressure/deformation, detector, filter, DC and
 * canonical FIR history. [prelevel] is the complete conditioned note before that gain.
 */
data class CreaseKeyProbe(
    val note: KeyNote,
    val prelevel: FloatArray,
    val diagnostics: CreaseDiagnostics,
    val trace: CreaseTrace,
    val previousCycle: FloatArray,
    val loopStateStart: DoubleArray,
    val loopStateEnd: DoubleArray,
    val transitionEndSeconds: Double,
) {
    val prefixFrames: Int get() = note.loopStartFrame.toInt()
    val prefixSeconds: Double get() = prefixFrames.toDouble() / note.snip.sampleRate
}

/** Model-3 CREASE as a held keyboard instrument, rather than a finite note spliced to a loop.
 *
 * One pressure/memory/output circuit starts from rest, speaks its onset, then follows the
 * existing periodic pressure gesture until its complete state recurs. Every resulting
 * sample is retained. HOLD is controlled by the player's key; the patch's other macros,
 * exact authoring velocity and model-3 phase geometry are retained.
 *
 * The sample format stores an onset and a loop to the sample end. Player note-off fades
 * this whole sound using the program envelope; it cannot run a separately evolving
 * deformation-release tail. Long recovery settings can require a long settling prefix
 * and expensive offline rendering. Generation is bounded and cancellable, never repaired
 * by overlapping independently rendered states or duplicating a cycle as recurrence proof.
 */
object CreaseKeys {
    const val RENDER_VERSION = 1
    const val SUPPORTED_MODEL = 3
    const val MAX_SAMPLE_SECONDS = 64.0
    const val MAX_SETTLE_CYCLES = 28

    fun supports(patch: CreasePatch): Boolean = patch.model == SUPPORTED_MODEL

    fun render(
        patch: CreasePatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): KeyNote = probe(patch, midi, cancelled = cancelled).note

    fun probe(
        patch: CreasePatch,
        midi: Int = patch.midi,
        oversample: Int = 4,
        normalize: Boolean = true,
        cancelled: () -> Boolean = { false },
    ): CreaseKeyProbe {
        require(supports(patch)) { "CREASE held keys support model $SUPPORTED_MODEL only" }
        require(midi in Crease.MIDI_MIN..Crease.MIDI_MAX) {
            "CREASE held keys support MIDI ${Crease.MIDI_MIN}..${Crease.MIDI_MAX}"
        }
        require(oversample == 4 || oversample == 16) { "CREASE key diagnostic oversample must be 4 or 16" }
        return Crease.keyProbe(patch, midi, oversample, normalize, cancelled)
    }
}
