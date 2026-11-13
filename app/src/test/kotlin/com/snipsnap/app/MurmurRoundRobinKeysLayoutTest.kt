package com.snipsnap.app

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.synth.ChordStabs
import kotlin.test.*
import org.junit.Test

class MurmurRoundRobinKeysLayoutTest {
    @Test fun keyGridUsesFinalMidiPitchesAtEveryOctave() {
        for (octave in -2..2) {
            val pads = MurmurRoundRobinKeysLayout.pads(MurmurRoundRobinKeysLayout.Mode.KEYS, octave)
            assertEquals((48..63).map { listOf(it + octave * 12) }, pads.map { it.notes })
        }
    }
    @Test fun majorAndMinorKeepActualChordToolInversionsWideRootAndAddedNinth() {
        for ((mode, scale) in listOf(MurmurRoundRobinKeysLayout.Mode.MAJOR to Scale.MAJOR,
            MurmurRoundRobinKeysLayout.Mode.MINOR to Scale.MINOR)) {
            val actual = ChordStabs.stabs(KeySpec(0, scale))
            for (octave in -2..2) {
                val pads = MurmurRoundRobinKeysLayout.pads(mode, octave)
                assertEquals(actual.map { it.name }, pads.map { it.label })
                assertEquals(actual.map { s -> s.notes.map { it + octave * 12 } }, pads.map { it.notes })
            }
            assertEquals(4, actual[7].notes.size)
            assertEquals(4, actual[15].notes.size)
        }
    }
    @Test fun coverageRequiresAllFinalTonesAndNeverClampsAChordIntoRange() {
        val pad = MurmurRoundRobinKeysLayout.Pad("inversion", listOf(55, 60, 64))
        assertFalse(pad.covered(60..84)); assertTrue(pad.covered(55..64))
        assertEquals(listOf(55, 60, 64), pad.notes)
        assertFalse(MurmurRoundRobinKeysLayout.Pad("too many", (60..68).toList()).covered(0..127))
        assertFalse(MurmurRoundRobinKeysLayout.Pad("empty", emptyList()).covered(0..127))
    }
}
