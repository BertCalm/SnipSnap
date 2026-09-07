package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Eras
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Treatments
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

    // ---- row two: the characters ----

    @Test
    fun `rows two and three draw four characters each and all rows read in order`() {
        assertEquals(listOf("TAIL", "SLAP", "WASH", "PUNCH"), PadSheet.CHARACTER_SEGMENTS)
        assertEquals(listOf("GHOST", "STOP", "START", "FLIP"), PadSheet.MORE_SEGMENTS)
        assertEquals(listOf("SKIM", "DUB", "SWELL", "TUNE"), PadSheet.EXTRA_SEGMENTS)
        assertEquals(listOf("BODY", "WOBBLE", "ETERNAL"), PadSheet.KEYED_SEGMENTS)
        assertEquals(listOf(PadSheet.SEGMENTS, PadSheet.CHARACTER_SEGMENTS, PadSheet.MORE_SEGMENTS, PadSheet.EXTRA_SEGMENTS, PadSheet.KEYED_SEGMENTS), PadSheet.ROWS)
        assertEquals(PadSheet.ALL_SEGMENTS.size, PadSheet.ALL_SEGMENTS.toSet().size, "no word on two rows")
    }

    @Test
    fun `every character segment names a real rack character, and the keyed segments a real keyed treatment`() {
        assertEquals(PadSheet.Treatment.Keyed("retuned"), PadSheet.treatmentFor(PadSheet.TUNE))
        assertEquals(PadSheet.TUNE, PadSheet.segmentFor(PadSheet.Treatment.Keyed("retuned")))
        for (segment in listOf(PadSheet.TUNE) + PadSheet.KEYED_SEGMENTS) {
            val t = PadSheet.treatmentFor(segment)
            assertTrue(t is PadSheet.Treatment.Keyed, "$segment is keyed")
            assertTrue(t!!.name in Keyed.NAMES, "$segment maps to '${t.name}', which Keyed does not know")
            assertEquals(segment, PadSheet.segmentFor(t))
        }
        assertNull(PadSheet.segmentForKeyed("frozen"), "a keyed name no segment draws lights nothing")
        for (segment in PadSheet.ROWS.drop(1).dropLast(1).flatten()) {
            if (segment == PadSheet.TUNE) continue
            val t = PadSheet.treatmentFor(segment)
            assertTrue(t is PadSheet.Treatment.Character, "$segment is a character")
            assertTrue(t!!.name in Treatments.names, "$segment maps to '${t.name}', which Treatments does not know")
            assertEquals(segment, PadSheet.segmentFor(t), "segmentFor is treatmentFor's inverse")
        }
    }

    @Test
    fun `treatmentFor speaks both rows, NONE is null, and eraFor still refuses row two`() {
        assertEquals(PadSheet.Treatment.Era("sp1200"), PadSheet.treatmentFor("CRUSH"))
        assertEquals(PadSheet.Treatment.Character("smeared"), PadSheet.treatmentFor("TAIL"))
        assertNull(PadSheet.treatmentFor(PadSheet.NONE))
        assertFailsWith<IllegalArgumentException> { PadSheet.treatmentFor("SPARKLE") }
        // eraFor refuses row two (TAIL is not in SEGMENTS); SMEAR itself
        // *is* in SEGMENTS (row one) and legitimately answers null - see
        // the dedicated test above.
        assertFailsWith<IllegalArgumentException> { PadSheet.eraFor("SLAP") }
    }

    @Test
    fun `the phone ruling on the character rows - crushed lights no segment, reversed lights FLIP`() {
        assertEquals("FLIP", PadSheet.segmentForCharacter("reversed"))
        assertNull(PadSheet.segmentForCharacter("crushed"))
    }

    @Test
    fun `read lights the right segment for either recipe shape, and nothing for the rest`() {
        val era = JsonValue.Obj(linkedMapOf<String, JsonValue>("era" to JsonValue.Str("tape"), "amount" to JsonValue.Num(0.5)))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Era("tape"), 0.5f, "TAPE"), PadSheet.read(era))

        val phone = JsonValue.Obj(linkedMapOf<String, JsonValue>("era" to JsonValue.Str("phone"), "amount" to JsonValue.Num(1.0)))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Era("phone"), 1f, null), PadSheet.read(phone))

        val smeared = PadRecipe(fx = Treatments.chain("smeared", 0.4f), treatment = "smeared", amount = 0.4f).toJsonValue()
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Character("smeared"), 0.4f, "TAIL"), PadSheet.read(smeared))

        val twin = PadRecipe(fx = Treatments.chain("crushed"), treatment = "crushed", amount = 1f).toJsonValue()
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Character("crushed"), 1f, null), PadSheet.read(twin))

        val fxOnly = PadRecipe(fx = Treatments.chain("washed")).toJsonValue()
        assertNull(PadSheet.read(fxOnly), "a chain with no name claims no segment")
        assertNull(PadSheet.read(null))

        val retuned = JsonValue.Obj(linkedMapOf<String, JsonValue>("keyed" to JsonValue.Str("retuned"), "key" to JsonValue.Str("C MAJOR"), "amount" to JsonValue.Num(1.0), "seed" to JsonValue.Num(7.0)))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("retuned"), 1f, "TUNE"), PadSheet.read(retuned))
        val bodied = JsonValue.Obj(linkedMapOf<String, JsonValue>("keyed" to JsonValue.Str("bodied"), "key" to JsonValue.Str("C"), "amount" to JsonValue.Num(0.5)))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("bodied"), 0.5f, "BODY"), PadSheet.read(bodied))
    }
}
