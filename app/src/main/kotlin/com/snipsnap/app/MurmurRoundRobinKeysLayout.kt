package com.snipsnap.app

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.shell.KeysLayout
import com.snipsnap.synth.ChordStabs

/** Uses the existing chord tool's final inversion/wide-root/add9 pitches unchanged. */
internal object MurmurRoundRobinKeysLayout {
    enum class Mode(val label: String) { KEYS("KEYS"), MINOR("MINOR CHORDS"), MAJOR("MAJOR CHORDS") }
    data class Pad(val label: String, val notes: List<Int>) {
        fun covered(range: IntRange): Boolean = notes.isNotEmpty() && notes.all { it in range } && notes.distinct().size <= 8
    }
    fun pads(mode: Mode, octave: Int): List<Pad> {
        require(octave in -2..2)
        return if (mode == Mode.KEYS) (0..15).map {
            val note = 48 + it + octave * 12
            Pad(KeysLayout.label(note), listOf(note))
        } else {
            val scale = if (mode == Mode.MINOR) Scale.MINOR else Scale.MAJOR
            ChordStabs.stabs(KeySpec(0, scale)).map { stab ->
                Pad(stab.name, stab.notes.map { it + octave * 12 })
            }
        }
    }
}
