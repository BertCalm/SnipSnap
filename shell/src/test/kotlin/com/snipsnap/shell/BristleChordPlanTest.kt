package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.synth.*
import com.snipsnap.kit.InstrumentStore
import kotlin.test.*

class BristleChordPlanTest {
    @Test fun `actual chord layouts preserve fitting voicings and explicitly refuse the four unfit B add ninths`() {
        val patch=BristlePresets.all().first().copy(take=1,velocity=.65f,macros=Bristle.defaults+("TUNE" to .75f))
        var count=0; var refused=0
        for (root in 0..11) {
            val chords = listOf("maj","min").flatMap { ChordStabs.stabs(KeySpec.parse(com.snipsnap.audio.Scales.NOTE_NAMES[root]+it)) } +
                ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(root,it) } +
                ChordStabs.borrowedStabs(KeySpec.parse(com.snipsnap.audio.Scales.NOTE_NAMES[root]+"maj"))
            for (chord in chords) {
                if (chord.notes in listOf(listOf(59,63,66,73),listOf(59,62,66,73))) {
                    assertFails { BristleChordPlan.fromStab(patch,chord) };refused++;continue
                }
                val plan=BristleChordPlan.fromStab(patch,chord)
                assertEquals(chord.notes.map { it+12*plan.transposeOctaves }.distinct().sorted(),plan.notes)
                assertTrue(plan.notes.all { it in 24..48 })
                assertEquals(plan.notes,plan.patches().map { Bristle.rootMidi(it) })
                assertTrue(plan.patches().all { it.take==1 && it.velocity==.65f })
                count++
            }
        }
        assertEquals(956,count);assertEquals(4,refused)
        assertFails { BristleChordPlan.fromNotes(patch,listOf(23,36,43)) }
        assertFails { BristleChordPlan.fromNotes(patch,(24..32).toList()) }
        assertFails { BristleChordPlan.fromStab(patch,ChordStabs.Stab("too wide",36,listOf(0,25))) }
    }

    @Test fun `all six held voices play chord tones together and release without stuck voices`() {
        for (voice in Bristle.musicalVoices) {
            val patch=BristlePresets.forVoice(voice).single()
            val plan=BristleChordPlan.fromStab(patch,ChordStabs.stabs(KeySpec.parse("Cmin")).last())
            val samples=plan.notes.associate { midi -> midi.toString() to BristleHeld.render(patch.copy(midi=midi)).note.snip.samples }
            val zones=plan.notes.map { midi -> InstrumentStore.Zone(midi,midi,midi,midi.toString(),samples.getValue(midi.toString()).size.toLong(),BristleHeld.LOOP_START_FRAME.toLong()) }
            val instrument=InstrumentStore.Instrument("Bristle chord",.2f,zones,explicitNoteOn=true)
            val player=InstrumentEngine(InstrumentEngine.Loaded(instrument,44100,samples),44100)
            plan.notes.forEach { assertTrue(player.noteOn(it,.65f)) }
            val held=FloatArray(44100*3*2);player.render(held,44100*3)
            assertEquals(plan.notes.toSet(),player.activeNotes.toSet())
            assertTrue(held.all { it.isFinite() && kotlin.math.abs(it)<=1f })
            plan.notes.forEach { player.noteOff(it) }
            val tail=FloatArray(44100);player.render(tail,22050)
            assertTrue(player.activeNotes.isEmpty());assertTrue(tail.takeLast(1000).all { it==0f })
        }
    }
}
