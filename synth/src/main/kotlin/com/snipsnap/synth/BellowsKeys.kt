package com.snipsnap.synth

import kotlin.math.pow

/**
 * Playable C3-C5 Bellows keys. MIDI is the exact absolute note: the macro TUNE is ignored,
 * HOLD is forced to its repeating region, and the sample carries its real source attack.
 * The nonzero marker begins the independently synthesized settled period; hosts control
 * release after note-off. The supplied pressure gesture repeats continuously across that
 * marker, rather than switching from a finite gesture to a different steady-state player.
 *
 * [note] retains native velocity timbre AND velocity^.60 level for standalone playback.
 * [layer] retains the same velocity timbre at the forte reference: MPC/phone keygroup hosts
 * then apply their own note-velocity level exactly once. Do not scale a [note] by velocity
 * again, or add a second compensation to a [layer]. All output is dry mono at 44.1 kHz.
 *
 * A WHEEL melody always has DRONE zero. Its tonic companion is rendered independently by
 * [tonicBed]; export that bed as a separate one-key zone (low=high=root=tonicMidi) or held
 * pad/track. Never map the bed across the melody range and call that a fixed tonic.
 */
object BellowsKeys {
    fun note(voice: BellowsVoice, macros: Map<String, Float> = emptyMap(), midi: Int,
             velocity: Float = 1f, gesture: BellowsGesture = Bellows.gestureFor(voice),
             model: Int = Bellows.MODEL_VERSION, cancelled: () -> Boolean = { false }): KeyNote {
        validate(voice, midi, velocity, model)
        val m = if (voice == BellowsVoice.WHEEL) macros + ("DRONE" to 0f) else macros
        return Bellows.keyNote(voice, m, midi, velocity, gesture, cancelled)
    }

    /** Native velocity changes source timbre; the instrument host supplies dynamic gain. */
    fun layer(voice: BellowsVoice, macros: Map<String, Float> = emptyMap(), midi: Int,
              velocity: Float = 1f, gesture: BellowsGesture = Bellows.gestureFor(voice),
              model: Int = Bellows.MODEL_VERSION, cancelled: () -> Boolean = { false }): KeyNote =
        hostLevel(note(voice, macros, midi, velocity, gesture, model, cancelled), velocity)

    /** A fixed-tonic WHEEL carrier plus octave/fifth drones, independent of all melody notes. */
    fun tonicBed(tonicMidi: Int, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                 gesture: BellowsGesture = BellowsGesture.STEADY, model: Int = Bellows.MODEL_VERSION,
                 cancelled: () -> Boolean = { false }): KeyNote {
        validate(BellowsVoice.WHEEL, tonicMidi, velocity, model)
        return Bellows.keyNote(BellowsVoice.WHEEL, macros + ("DRONE" to 1f), tonicMidi, velocity, gesture, cancelled)
    }

    /** The tonic companion at reference level for a separate host-velocity-controlled zone. */
    fun tonicBedLayer(tonicMidi: Int, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                      gesture: BellowsGesture = BellowsGesture.STEADY, model: Int = Bellows.MODEL_VERSION,
                      cancelled: () -> Boolean = { false }): KeyNote =
        hostLevel(tonicBed(tonicMidi, macros, velocity, gesture, model, cancelled), velocity)

    private fun hostLevel(note: KeyNote, velocity: Float): KeyNote {
        if (velocity > 0f) {
            val nativeGain = velocity.pow(.60f)
            for (i in note.snip.samples.indices) note.snip.samples[i] /= nativeGain
        }
        return note
    }

    private fun validate(voice: BellowsVoice, midi: Int, velocity: Float, model: Int) {
        require(model == Bellows.MODEL_VERSION) {
            "BELLOWS playable keys require model ${Bellows.MODEL_VERSION}; saved model $model remains available through its original renderer"
        }
        require(midi in Bellows.midiRange(voice)) { "BELLOWS $voice keys require absolute MIDI 48..72: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "BELLOWS key velocity out of 0..1: $velocity" }
    }
}
