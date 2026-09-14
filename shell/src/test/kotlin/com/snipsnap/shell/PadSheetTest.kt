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
    fun `the six segments the design draws, NONE first, SMEAR and DUST last`() {
        assertEquals(listOf("NONE", "CRUSH", "TAPE", "DIRT", "SMEAR", "DUST"), PadSheet.SEGMENTS)
    }

    @Test
    fun `every era segment names a real era`() {
        for (segment in PadSheet.SEGMENTS - PadSheet.NONE - PadSheet.SMEAR - PadSheet.DUST) {
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
        assertTrue(PadSheet.DUST in PadSheet.SEGMENTS, "DUST is row one too")
        assertNull(PadSheet.eraFor(PadSheet.DUST), "DUST names no era, like SMEAR")
        assertNull(PadSheet.treatmentFor(PadSheet.DUST))
        assertTrue(PadSheet.TAIL in PadSheet.ANATOMY_SEGMENTS, "TAIL is row two")
        assertFalse(PadSheet.TAIL in PadSheet.SEGMENTS)
        assertFalse(PadSheet.SMEAR in PadSheet.ANATOMY_SEGMENTS)

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
        for (segment in PadSheet.SEGMENTS - PadSheet.NONE - PadSheet.SMEAR - PadSheet.DUST) {
            val era = PadSheet.eraFor(segment)!!
            assertEquals(segment, PadSheet.segmentFor(era))
        }
    }

    @Test
    fun `an era with no segment - the phone ruling - answers null, not NONE`() {
        assertNull(PadSheet.segmentFor("phone"))
    }

    // ---- rows two through six: the regroup ----

    @Test
    fun `the card reads in zones, and holds every chip`() {
        assertEquals(listOf("NONE", "CRUSH", "TAPE", "DIRT", "SMEAR", "DUST"), PadSheet.SEGMENTS)
        assertEquals(listOf("SWELL", "TAIL", "SKIM", "GHOST", "SPIKE"), PadSheet.ANATOMY_SEGMENTS)
        assertEquals(listOf("PUNCH", "RING", "DUB", "VINYL", "PHASE"), PadSheet.CHARACTER_SEGMENTS)
        assertEquals(listOf("SLAP", "WASH", "ROLL", "GATE"), PadSheet.TIME_SEGMENTS)
        assertEquals(listOf("FLIP", "STOP", "START", "PITCH"), PadSheet.TRANSPORT_SEGMENTS)
        assertEquals(listOf("TUNE", "BODY", "WOBBLE", "ETERNAL"), PadSheet.KEYED_SEGMENTS)
        assertEquals(28, PadSheet.ALL_SEGMENTS.size, "the card should draw 28 chips")
    }

    /**
     * The regroup moves every chip but row one. A chip silently dropped
     * during the rearrangement would not be caught by the duplicate/width/
     * registry checks below — a deleted segment is simply never iterated —
     * so this test checks the inventory itself, independent of which row
     * anything ended up on.
     */
    @Test
    fun `the regroup keeps every chip the card already drew, adds its seven, and the merge's DUST makes twenty-eight`() {
        val expected = setOf(
            // the twenty that existed before the regroup, plus the row-one
            // DUST chip a separate branch merged in later (`readDust`,
            // `com.snipsnap.audio.Dust`) - unrelated to this plan's own
            // character of the same rendered name, which lives below as VINYL.
            "NONE", "CRUSH", "TAPE", "DIRT", "SMEAR", "DUST",
            "TAIL", "SLAP", "WASH", "PUNCH",
            "GHOST", "STOP", "START", "FLIP",
            "SKIM", "DUB", "SWELL", "TUNE",
            "BODY", "WOBBLE", "ETERNAL",
            // the seven this plan adds
            "SPIKE", "RING", "VINYL", "PHASE", "PITCH", "ROLL", "GATE",
        )
        assertEquals(expected, PadSheet.ALL_SEGMENTS.toSet(), "the card's inventory changed")
        assertEquals(28, PadSheet.ALL_SEGMENTS.size, "a chip is drawn twice or missing")
    }

    @Test
    fun `every segment names something real, whatever row it sits on`() {
        for (segment in PadSheet.ALL_SEGMENTS) {
            // NONE means "no treatment"; SMEAR and DUST (row one) each ride
            // their own recipe shape ({"verb":"smear",...} / {"verb":"dust",
            // "amount","tape"}) and deliberately dispatch through none of the
            // three doors (see the dedicated SMEAR-vs-TAIL test above, and
            // readDust's KDoc) — all three are real "no Treatment" answers,
            // not gaps, on any row.
            if (segment == PadSheet.NONE || segment == PadSheet.SMEAR || segment == PadSheet.DUST) {
                assertNull(PadSheet.treatmentFor(segment), "$segment must resolve to no Treatment")
                continue
            }
            val t = PadSheet.treatmentFor(segment)
                ?: throw AssertionError("$segment draws a chip but does nothing")
            when (t) {
                is PadSheet.Treatment.Era -> {}
                is PadSheet.Treatment.Character ->
                    assertTrue(t.name in Treatments.names, "$segment maps to '${t.name}', which Treatments does not know")
                is PadSheet.Treatment.Keyed ->
                    assertTrue(t.name in Keyed.NAMES, "$segment maps to '${t.name}', which Keyed does not know")
            }
            assertEquals(segment, PadSheet.segmentFor(t), "segmentFor is not treatmentFor's inverse for $segment")
        }
        assertNull(PadSheet.segmentForKeyed("frozen"), "a keyed name no segment draws lights nothing")
    }

    @Test
    fun `the card draws every chip once and only once`() {
        assertEquals(
            PadSheet.ALL_SEGMENTS.size,
            PadSheet.ALL_SEGMENTS.toSet().size,
            "a word is drawn on two rows: ${PadSheet.ALL_SEGMENTS.groupBy { it }.filterValues { it.size > 1 }.keys}",
        )
        assertEquals(PadSheet.ROWS, PadSheet.ROWS.filter { it.isNotEmpty() }, "an empty row would draw nothing")
        // Row one earned a sixth chip (DUST) from the merged branch, so the
        // ceiling moved from 5 to 6 rather than the regroup's rows growing.
        assertTrue(PadSheet.ROWS.maxOf { it.size } <= 6, "a row wider than 6 makes every chip narrower")
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
