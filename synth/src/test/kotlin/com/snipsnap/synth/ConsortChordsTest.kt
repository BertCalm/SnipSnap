package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsortChordsTest {
    private val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    internal fun everyStab(): List<ChordStabs.Stab> =
        keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
            (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
            keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }

    @Test
    fun `every chord-tool chord keeps its spacing and lands on each voice's keys, moved only by octaves`() {
        val stabs = everyStab()
        assertEquals(384 + 384 + 192, stabs.size)
        for (voice in ConsortVoice.entries) {
            val range = ConsortChords.range(voice)
            assertEquals(ConsortKeys.padMidis(voice).first()..ConsortKeys.padMidis(voice).last(), range)
            val playable = range.first - ConsortChords.EDGE_SEMITONES..range.last + ConsortChords.EDGE_SEMITONES
            var whole = 0
            var worst = 0
            for (stab in stabs) {
                val notes = ConsortChords.notes(voice, stab)
                assertEquals(stab.notes.size, notes.size, "$voice ${stab.name}")
                val shift = notes.first() - stab.notes.first()
                assertEquals(0, Math.floorMod(shift, 12), "$voice ${stab.name} moves by whole octaves")
                assertEquals(stab.notes.map { it + shift }, notes, "$voice ${stab.name} keeps its spacing")
                assertTrue(notes.all { it in playable }, "$voice ${stab.name}: $notes")
                val past = notes.maxOf { if (it < range.first) range.first - it else if (it > range.last) it - range.last else 0 }
                if (past == 0) whole++
                worst = maxOf(worst, past)
                if (stab.notes.all { it in range }) assertEquals(0, shift, "$voice ${stab.name} already fits")
            }
            // The chord tool spans at most 14 semitones, so a chord strays at most a couple of semitones past an edge.
            assertTrue(worst <= 2, "$voice: a chord reaches $worst semitones past its range")
            println("ConsortChords $voice: $whole of ${stabs.size} chords inside the sampled range, at most $worst semitones past it")
        }
    }

    @Test
    fun `a chord render is the patch on each note summed at one over n`() {
        val plain = ConsortPresets.forVoice(ConsortVoice.CLARINET).first()
        val patch = plain.withMacros(plain.macros + ("HOLD" to 0f))
        val chord = ChordStabs.stabs(KeySpec.parse("Dm")).first()
        val notes = ConsortChords.notes(ConsortVoice.CLARINET, chord)
        val root = Consort.rootMidi(ConsortVoice.CLARINET)
        val sources = notes.map { patch.withMacros(patch.macros + ("TUNE" to (it - root) / 24f)).render().samples }
        val expected = FloatArray(sources.maxOf { it.size })
        for (s in sources) for (i in s.indices) expected[i] += s[i] / notes.size
        val rendered = ConsortChords.render(patch, chord).samples
        assertEquals(expected.size, rendered.size)
        for (i in expected.indices) assertEquals(expected[i], rendered[i], 1e-6f)
        assertTrue(rendered.all { it.isFinite() })
    }
}
