package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccordChordsTest {
    private val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun everyStab(): List<ChordStabs.Stab> =
        keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
            (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
            keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }

    @Test fun `every diatonic modal and borrowed chord fits C2 to C5 whole, moved only by octaves`() {
        val stabs = everyStab()
        assertEquals(384 + 384 + 192, stabs.size)
        var moved = 0
        for (stab in stabs) {
            val notes = AccordChords.notes(stab)
            assertTrue(notes.all { it in Accord.MIN_MIDI..Accord.MAX_MIDI }, stab.name)
            assertEquals(stab.notes.size, notes.size, stab.name)
            val shift = notes.first() - stab.notes.first()
            assertEquals(0, Math.floorMod(shift, 12), "${stab.name} moves by whole octaves")
            assertEquals(stab.notes.map { it + shift }, notes, "${stab.name} keeps its spacing")
            if (stab.notes.all { it in Accord.MIN_MIDI..Accord.MAX_MIDI }) assertEquals(0, shift, "${stab.name} already fits")
            else moved++
        }
        // The chord tool voices around G3 to F#4, so most chords already sit inside C2 to C5.
        println("AccordChords: $moved of ${stabs.size} chord-tool voicings moved by octaves")
        assertTrue(moved < stabs.size, "$moved of ${stabs.size} chords moved")
    }

    @Test fun `a chord wider than the range folds each tone in by octaves and keeps every pitch class`() {
        val wide = ChordStabs.Stab("WIDE", 24, listOf(0, 19, 40, 63))
        val notes = AccordChords.notes(wide)
        assertTrue(notes.all { it in Accord.MIN_MIDI..Accord.MAX_MIDI })
        assertEquals(wide.notes.map { it % 12 }.toSet(), notes.map { it % 12 }.toSet())
    }

    @Test fun `a chord render is its notes struck independently and summed at one over n`() {
        val patch = AccordPresets.forVoice(AccordVoice.BELL).first().copy(sourceSeconds = .75)
        val chord = ChordStabs.stabs(KeySpec.parse("Cm")).first()
        val notes = AccordChords.notes(chord)
        assertEquals(3, notes.size)
        val sources = notes.map { patch.copy(midi = it, velocity = 1f).render().samples }
        val expected = FloatArray(sources.maxOf { it.size })
        for (source in sources) for (i in source.indices) expected[i] += source[i] * (1f / 3)
        val chordSnip = AccordChords.render(patch, chord, 1f)
        assertContentEquals(expected, chordSnip.samples)
        assertTrue(chordSnip.samples.all { it.isFinite() } && chordSnip.peak() in .05f..(.85f))
        assertContentEquals(AccordChords.render(patch, chord).samples, AccordChords.render(patch, chord, patch.velocity).samples)
    }
}
