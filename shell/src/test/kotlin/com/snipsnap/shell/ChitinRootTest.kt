package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.synth.*
import kotlin.math.*
import kotlin.test.*

class ChitinRootTest {
    @Test fun `root edits retain candidate character and sound at the requested pitch`() {
        for(voice in Chitin.KEPT_VOICES) {
            val patch=ChitinPresets.forVoice(voice).first()
            val control=assertNotNull(SynthRoot.of(patch))
            val low=Chitin.midiFor(voice,0f)
            assertEquals(low..low+24,control.range)
            val target=low+19
            val moved=assertIs<ChitinPatch>(control.at(target))
            assertEquals(patch.macros-"TUNE",moved.macros-"TUNE")
            val sound=moved.render()
            assertEquals(target.toFloat(),Spread.detect(sound,moved))
            val pitch=assertNotNull(Pitch.detect(sound))
            val expected=440.0*2.0.pow((target-69)/12.0)
            val cents=1200*ln(pitch.hz/expected)/ln(2.0)
            println("CHITIN $voice root edit to MIDI $target: detector reads %+.1f cents".format(cents))
            // The general detector is held to 10 cents on SHELL and WING only. The voices kept in
            // chitin-voices-1 are held to 15 cents at the root by ChitinCandidateVoicesTest's own measure.
            if(voice in Chitin.MIXED_VOICES) assertTrue(abs(cents)<10,"$voice $cents")
        }
    }
}
