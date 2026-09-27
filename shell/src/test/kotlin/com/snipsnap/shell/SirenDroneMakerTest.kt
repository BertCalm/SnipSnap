package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenVoice
import kotlin.test.Test
import kotlin.test.assertEquals

class SirenDroneMakerTest {

    @Test
    fun `every voice shares SIREN's own register`() {
        val want = Siren.ROOT_MIDI..(Siren.ROOT_MIDI + Siren.TUNE_SEMITONES)
        for (voice in SirenVoice.entries) assertEquals(want, SirenDroneMaker.roots(voice))
    }

    @Test
    fun `the default root is the kit's key at the bottom of the register`() {
        assertEquals(Siren.ROOT_MIDI, SirenDroneMaker.defaultRoot(SirenVoice.WAIL, null))
        assertEquals(Siren.ROOT_MIDI + 2, SirenDroneMaker.defaultRoot(SirenVoice.WAIL, KeySpec(2, Scale.entries.first()))) // D4
    }

    @Test
    fun `a recipe keeps only what a drone hears`() {
        val s = SirenDroneMaker.spec(SirenVoice.WAIL, mapOf("RATE" to 0.2f, "DEPTH" to 0.9f, "GRIT" to 0.1f, "HOLD" to 0.5f, "TUNE" to 0.5f, "SWEEP" to 0.5f))
        assertEquals(setOf("RATE", "DEPTH", "GRIT"), s.macros.keys)
    }

    @Test
    fun `the readout is DroneMaker's own, unmodified by the engine`() {
        val fresh = SessionBuilder.empty(44_100)
        assertEquals(DroneMaker.label(Siren.ROOT_MIDI, fresh), SirenDroneMaker.label(Siren.ROOT_MIDI, fresh))
    }
}
