package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadSheetBoxesTest {

    private val bare = KitPad(slot = 1, sampleFile = "A01_Kick.wav", drumClass = DrumClass.KICK)

    @Test
    fun `five boxes in order, one open at a time, a tap on the open one closes it`() {
        assertEquals(listOf("TREATMENT", "SHAPE", "MUTATE", "OUTSIDE", "MAKE"), PadSheetBoxes.ORDER.map { it.legend })
        assertEquals(PadSheetBoxes.Box.MUTATE, PadSheetBoxes.boxFor("MUTATE"))
        assertNull(PadSheetBoxes.boxFor("BENCH"))
        assertEquals(PadSheetBoxes.Box.SHAPE, PadSheetBoxes.toggle(null, PadSheetBoxes.Box.SHAPE))
        assertEquals(PadSheetBoxes.Box.MUTATE, PadSheetBoxes.toggle(PadSheetBoxes.Box.SHAPE, PadSheetBoxes.Box.MUTATE), "only one open")
        assertNull(PadSheetBoxes.toggle(PadSheetBoxes.Box.MUTATE, PadSheetBoxes.Box.MUTATE), "tapping the open box closes it")
    }

    @Test
    fun `an untouched pad reads UNTOUCHED on every bench but MAKE, which names its doors`() {
        val s = PadSheetBoxes.summaries(bare, outsideStage = null)
        assertEquals(PadSheetBoxes.UNTOUCHED, s[PadSheetBoxes.Box.TREATMENT])
        assertEquals(PadSheetBoxes.UNTOUCHED, s[PadSheetBoxes.Box.SHAPE])
        assertEquals(PadSheetBoxes.UNTOUCHED, s[PadSheetBoxes.Box.MUTATE])
        assertEquals(PadSheetBoxes.UNTOUCHED, s[PadSheetBoxes.Box.OUTSIDE])
        assertEquals("PAD FROM ANYTHING · DE-SAMPLE · INSTRUMENT", s[PadSheetBoxes.Box.MAKE])
        for ((box, line) in s) assertTrue(line.length <= PadSheetBoxes.SUMMARY_CHARS, "$box: '$line' (${line.length})")
    }

    @Test
    fun `the strips read the recipes and the shape, shouting, inside the label budget`() {
        assertEquals("CRUSH · 35%", PadSheetBoxes.treatment(PadSheet.Applied(PadSheet.Treatment.Era("crush"), 0.35f, "CRUSH")))
        assertEquals("SMEARED · 60%", PadSheetBoxes.treatment(PadSheet.Applied(PadSheet.Treatment.Character("smeared"), 0.6f, null)))

        val shaped = bare.copy(attack = 0.5f, cutoff = 0.5f)
        val shape = PadSheetBoxes.shape(shaped)
        assertEquals("ATK 200 MS · CUT 632 HZ", shape, "only the fields the pad sets")
        val fully = PadSheetBoxes.shape(bare.copy(attack = 1f, decay = 0.5f, cutoff = 1f, resonance = 1f))
        assertTrue(fully.length <= PadSheetBoxes.SUMMARY_CHARS, "$fully (${fully.length})")
        assertEquals("632 HZ", PadSheetBoxes.cutoffLabel(0.5f))
        assertEquals("20.0K", PadSheetBoxes.cutoffLabel(1f))

        assertEquals("ROOM × FUNK ROOM", PadSheetBoxes.mutate(MutateSheet.Applied("ROOM", listOf("room:FUNK ROOM"))))
        assertEquals("DRIFT × SOUL A03", PadSheetBoxes.mutate(MutateSheet.Applied("MORPH", listOf("Soul:A03"), drifted = true)))
        assertEquals("STACK × FUNK A01 + FUNK A05", PadSheetBoxes.mutate(MutateSheet.Applied("STACK", listOf("FUNK:A01", "FUNK:A05"))))

        val applied = OutsideSheet.Applied("ROOM", 23.4f, 0.87f, false)
        assertEquals(OutsideSheet.statusLine(applied), PadSheetBoxes.outside(applied, null))
        assertEquals("ROOM · 23 MS LATE · 87% SURE", PadSheetBoxes.outside(applied, null))
        assertEquals(Copy.OUTSIDE_LISTENING, PadSheetBoxes.outside(applied, Copy.OUTSIDE_LISTENING), "the trip's stage wins while it is out")

        assertEquals("A PATCH NOW, 12% AWAY", PadSheetBoxes.make(bare.copy(source = mapOf("desampled" to "12%"))))
        for (line in listOf(Copy.OUTSIDE_LISTENING, Copy.OUTSIDE_SENDING)) {
            assertTrue(line.length <= PadSheetBoxes.SUMMARY_CHARS, "a stage must fit the strip: '$line'")
        }
    }
}
