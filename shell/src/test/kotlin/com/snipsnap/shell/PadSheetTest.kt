package com.snipsnap.shell

import com.snipsnap.synth.Eras
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadSheetTest {

    @Test
    fun `the five segments the design draws, NONE first, SMEAR last`() {
        assertEquals(listOf("NONE", "CRUSH", "TAPE", "DIRT", "SMEAR"), PadSheet.SEGMENTS)
    }

    @Test
    fun `every era segment names a real era`() {
        for (segment in PadSheet.SEGMENTS - PadSheet.NONE - PadSheet.SMEAR) {
            val era = PadSheet.eraFor(segment)
            assertTrue(
                era in Eras.names,
                "$segment maps to '$era', which Eras does not know: ${Eras.names}",
            )
        }
    }

    @Test
    fun `NONE is the absence of an era, not an era called none`() {
        assertNull(PadSheet.eraFor(PadSheet.NONE))
    }

    @Test
    fun `SMEAR names no era - it isn't one, and never will be`() {
        assertNull(PadSheet.eraFor(PadSheet.SMEAR))
    }

    @Test
    fun `an unknown segment is refused, not silently ignored`() {
        assertFailsWith<IllegalArgumentException> { PadSheet.eraFor("WOBBLE") }
    }

    @Test
    fun `the sheet opens at NONE, 35 percent`() {
        assertEquals(0.35f, PadSheet.DEFAULT_AMOUNT)
    }

    @Test
    fun `segmentFor is eraFor's inverse for every era segment`() {
        for (segment in PadSheet.SEGMENTS - PadSheet.NONE - PadSheet.SMEAR) {
            val era = PadSheet.eraFor(segment)!!
            assertEquals(segment, PadSheet.segmentFor(era))
        }
    }

    @Test
    fun `an era with no segment - the phone ruling - answers null, not NONE`() {
        assertNull(PadSheet.segmentFor("phone"))
    }
}
