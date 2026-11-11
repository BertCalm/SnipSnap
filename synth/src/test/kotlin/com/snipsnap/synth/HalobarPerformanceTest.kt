package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

class HalobarPerformanceTest {
    @Test fun `every mallet has repeatable distinct native landings at fixed pitch and finite HOLD`() {
        assertEquals(6, HalobarVoice.entries.size)
        for (voice in HalobarVoice.entries) {
            val patch = HalobarPatch("Strike", voice, mapOf("SPACE" to 0f, "HOLD" to 0f))
            assertTrue(Restrike.canRestrike(patch))
            val reference = Velocity.atVelocity(patch, .7f)
            assertContentEquals(reference.samples, Restrike.take(patch, .7f, 13, 0)!!.samples)
            val takes = (1..3).map { Restrike.take(patch, .7f, 13, it)!! }
            assertEquals(1, takes.map { it.frameCount }.distinct().size)
            for (i in takes.indices) {
                assertContentEquals(takes[i].samples, Restrike.take(patch, .7f, 13, i + 1)!!.samples)
                assertFalse(takes[i].samples.contentEquals(reference.samples))
                assertTrue(takes[i].samples.all { it.isFinite() && abs(it) <= .99001f })
                for (j in 0 until i) assertFalse(takes[i].samples.contentEquals(takes[j].samples))
                var factor = 1
                val hz = Keys.midiHz(Halobar.rootMidi(voice))
                while (hz / factor > 1100) factor *= 2
                val pitch = assertNotNull(Pitch.detect(Snip(takes[i].samples, 1, Halobar.RATE / factor),
                    fromSec = .05f * factor, windowSec = .25f * factor))
                assertTrue(abs(1200 * log2(pitch.hz * factor / hz)) < 5, "$voice take $i drifted")
            }
            assertFalse(takes[0].samples.contentEquals(Restrike.take(patch, .7f, 14, 1)!!.samples))
            assertTrue(HalobarPresets.auditionFor(voice).size >= 1)
            assertFalse(Halobar.isLoop(1f))
        }
    }
    @Test fun `all chord-tool layouts retain pitch classes inside every mallet register`() {
        val chords = listOf("C", "Cm", "B", "Bbm").flatMap { ChordStabs.stabs(KeySpec.parse(it)) } +
            ChordStabs.modalStabs(2, ChordStabs.Mode.DORIAN) +
            ChordStabs.modalStabs(7, ChordStabs.Mode.MIXOLYDIAN) + ChordStabs.borrowedStabs(KeySpec.parse("C"))
        for (voice in HalobarVoice.entries) for (chord in chords) {
            val notes = HalobarChords.notes(voice, chord)
            assertEquals(chord.notes.map { it % 12 }.toSet(), notes.map { it % 12 }.toSet())
            assertTrue(notes.size in chord.notes.map { it % 12 }.distinct().size..chord.notes.size)
            assertTrue(notes.all { it in Halobar.minimumMidi(voice)..Halobar.maximumMidi(voice) })
        }
    }
    @Test fun `tubular bell body outlasts the steel bar and exposes its octave`() {
        val tube = Halobar.renderProbe(HalobarVoice.TUBULAR_BELLS, mapOf("SPACE" to 0f))
        val bar = Halobar.plan(HalobarVoice.GLOCKENSPIEL,
            mapOf("TUNE" to Halobar.tuneForMidi(HalobarVoice.GLOCKENSPIEL, 60), "SPACE" to 0f))
        assertTrue(tube.plan.modeT60.first() > bar.modeT60.first() * 2)
        fun amplitude(ratio: Double): Double {
            var re=0.0; var im=0.0
            for (i in 2205 until 11025) {
                val phase = 2 * PI * tube.plan.hz * ratio * i / Halobar.RATE
                re += tube.samples[i] * cos(phase); im += tube.samples[i] * sin(phase)
            }
            return hypot(re, im)
        }
        assertTrue(amplitude(2.01) > amplitude(1.0) * .05)
        for (note in 48..84) assertEquals(note, Halobar.midiFor(HalobarVoice.TUBULAR_BELLS,
            Halobar.tuneForMidi(HalobarVoice.TUBULAR_BELLS, note)))
    }
}
