package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Versioned reconstruction of encounter material, independent of the frozen finite source models. */
object TidalKeys {
    const val MODEL_VERSION = 1
    const val MIN_MIDI = Tidal.MIN_MIDI
    const val MAX_MIDI = Tidal.MAX_MIDI

    fun note(patch: TidalPatch, midi: Int = patch.midi, cancelled: () -> Boolean = { false }): KeyNote =
        probe(patch, midi, cancelled).note

    /** Pads audition only the aftermath; keygroups retain the encounter before their loop marker. */
    fun loop(patch: TidalPatch, cancelled: () -> Boolean = { false }): Snip =
        TidalHeld.probe(patch, cancelled, keepAttack = false).let { Snip(padLoop(it.loop), 1, Dsp.RATE) }

    /** Rotate only: a near-zero trigger avoids a hard onset without fading any repeated sample. */
    internal fun padLoop(samples: FloatArray): FloatArray {
        require(samples.isNotEmpty())
        var cut = 0
        for (i in samples.indices) if (kotlin.math.abs(samples[i]) < kotlin.math.abs(samples[cut])) cut = i
        return FloatArray(samples.size) { samples[(cut + it) % samples.size] }
    }

    internal fun probe(patch: TidalPatch, midi: Int = patch.midi,
        cancelled: () -> Boolean = { false }): TidalHeld.Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "TIDAL held MIDI must be $MIN_MIDI..$MAX_MIDI" }
        return TidalHeld.probe(patch.copy(midi = midi), cancelled, keepAttack = true)
    }
}
