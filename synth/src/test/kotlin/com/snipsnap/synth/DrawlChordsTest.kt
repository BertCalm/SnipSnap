package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DRAWL's chord-tool voicings on keys: every chord, every voice, inside the voice's two octaves. */
class DrawlChordsTest {
    private val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val chords = keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
        (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
        keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }

    @Test fun `every chord-tool voicing lands in every voice's keys with its tones and spacing kept`() {
        assertEquals(960, chords.size)
        for (voice in DrawlVoice.entries) {
            val range = DrawlChords.range(voice)
            assertEquals(Drawl.rootMidi(voice) - 12..Drawl.rootMidi(voice) + 12, range)
            for (chord in chords) {
                val notes = DrawlChords.notes(voice, chord)
                assertTrue(notes.all { it in range }, "${voice.name} ${chord.name}: $notes")
                assertEquals(chord.notes.map { it % 12 }.toSet(), notes.map { it % 12 }.toSet(), "${chord.name}: every pitch class")
                // A chord within an octave always fits by a whole-octave move; a wider one may fold.
                if (chord.notes.last() - chord.notes.first() <= 12) {
                    val shift = notes.first() - chord.notes.first()
                    assertEquals(0, shift % 12, "${chord.name}: a whole-octave move")
                    assertEquals(chord.notes.map { it + shift }, notes, "${chord.name}: spacing kept")
                    if (chord.notes.all { it in range }) assertEquals(chord.notes, notes, "${chord.name}: already in range")
                }
            }
        }
    }

    @Test fun `tune reaches every key and the audition is the sum of each tone's render`() {
        for (voice in DrawlVoice.entries) for (midi in DrawlChords.range(voice))
            assertEquals(midi, Drawl.midiFor(voice, DrawlChords.tuneFor(voice, midi)))
        assertFailsWith<IllegalArgumentException> { DrawlChords.tuneFor(DrawlVoice.GUT, Drawl.rootMidi(DrawlVoice.GUT) + 13) }
        val patch = DrawlPresets.named(DrawlVoice.CONE, "RESO TWANG")
        val chord = ChordStabs.stabs(KeySpec.parse("A")).first()
        val sum = DrawlChords.render(patch, chord)
        val parts = DrawlChords.notes(DrawlVoice.CONE, chord).map {
            patch.copy(macros = patch.macros + ("TUNE" to DrawlChords.tuneFor(DrawlVoice.CONE, it))).render().samples
        }
        val expected = FloatArray(parts.maxOf { it.size })
        val gain = 1f / parts.size
        for (p in parts) for (i in p.indices) expected[i] += p[i] * gain
        assertContentEquals(expected, sum.samples)
        assertFailsWith<IllegalArgumentException> { DrawlChords.render(DrawlPresets.named(DrawlVoice.CONE, "RESO STRUM"), chord) }
    }
}
