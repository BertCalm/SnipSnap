package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.ConsortPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsortPadTest {
    private val pad = KitPad(slot = 1, sampleFile = "a.wav", drumClass = DrumClass.TONAL, displayName = "x", colorHex = "#000000")

    @Test
    fun `a finite CONSORT pad plays once, and HOLD's top step gates a sustaining loop`() {
        val preset = ConsortPresets.all().first()
        val finite = ConsortPad.configure(pad, preset)
        assertTrue(finite.oneShot)
        assertFalse(finite.loop)
        val held = ConsortPad.configure(pad, preset.withMacros(preset.macros + ("HOLD" to 1f)))
        assertFalse(held.oneShot)
        assertTrue(held.loop)
        assertEquals(DrumClass.LOOP, held.drumClass)
        assertEquals(0f, held.attack)
        assertEquals(1f, held.decay)
    }
}
