package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/** Chord-tool voicings keep every pitch class, shifted together into the mallet's register. */
object HalobarChords {
    fun notes(voice: HalobarVoice, chord: ChordStabs.Stab): List<Int> {
        require(chord.notes.isNotEmpty())
        val low = Halobar.minimumMidi(voice)
        val high = Halobar.maximumMidi(voice)
        val shifts = (-10..10).map { it * 12 }.filter { shift -> chord.notes.all { it + shift in low..high } }
        if (shifts.isNotEmpty()) {
            val shift = shifts.minBy { abs(chord.notes.average() + it - Halobar.rootMidi(voice)) }
            return chord.notes.map { it + shift }
        }
        // A wide B add9 cannot fit C6..C8 by whole-chord octave shifts.
        // Revoice edge tones by octaves, preserving every chord tone rather than clamping pitch classes.
        return chord.notes.map { note -> (low..high).filter { (it - note) % 12 == 0 }
            .minBy { abs(it - Halobar.rootMidi(voice)) } }.distinct()
    }
    /** Offline audition of the same independently struck notes a polyphonic keygroup plays. */
    fun render(patch: HalobarPatch, chord: ChordStabs.Stab, velocity: Float = 1f): Snip {
        val sources = notes(patch.voice, chord).map { midi -> Halobar.render(patch.voice,
            patch.macros + ("TUNE" to Halobar.tuneForMidi(patch.voice, midi)), velocity, patch.model) }
        val out = FloatArray(sources.maxOf { it.frameCount })
        val gain = 1f / sources.size
        for (source in sources) for (i in source.samples.indices) out[i] += source.samples[i] * gain
        Dsp.fadeTail(out)
        return Snip(out, 1, Halobar.RATE)
    }
}
