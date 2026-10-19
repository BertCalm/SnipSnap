package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadStripTest {

    @Test
    fun `an empty pad offers what can fill it`() {
        val strip = PadStrip.line(null, slot = 8)
        assertEquals("A08", strip.id)
        assertEquals(PadStrip.EMPTY, strip.facts)
        assertEquals(listOf(PadStrip.DOOR_CAPTURE, PadStrip.DOOR_FROM_SNIPS), strip.doors)
        assertEquals("A08 · EMPTY", strip.line)
    }

    @Test
    fun `a raw pad says so and opens its bench`() {
        val pad = KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", displayName = "snare", drumClass = DrumClass.SNARE)
        val strip = PadStrip.line(pad, 2)
        assertEquals("A02 · snare · RAW", strip.line)
        assertEquals(listOf(PadStrip.DOOR_OPEN), strip.doors)
    }

    @Test
    fun `a treated pad reads the sheet's own closed-box words plus choke and layers`() {
        val pad = KitPad(
            slot = 2, sampleFile = "A02_Snare_01.wav", displayName = "snare", drumClass = DrumClass.SNARE,
            muteGroup = 1, attack = 0.2f, oneShot = false,
            velocityLayers = listOf(
                KitLayer("A02_Snare_soft.wav", 0, 63),
                KitLayer("A02_Snare_01.wav", 64, 127),
            ),
        )
        val strip = PadStrip.line(pad, 2)
        val shape = PadSheetBoxes.shape(pad)
        assertTrue(shape != PadSheetBoxes.UNTOUCHED)
        assertEquals("$shape · CHOKE 1 · 2 LAYERS · HOLDS", strip.facts, "the sheet's SHAPE strip, verbatim, then the everyday facts")
        assertTrue(PadSheetBoxes.UNTOUCHED !in strip.facts, "untouched benches are left out")
    }

    @Test
    fun `fitted to one line, the facts give way and the name never does`() {
        val pad = KitPad(
            slot = 13, sampleFile = "A13_Break_01.wav", displayName = "break from the kitchen tape",
            drumClass = DrumClass.LOOP, muteGroup = 3, attack = 0.2f, decay = 0.5f, cutoff = 0.3f, resonance = 0.4f,
        )
        val strip = PadStrip.line(pad, 13)
        val fitted = PadStrip.oneLine(strip, 44)
        assertTrue(fitted.length <= 44, fitted)
        assertTrue(fitted.startsWith("A13 · break from the kitchen tape"), fitted)
        assertEquals(strip.line, PadStrip.oneLine(strip, 400), "room enough: the whole line")
    }
}
