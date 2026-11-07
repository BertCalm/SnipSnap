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
        assertEquals(listOf("TREATMENT", "SHAPE", "OUTSIDE", "MUTATE", "MAKE"), PadSheetBoxes.ORDER.map { it.legend })
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

    @Test
    fun `a pedal board names each pedal and its AMT on the strip, and marks the pad as treated`() {
        fun pick(key: String, amount: Float) = com.snipsnap.synth.PedalBoard.Pick(key, amount)
        val two = com.snipsnap.synth.PedalBoard(listOf(pick("wahed", 0.7f), pick("driven", 1f)))
        val pad = bare.copy(recipe = com.snipsnap.synth.PadRecipe(pedals = two).toJsonValue())
        assertEquals("WAH 70% · DRIVE 100%", PadSheetBoxes.summaries(pad, null)[PadSheetBoxes.Box.TREATMENT])
        assertTrue(PadSheetBoxes.isTouched(pad), "a board pad was UNTOUCHED on the grid")
        // Six pedals, all heard (TOUCH after the amp, ROCK before it): the AMTs do not fit the strip, then the words do not either.
        val six = listOf("compressed", "driven", "touchwahed", "forged", "delayed", "rockwahed")
            .map { if (it == "touchwahed") com.snipsnap.synth.PedalBoard.Pick(it, 1f, com.snipsnap.synth.PedalSide.POST) else pick(it, 1f) }
        assertEquals(six, PedalSlate.audible(six), "the fixture's six are all heard")
        assertEquals("6 PEDALS", PadSheetBoxes.pedals(six))
        val four = listOf("compressed", "driven", "touchwahed", "delayed").map { pick(it, 1f) }
        assertEquals("SUSTAIN · DRIVE · TOUCH · DELAY", PadSheetBoxes.pedals(four))
        for (b in listOf(two.picks, four, six)) assertTrue(PadSheetBoxes.pedals(b).length <= PadSheetBoxes.SUMMARY_CHARS)
        // One pedal reads as a chip does, so the same DELAY at 70 reads one way whichever door made it.
        assertEquals("DELAY · 70%", PadSheetBoxes.pedals(listOf(pick("delayed", 0.7f))))
        // The strip names only what plays: WAH under TOUCH on one side is silent, and so is a pick at AMT 0.
        val shadowed = com.snipsnap.synth.PedalBoard(listOf(pick("wahed", 0.7f), pick("touchwahed", 0.7f), pick("driven", 0f)))
        val shadowedPad = bare.copy(recipe = com.snipsnap.synth.PadRecipe(pedals = shadowed).toJsonValue())
        assertEquals("TOUCH · 70%", PadSheetBoxes.summaries(shadowedPad, null)[PadSheetBoxes.Box.TREATMENT])
    }

    /**
     * J36: KIT's grid showed nothing to tell a treated pad from a raw one,
     * so sixteen treated pads could only be told apart by holding each in
     * turn — while every strip the sheet draws already knew.
     *
     * The trap this pins is [PadSheetBoxes.Box.MAKE]. Its strip is never
     * UNTOUCHED, because it advertises what the box can do rather than
     * reporting what the pad carries. "Any strip that is not UNTOUCHED"
     * would call every pad in every kit treated, which is the same answer
     * as marking none of them.
     */
    @Test
    fun `touched names the benches a pad carries, and MAKE's advert is not one`() {
        assertEquals(emptySet(), PadSheetBoxes.touched(bare), "a bare pad carries nothing")
        assertTrue(!PadSheetBoxes.isTouched(bare))
        // The bare pad's MAKE strip is non-UNTOUCHED, which is exactly what
        // would make a naive predicate say "treated" here.
        assertTrue(
            PadSheetBoxes.summaries(bare, null)[PadSheetBoxes.Box.MAKE] != PadSheetBoxes.UNTOUCHED,
            "if MAKE ever starts reading UNTOUCHED this test stops proving anything — the point is that " +
                "it does not, and touched() excludes it anyway.",
        )

        val shaped = bare.copy(attack = 0.5f)
        assertEquals(setOf(PadSheetBoxes.Box.SHAPE), PadSheetBoxes.touched(shaped))
        assertTrue(PadSheetBoxes.isTouched(shaped))

        // An OUTSIDE trip in flight counts while it is out.
        assertEquals(setOf(PadSheetBoxes.Box.OUTSIDE), PadSheetBoxes.touched(bare, outsideStage = "OUT THERE"))

        // DE-SAMPLE is the one thing MAKE leaves behind, so it does count.
        val desampled = bare.copy(source = bare.source + ("desampled" to "2 steps"))
        assertEquals(setOf(PadSheetBoxes.Box.MAKE), PadSheetBoxes.touched(desampled))

        // Benches accumulate rather than replacing one another.
        assertEquals(
            setOf(PadSheetBoxes.Box.SHAPE, PadSheetBoxes.Box.MAKE),
            PadSheetBoxes.touched(shaped.copy(source = shaped.source + ("desampled" to "2 steps"))),
        )
    }


    @Test
    fun `the EFFECTS door says what is applied from the three benches on that screen, and the VARIATIONS door what is inside`() {
        assertEquals(PadSheetBoxes.NOTHING_APPLIED, PadSheetBoxes.effectsLine(bare))
        assertEquals(PadSheetBoxes.MUTATE_OFFERS, PadSheetBoxes.variationsLine(bare))

        // A pedal reads as the card does, with the joint dropped: "DELAY 70%", the way a treatment reads "CRUSH 35%".
        val pedal = com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("delayed", 0.7f)))
        val delayed = bare.copy(recipe = com.snipsnap.synth.PadRecipe(pedals = pedal).toJsonValue())
        assertEquals("DELAY 70%", PadSheetBoxes.effectsLine(delayed))

        // SHAPE and a trip out count too; MUTATE's and MAKE's strips do not.
        assertEquals("ATK 200 MS", PadSheetBoxes.effectsLine(bare.copy(attack = 0.5f)))
        assertEquals("DELAY 70% · ATK 200 MS", PadSheetBoxes.effectsLine(delayed.copy(attack = 0.5f)))
        assertEquals(Copy.OUTSIDE_LISTENING, PadSheetBoxes.effectsLine(bare, outsideStage = Copy.OUTSIDE_LISTENING))
        assertEquals(
            PadSheetBoxes.NOTHING_APPLIED,
            PadSheetBoxes.effectsLine(bare.copy(source = bare.source + ("desampled" to "2 steps"))),
            "MAKE's DE-SAMPLE is not on the EFFECTS screen",
        )

        // Words that would not fit the line become a count, as the pedal strip's own do.
        val full = delayed.copy(attack = 1f, decay = 0.5f, cutoff = 1f, resonance = 1f)
        assertEquals("2 APPLIED", PadSheetBoxes.effectsLine(full))
        for (line in listOf(PadSheetBoxes.NOTHING_APPLIED, PadSheetBoxes.MUTATE_OFFERS, PadSheetBoxes.effectsLine(delayed))) {
            assertTrue(line.length <= PadSheetBoxes.SUMMARY_CHARS, "a door line must fit the strip: '$line'")
        }
    }

    @Test
    fun `every box sits on one of the editor's pages, OUTSIDE with the effects, and the pages are benches`() {
        assertEquals(PadSheetPage.EFFECTS, PadSheetBoxes.page(PadSheetBoxes.Box.OUTSIDE))
        assertEquals(PadSheetPage.VARIATIONS, PadSheetBoxes.page(PadSheetBoxes.Box.MUTATE))
        assertEquals(PadSheetPage.EDITOR, PadSheetBoxes.page(PadSheetBoxes.Box.MAKE))
        // The order is the order the pages draw them in: a page's boxes are a run of ORDER.
        assertEquals(
            listOf(PadSheetPage.EFFECTS, PadSheetPage.EFFECTS, PadSheetPage.EFFECTS, PadSheetPage.VARIATIONS, PadSheetPage.EDITOR),
            PadSheetBoxes.ORDER.map { PadSheetBoxes.page(it) },
        )
        for (page in PadSheetPage.entries) assertEquals(page, PadSheetPage.of(page.bench))
        assertNull(PadSheetPage.of(Bench.SPLICE))
        assertNull(PadSheetPage.of(null))
        for (page in PadSheetPage.entries) assertTrue(page.bench in KitBench.PAD_BENCHES, "${page.name} works on one pad of one kit")
    }
}
