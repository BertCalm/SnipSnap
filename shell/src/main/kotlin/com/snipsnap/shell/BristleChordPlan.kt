package com.snipsnap.shell

import com.snipsnap.synth.Bristle
import com.snipsnap.synth.BristlePatch
import com.snipsnap.synth.ChordStabs
import kotlin.math.abs

/** Whole-chord preflight for the existing chord-tool voicings in Bristle's bass register. */
object BristleChordPlan {
    data class Plan(val source: BristlePatch, val notes: List<Int>, val transposeOctaves: Int) {
        fun patches(): List<BristlePatch> = notes.map { source.copy(midi = it, macros = source.macros + ("TUNE" to .5f)) }
    }

    fun fromNotes(source: BristlePatch, notes: List<Int>): Plan {
        require(notes.isNotEmpty() && notes.size <= 128)
        require(notes.all { it in Bristle.MIDI_MIN..Bristle.MIDI_MAX }) { "Bristle chord must fit C1–C3" }
        val unique = notes.distinct().sorted()
        require(unique.size <= InstrumentEngine.MAX_VOICES) { "Bristle chord exceeds eight voices" }
        return Plan(BristlePatch.fromJsonText(source.toJsonText()), unique, 0)
    }

    /** Choose one whole-octave shift, reported in the plan; never clamp or drop a chord tone. */
    fun fromStab(source: BristlePatch, stab: ChordStabs.Stab): Plan {
        require(stab.rootMidi in 0..127 && stab.intervals.size in 1..128 && stab.intervals.all { it in -127..127 })
        val notes = stab.notes
        val octave = (-10..10).filter { shift -> notes.all { it + 12*shift in Bristle.MIDI_MIN..Bristle.MIDI_MAX } }
            .minByOrNull { shift -> abs(notes.average() + 12*shift - Bristle.rootMidi(source)) }
            ?: throw IllegalArgumentException("Chord span cannot fit Bristle's C1–C3 range")
        return fromNotes(source, notes.map { it + 12*octave }).copy(transposeOctaves = octave)
    }
}
