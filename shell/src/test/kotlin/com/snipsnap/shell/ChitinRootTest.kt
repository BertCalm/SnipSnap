package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.synth.*
import kotlin.math.*
import kotlin.test.*

class ChitinRootTest {
    @Test fun `root edits retain candidate character and sound at the requested pitch`() {
        for(voice in ChitinVoice.entries) {
            val patch=ChitinPresets.forVoice(voice).first()
            val control=assertNotNull(SynthRoot.of(patch))
            assertEquals(if(voice==ChitinVoice.SHELL) 24..48 else 48..72,control.range)
            val target=if(voice==ChitinVoice.SHELL) 43 else 67
            val moved=assertIs<ChitinPatch>(control.at(target))
            assertEquals(patch.macros-"TUNE",moved.macros-"TUNE")
            val sound=moved.render()
            assertEquals(target.toFloat(),Spread.detect(sound,moved))
            val pitch=assertNotNull(Pitch.detect(sound))
            val expected=440.0*2.0.pow((target-69)/12.0)
            assertTrue(abs(1200*ln(pitch.hz/expected)/ln(2.0))<10)
        }
    }
}
