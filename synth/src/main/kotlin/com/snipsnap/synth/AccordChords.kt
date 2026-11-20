package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * Chord-tool voicings for ACCORD: every chord tone kept, the whole chord moved by octaves into
 * the engine's C2..C5 so its spacing is the chord tool's own ([HalobarChords]' rule).
 */
object AccordChords {
    /** [chord]'s notes inside [Accord.MIN_MIDI]..[Accord.MAX_MIDI]; a chord already inside is not moved. */
    fun notes(chord: ChordStabs.Stab): List<Int> {
        require(chord.notes.isNotEmpty())
        val low = Accord.MIN_MIDI
        val high = Accord.MAX_MIDI
        val shifts = (-10..10).map { it * 12 }.filter { shift -> chord.notes.all { it + shift in low..high } }
        if (shifts.isNotEmpty()) {
            val shift = shifts.minBy { abs(it) }
            return chord.notes.map { it + shift }
        }
        // Wider than three octaves: fold each tone to its nearest octave in range, keeping every pitch class.
        return chord.notes.map { note -> (low..high).filter { (it - note) % 12 == 0 }.minBy { abs(it - note) } }.distinct().sorted()
    }

    /**
     * Offline audition: the chord's notes struck independently and summed at 1 / n, each the
     * engine's own render of [patch] at that note. Every tone hears the same machine, so the
     * contacts land together on all of them. Keys made by MAKE INSTRUMENT play tones between
     * their sampled roots a semitone fast or slow instead; that is the instrument's, not this.
     */
    fun render(patch: AccordPatch, chord: ChordStabs.Stab, velocity: Float = patch.velocity): Snip {
        val sources = notes(chord).map { midi -> patch.copy(midi = midi, velocity = velocity).render() }
        val out = FloatArray(sources.maxOf { it.frameCount })
        val gain = 1f / sources.size
        for (source in sources) for (i in source.samples.indices) out[i] += source.samples[i] * gain
        return Snip(out, 1, Dsp.RATE)
    }
}
