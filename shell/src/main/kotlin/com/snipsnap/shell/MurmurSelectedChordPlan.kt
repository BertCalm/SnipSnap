package com.snipsnap.shell

import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurSelectedInstrumentSource

/** Preflight for structured/offline chords, not a claim of whole-chord native admission. */
object MurmurSelectedChordPlan {
    class Plan internal constructor(val sourcePatchJson: String, notes: List<Int>) {
        private val snapshot = notes.toList()
        val notes: List<Int> get() = snapshot.toList()
        /** Source snapshots for metadata; instrument rendering uses [cellRecipes] and its explicit output policy. */
        fun patches(): List<MurmurPatch> = snapshot.map { midi -> MurmurPatch.fromJsonText(sourcePatchJson).copy(midi = midi) }

        /** All tones were preflighted before any executable native cell recipe is constructed. */
        fun cellRecipes(touchIndex: Int, releaseSeconds: Float = MurmurSelectedInstrumentSource.DEFAULT_RELEASE_SECONDS): List<MurmurSelectedInstrumentSource.CellRecipe> {
            val source = MurmurPatch.fromJsonText(sourcePatchJson)
            return snapshot.map { MurmurSelectedInstrumentSource.cellRecipe(source, it, touchIndex, releaseSeconds) }
        }
    }

    fun fromNotes(source: MurmurPatch, finalNotes: List<Int>): Plan {
        val frozen = MurmurPatch.fromJsonText(source.toJsonText())
        val selected = requireNotNull(MurmurSelectedInstrumentSource.eligibility(frozen)) { "MURMUR chord source is not a qualified selected shape" }
        require(finalNotes.size in 1..128) { "MURMUR generated chord is empty or exceeds request budget" }
        // Validate before deduplication and before constructing even the first native note.
        require(finalNotes.all { it in selected.supportedMidi }) { "MURMUR chord contains a tone outside ${selected.supportedMidi}" }
        val notes = finalNotes.distinct().sorted()
        require(notes.size <= InstrumentEngine.MAX_VOICES) { "MURMUR generated chord exceeds eight note groups" }
        return Plan(frozen.toJsonText(), notes)
    }

    /** Expand the actual chord tool's inversions, wide roots and added tones before range checks. */
    fun fromStab(source: MurmurPatch, stab: ChordStabs.Stab, octaveLayers: List<Int> = listOf(0)): Plan {
        require(stab.rootMidi in 0..127 && stab.intervals.size in 1..128 && stab.intervals.all { it in -127..127 }) { "Invalid chord root or interval" }
        require(octaveLayers.size in 1..8 && octaveLayers.all { it in -10..10 }) { "Invalid MURMUR chord octave layers" }
        val base = stab.notes
        val notes = octaveLayers.flatMap { octave -> base.map { note ->
            val expanded = note.toLong() + octave.toLong() * 12L
            require(expanded in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "MURMUR chord note overflow" }
            expanded.toInt()
        } }
        return fromNotes(source, notes)
    }
}
