package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Eras
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Treatments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * Finding 20: SMEAR (row one) and the rack character named `"smeared"`
     * (row two, drawn as TAIL) are unrelated DSP with near-identical names —
     * row one bakes a destructive HPSS stretch, row two pulls transients
     * through a non-destructive FX chain. The card resolved that by drawing
     * the character as TAIL, but nothing stopped the two from being wired
     * back together by someone reading only the names.
     *
     * The engine name cannot be changed to break the tie: `"smeared"` is
     * written into recipes on disk, so renaming it would orphan every kit
     * that carries one. So the separation is pinned instead.
     */
    @Test
    fun `SMEAR and the smeared character stay apart, whatever their names suggest`() {
        // The character belongs to TAIL and to nothing else.
        assertEquals("TAIL", PadSheet.segmentForCharacter("smeared"))
        assertEquals(PadSheet.Treatment.Character("smeared"), PadSheet.treatmentFor(PadSheet.TAIL))

        // SMEAR is neither an era nor that character - it has no Treatment at
        // all, because it rides its own door.
        assertNull(PadSheet.eraFor(PadSheet.SMEAR))
        assertNull(
            PadSheet.treatmentFor(PadSheet.SMEAR),
            "SMEAR must never dispatch through the era/character/keyed doors",
        )

        // They sit on different rows, and only one of them is on row one.
        assertTrue(PadSheet.SMEAR in PadSheet.SEGMENTS, "SMEAR is row one")
        assertTrue(PadSheet.TAIL in PadSheet.CHARACTER_SEGMENTS, "TAIL is row two")
        assertFalse(PadSheet.TAIL in PadSheet.SEGMENTS)
        assertFalse(PadSheet.SMEAR in PadSheet.CHARACTER_SEGMENTS)

        // And the card never draws one word for both.
        assertTrue(
            PadSheet.displayLabel(PadSheet.SMEAR) != PadSheet.displayLabel(PadSheet.TAIL),
            "two chips on one card cannot share a label",
        )
    }

    @Test
    fun `an unknown segment is refused, not silently ignored`() {
        assertFailsWith<IllegalArgumentException> { PadSheet.eraFor("WOBBLE") }
    }

    @Test
    fun `the sheet opens at NONE, 70 percent - a first tap you can hear`() {
        assertEquals(0.7f, PadSheet.DEFAULT_AMOUNT)
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

    @Test
    fun `displayLabel speaks the card's renamed chips, and every id it doesn't rename unchanged`() {
        // The seven chips the language pass renamed - the card's own word,
        // not the segment id every other door (onSegmentTap, treatmentFor,
        // read's Applied.segment) still reads and writes.
        assertEquals("IN KEY", PadSheet.displayLabel("TUNE"))
        assertEquals("SPIN DOWN", PadSheet.displayLabel("STOP"))
        assertEquals("SPIN UP", PadSheet.displayLabel("START"))
        assertEquals("ECHO", PadSheet.displayLabel("SLAP"))
        assertEquals("DRONE", PadSheet.displayLabel("ETERNAL"))
        assertEquals("SMOOTH", PadSheet.displayLabel("SKIM"))
        // Every other segment on the card draws its own id unchanged.
        for (segment in PadSheet.ALL_SEGMENTS - setOf("TUNE", "STOP", "START", "SLAP", "ETERNAL", "SKIM")) {
            assertEquals(segment, PadSheet.displayLabel(segment), "$segment wasn't renamed - it should draw itself")
        }
    }

    // ---- NONE, the un-treat (September UAT, finding 13) ----

    private fun treated(
        recipe: JsonValue.Obj?,
        layers: List<KitLayer> = emptyList(),
    ) = KitPad(slot = 1, sampleFile = "a1_kick.wav", recipe = recipe, velocityLayers = layers)

    private val ERA = JsonValue.Obj(
        linkedMapOf<String, JsonValue>("era" to JsonValue.Str("tape"), "amount" to JsonValue.Num(0.5)),
    )

    @Test
    fun `NONE is live over a treatment and dead over an untreated pad - every other chip is always live`() {
        assertTrue(PadSheet.tappable(PadSheet.NONE, isNoneState = false), "NONE is the un-treat when there is one")
        assertFalse(PadSheet.tappable(PadSheet.NONE, isNoneState = true), "NONE over NONE is a no-op, not a toast")
        for (segment in PadSheet.ALL_SEGMENTS - PadSheet.NONE) {
            assertTrue(PadSheet.tappable(segment, isNoneState = true), "$segment is live on an untreated pad")
            assertTrue(PadSheet.tappable(segment, isNoneState = false), "$segment re-treats when tapped again")
        }
    }

    @Test
    fun `NONE has nothing to undo on a pad no card treatment rides`() {
        assertEquals(PadSheet.UnTreat.NOTHING, PadSheet.unTreatState(treated(null), setOf("a1_kick.wav")))
        // An fx-only chain names no treatment, so the card never lit a
        // segment for it and NONE claims no undo either.
        val fxOnly = PadRecipe(fx = Treatments.chain("washed")).toJsonValue()
        assertEquals(PadSheet.UnTreat.NOTHING, PadSheet.unTreatState(treated(fxOnly), setOf("a1_kick.wav")))
    }

    @Test
    fun `NONE is ready when the bin holds every file the pad references`() {
        assertEquals(PadSheet.UnTreat.READY, PadSheet.unTreatState(treated(ERA), setOf("a1_kick.wav")))

        val layered = treated(ERA, listOf(KitLayer("a1_kick_soft.wav", 1, 63), KitLayer("a1_kick.wav", 64, 127)))
        assertEquals(
            PadSheet.UnTreat.READY,
            PadSheet.unTreatState(layered, setOf("a1_kick.wav", "a1_kick_soft.wav")),
        )
    }

    @Test
    fun `SMEAR rides its own recipe shape and is just as undoable`() {
        val smear = JsonValue.Obj(
            linkedMapOf<String, JsonValue>("verb" to JsonValue.Str("smear"), "amount" to JsonValue.Num(0.4)),
        )
        // read() cannot see SMEAR on purpose, so a state that consulted only
        // read() would call a smeared pad untreated and grey NONE out on it.
        assertNull(PadSheet.read(smear))
        assertEquals(PadSheet.UnTreat.READY, PadSheet.unTreatState(treated(smear), setOf("a1_kick.wav")))
    }

    @Test
    fun `a ghost layer that postdates the treatment refuses rather than half-restoring`() {
        val layered = treated(ERA, listOf(KitLayer("a1_kick_soft.wav", 1, 63), KitLayer("a1_kick.wav", 64, 127)))
        assertEquals(
            PadSheet.UnTreat.GHOSTS_POSTDATE,
            PadSheet.unTreatState(layered, setOf("a1_kick.wav")),
        )
    }

    @Test
    fun `a treatment the bin never held is named but not undoable`() {
        // A bank-B twin, a CLI treat in another folder, a bin since emptied.
        assertEquals(PadSheet.UnTreat.NOT_BINNED, PadSheet.unTreatState(treated(ERA), emptySet()))
        // The main sample decides: a bin holding only the ghost layer is
        // still no earlier take of the pad.
        val layered = treated(ERA, listOf(KitLayer("a1_kick_soft.wav", 1, 63), KitLayer("a1_kick.wav", 64, 127)))
        assertEquals(
            PadSheet.UnTreat.NOT_BINNED,
            PadSheet.unTreatState(layered, setOf("a1_kick_soft.wav")),
        )
    }
}
