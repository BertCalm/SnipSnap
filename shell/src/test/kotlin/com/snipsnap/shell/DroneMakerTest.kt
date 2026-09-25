package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.synth.ResinVoice
import kotlin.test.Test
import kotlin.test.assertEquals

class DroneMakerTest {

    @Test
    fun `roots are each voice's own register`() {
        assertEquals(33..57, DroneMaker.roots(ResinVoice.BASS))
        assertEquals(45..69, DroneMaker.roots(ResinVoice.BRASS))
        assertEquals(57..81, DroneMaker.roots(ResinVoice.LEAD))
    }

    @Test
    fun `the default root is the kit's key at the bottom of the register`() {
        assertEquals(33, DroneMaker.defaultRoot(ResinVoice.BASS, null))
        assertEquals(38, DroneMaker.defaultRoot(ResinVoice.BASS, KeySpec(2, Scale.entries.first()))) // D2
        assertEquals(45, DroneMaker.defaultRoot(ResinVoice.BRASS, KeySpec(9, Scale.entries.first()))) // A2
    }

    @Test
    fun `a recipe keeps only what a drone hears`() {
        val s = DroneMaker.spec(ResinVoice.BASS, mapOf("STACK" to 0.2f, "CONTOUR" to 0.9f, "DECAY" to 0.1f, "TUNE" to 0.5f), 0.5f, 2)
        assertEquals(setOf("STACK"), s.macros.keys)
    }

    @Test
    fun `the readout names the note, the bars and the nudge`() {
        val fresh = SessionBuilder.empty(44_100)
        assertEquals("A1 · 4 BARS · -1.97¢", DroneMaker.label(33, fresh))
        assertEquals("±1.2 OCT", DroneMaker.motionLabel(0.6f))
        assertEquals("1 BREATH", DroneMaker.breathsLabel(1))
    }
}
