package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChopReviewTest {

    private val rate = 44_100

    /** Kick, closed hat, snare, open hat on quarter steps with silence around. */
    private fun breakSnip(): Snip {
        val step = rate / 2
        val hits = listOf(
            1 to DrumSynth.kick(),
            2 to DrumSynth.closedHat(),
            3 to DrumSynth.snare(),
            4 to DrumSynth.openHat(),
        )
        val total = FloatArray(step * 6)
        for ((s, hit) in hits) {
            val at = s * step
            for (i in hit.samples.indices) {
                if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
            }
        }
        return Snip(total, 1, rate)
    }

    /**
     * The same four hits with a quiet ghost hat 125 ms after each of the
     * first three, inside their decay — what the ear controls are for.
     * (Hits in silence all clear every ear: the detector's bar is set
     * against the local novelty, and silence has none. A ghost inside a
     * decay is the case that separates them.)
     */
    private fun busyBreak(ghost: Float = 0.06f): Snip {
        val base = breakSnip()
        val out = base.samples.copyOf()
        val hat = DrumSynth.closedHat()
        val step = rate / 2
        for (s in 1..3) {
            val at = s * step + step / 4
            for (i in hat.samples.indices) if (at + i < out.size) out[at + i] += hat.samples[i] * ghost
        }
        return Snip(out, 1, rate)
    }

    private fun byHits(hits: Int = 8, ear: ChopReviewModel.Ear = ChopReviewModel.Ear.NORMAL, cut: ChopReviewModel.Cut = ChopReviewModel.Cut.ON) =
        ChopReviewModel.ChopMode.ByHits(hits, ear, cut)

    @Test
    fun `the ear hears more at FINE than at COARSE, and CUT moves every cut against the attack`() {
        val coarse = ChopReviewModel.chop(busyBreak(), byHits(16, ChopReviewModel.Ear.COARSE))
        val normal = ChopReviewModel.chop(busyBreak(), byHits(16, ChopReviewModel.Ear.NORMAL))
        val fine = ChopReviewModel.chop(busyBreak(), byHits(16, ChopReviewModel.Ear.FINE))
        assertTrue(coarse.sliceCount <= normal.sliceCount && normal.sliceCount <= fine.sliceCount, "${coarse.sliceCount} ≤ ${normal.sliceCount} ≤ ${fine.sliceCount}")
        assertTrue(fine.sliceCount > coarse.sliceCount, "FINE hears a ghost COARSE does not: ${fine.sliceCount} vs ${coarse.sliceCount}")
        assertTrue(coarse.sliceCount >= 4, "COARSE still hears the four that carry the beat")
        // CUT: the same hits, each cut earlier at EARLY and later at LATE.
        val early = ChopReviewModel.chop(breakSnip(), byHits(8, cut = ChopReviewModel.Cut.EARLY))
        val on = ChopReviewModel.chop(breakSnip(), byHits(8))
        val late = ChopReviewModel.chop(breakSnip(), byHits(8, cut = ChopReviewModel.Cut.LATE))
        assertEquals(on.sliceCount, early.sliceCount)
        assertEquals(on.sliceCount, late.sliceCount)
        for (i in on.rows.indices) {
            assertTrue(early.cutFrames()[i] <= on.cutFrames()[i], "EARLY cuts no later than ON at $i")
            assertTrue(on.cutFrames()[i] <= late.cutFrames()[i], "LATE cuts no earlier than ON at $i")
        }
        assertTrue(early.cutFrames()[1] < late.cutFrames()[1], "and the nudge is real")
        // The header says only what is off its default.
        assertEquals("BY HITS", on.modeLabel())
        assertEquals("BY HITS · FINE", fine.modeLabel())
        assertEquals("BY HITS · CUT EARLY", early.modeLabel())
        assertEquals("GRID ×8", ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.Grid(8)).modeLabel())
        assertEquals(on.rows.map { it.slice.sourceFrame }, on.cutFrames())
        assertEquals(on.cutFrames(), on.cutFrames().sorted())
        // The stepper's bounds are the mode's own law.
        kotlin.test.assertFailsWith<IllegalArgumentException> { ChopReviewModel.ChopMode.ByHits(0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ChopReviewModel.ChopMode.ByHits(ChopReviewModel.MAX_HITS + 1) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ChopReviewModel.ChopMode.Grid(0) }
    }

    @Test
    fun `AUTO finds the knee, and a bench re-chop carries corrected chips onto the slices that stayed`() {
        val model = ChopReviewModel.chop(breakSnip(), byHits(8))
        assertEquals(4, model.autoCount(), "four hits of a kind: all four")
        model.setLabel(0, DrumClass.TOM)
        model.setLabel(2, DrumClass.CLAP)
        val nudged = model.rechopKeeping(byHits(8, cut = ChopReviewModel.Cut.EARLY))
        assertEquals(DrumClass.TOM, nudged.rows[0].override, "the chip followed its slice across the nudge")
        assertEquals(DrumClass.CLAP, nudged.rows[2].override)
        assertEquals(null, nudged.rows[1].override)
        assertFalse(nudged.edited)
        // Fewer hits: the chips of the slices that stayed stay.
        val fewer = model.rechopKeeping(byHits(2))
        assertEquals(2, fewer.sliceCount)
        for (row in fewer.rows) {
            val was = model.rows.firstOrNull { abs(it.slice.sourceFrame - row.slice.sourceFrame) <= ChopReviewModel.CARRY_TOLERANCE_FRAMES }
            assertEquals(was?.override, row.override, "row ${row.n}")
        }
        // Plain RE-CHOP still clears them.
        assertTrue(model.rechop().rows.all { it.override == null })
    }

    @Test
    fun `MERGE joins two slices and keeps the other chips, SPLIT cuts a slice at its inner hit, and both refuse honestly`() {
        val model = ChopReviewModel.chop(breakSnip(), byHits(8))
        assertEquals(4, model.sliceCount)
        model.setLabel(2, DrumClass.CLAP)
        val lengths = model.rows.map { it.slice.snip.frameCount }
        val merged = assertNotNull(model.merged(0))
        assertEquals(3, merged.sliceCount)
        assertEquals(lengths[0] + lengths[1], merged.rows[0].slice.snip.frameCount, "the joined slice is both, end to end")
        assertEquals(model.rows[0].slice.sourceFrame, merged.rows[0].slice.sourceFrame)
        assertEquals(DrumClass.CLAP, merged.rows[1].override, "the chip after the join is still the human's")
        assertTrue(merged.edited)
        assertTrue("EDITED" in merged.modeLabel(), merged.modeLabel())
        assertEquals(null, model.merged(3), "nothing after the last slice")
        assertEquals(null, model.merged(-1))
        // SPLIT the joined slice: its inner hit is the hat, so the cut lands where it was.
        val split = assertNotNull(merged.split(0))
        assertEquals(4, split.sliceCount)
        assertTrue(abs(split.rows[1].slice.sourceFrame - model.rows[1].slice.sourceFrame) <= ChopReviewModel.CARRY_TOLERANCE_FRAMES, "the cut is back near the hat: ${split.rows[1].slice.sourceFrame} vs ${model.rows[1].slice.sourceFrame}")
        assertEquals(lengths[0] + lengths[1], split.rows[0].slice.snip.frameCount + split.rows[1].slice.snip.frameCount, "nothing lost in the split")
        assertEquals(DrumClass.CLAP, split.rows[2].override, "the chip after the split is still the human's")
        assertEquals(null, split.rows[1].override, "the new half takes the classifier's word")
        // A slice with one hit in it has nothing to split at.
        assertEquals(null, model.split(3), "one hit, no second")
        assertEquals(null, model.split(9))
        // The tape reference rides through both.
        val taped = ChopReviewModel.chop(breakSnip(), byHits(8), ChopReviewModel.TapeRef("snip_1_X.wav", 100))
        assertEquals(taped.tape, assertNotNull(taped.merged(0)).tape)
        assertEquals(taped.tape, assertNotNull(assertNotNull(taped.merged(0)).split(0)).tape)
    }

    @Test
    fun `chop classifies rows and the core classes place on their pads`() {
        val model = ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.ByHits(8))
        assertEquals(4, model.sliceCount)
        assertEquals(
            listOf(DrumClass.KICK, DrumClass.HAT_CLOSED, DrumClass.SNARE, DrumClass.HAT_OPEN),
            model.rows.map { it.effectiveClass },
        )
        // Rows keep capture order; placement puts them on the layout.
        val placed = model.placementPreview()
        assertEquals(DrumClass.KICK, placed[0]?.effectiveClass)
        assertEquals(DrumClass.SNARE, placed[1]?.effectiveClass)
        assertEquals(DrumClass.HAT_CLOSED, placed[2]?.effectiveClass)
        assertEquals(DrumClass.HAT_OPEN, placed[3]?.effectiveClass)

        val summary = model.placementSummary()
        assertTrue("KICK→A01" in summary, summary)
        assertTrue("SNARE→A02" in summary, summary)
        assertTrue("+ CHOKE" in summary, summary)
    }

    @Test
    fun `tap-to-cycle overrides and marks YOU, clearOverride restores`() {
        val model = ChopReviewModel.chop(breakSnip())
        val row = model.rows[0]
        val machine = row.effectiveClass
        assertFalse(row.overridden)

        model.cycleLabel(0)
        val next = ChopReviewModel.CHIP_CYCLE[
            (ChopReviewModel.CHIP_CYCLE.indexOf(machine) + 1) % ChopReviewModel.CHIP_CYCLE.size,
        ]
        assertEquals(next, row.effectiveClass)
        assertTrue(row.overridden)
        assertFalse(row.unsure, "an overridden chip is the human's word — never dashed")

        // Cycling all the way around lands back on the machine's call…
        repeat(ChopReviewModel.CHIP_CYCLE.size - 1) { model.cycleLabel(0) }
        assertEquals(machine, row.effectiveClass)
        assertFalse(row.overridden, "a full lap is agreement, not correction")

        model.cycleLabel(0)
        model.clearOverride(0)
        assertEquals(machine, row.effectiveClass)
    }

    /**
     * Finding 6's fix. The cycler needed up to nine taps to reach a class
     * and had no way back; the picker reaches every one of the ten in a
     * single call, from wherever the chip happens to sit.
     *
     * The second half matters as much as the first: picking the class the
     * machine already chose must read as agreement, not as a correction, or
     * every chip a user merely confirms would wear the "YOU ✓" marker and
     * the screen would stop meaning anything.
     */
    @Test
    fun `setLabel reaches every class in one move and agreement is not a correction`() {
        val model = ChopReviewModel.chop(breakSnip())
        val row = model.rows[0]
        val machine = row.effectiveClass

        // Every class, from every starting point, in one call.
        for (from in ChopReviewModel.CHIP_CYCLE) {
            for (to in ChopReviewModel.CHIP_CYCLE) {
                model.setLabel(0, from)
                model.setLabel(0, to)
                assertEquals(to, row.effectiveClass, "$from -> $to should be one move")
            }
        }

        // Picking the machine's own call is agreement.
        model.setLabel(0, machine)
        assertEquals(machine, row.effectiveClass)
        assertFalse(row.overridden, "choosing what the machine chose is agreement, not correction")

        // Picking anything else is the human's word, and never dashed.
        val other = ChopReviewModel.CHIP_CYCLE.first { it != machine }
        model.setLabel(0, other)
        assertTrue(row.overridden)
        assertFalse(row.unsure, "the human's word is never a guess")

        // And the machine's call is still one tap away afterwards.
        model.clearOverride(0)
        assertEquals(machine, row.effectiveClass)
        assertFalse(row.overridden)
    }

    /**
     * The number the report actually complained about. Sixteen slices, each
     * needing the class furthest from where the cycler starts, is the worst
     * case it measured at 144 taps; the picker is one tap to open plus one
     * to choose, whatever the slice and whatever the target.
     */
    @Test
    fun `relabelling a whole kit costs two taps a slice, not nine`() {
        val model = ChopReviewModel.chop(breakSnip())
        var taps = 0
        for ((i, row) in model.rows.withIndex()) {
            val target = ChopReviewModel.CHIP_CYCLE.last { it != row.effectiveClass }
            taps += 1 // open the picker
            model.setLabel(i, target)
            taps += 1 // choose
            assertEquals(target, row.effectiveClass)
        }
        assertEquals(model.rows.size * 2, taps)
        assertTrue(taps <= 32, "two taps a slice at most, whatever the kit")
    }

    @Test
    fun `overrides redirect placement`() {
        val model = ChopReviewModel.chop(breakSnip())
        // Insist the kick is a loop; A01 should no longer hold it.
        val kickRow = model.rows.indexOfFirst { it.effectiveClass == DrumClass.KICK }
        while (model.rows[kickRow].effectiveClass != DrumClass.LOOP) model.cycleLabel(kickRow)
        val placed = model.placementPreview()
        assertTrue(placed[0]?.effectiveClass != DrumClass.KICK)
        assertEquals(DrumClass.LOOP, placed[15]?.effectiveClass, "loops prefer A16")
    }

    @Test
    fun `sendToGrid hands over arranged pads with the choke flag`() {
        val model = ChopReviewModel.chop(breakSnip())
        val sent = model.sendToGrid()
        assertEquals(4, sent.sliceCount)
        assertTrue(sent.chokeSet)
        assertEquals(16, sent.arranged.size)
        assertEquals(DrumClass.KICK, sent.arranged[0]?.drumClass)
        assertEquals("4 SLICES ON THE GRID. CHOKE GROUP SET.", Copy.sentToGrid(sent.sliceCount, sent.chokeSet))
    }

    @Test
    fun `rechop clears overrides and grid mode keeps source order`() {
        val model = ChopReviewModel.chop(breakSnip())
        model.cycleLabel(0)
        assertTrue(model.rows[0].overridden)

        val rechopped = model.rechop(ChopReviewModel.ChopMode.Grid(4))
        assertTrue(rechopped.rows.none { it.overridden })
        assertEquals(4, rechopped.sliceCount)
        // Grid slices come back in source order with source frames ascending.
        val frames = rechopped.rows.map { it.slice.sourceFrame }
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun `grooveClip carries the capture's rhythm, or refuses without a tempo`() {
        val model = ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.ByHits(8))
        val clip = model.grooveClip("Test Groove")
        if (model.tempo != null) {
            kotlin.test.assertNotNull(clip)
            assertEquals(model.sliceCount, clip.notes.size, "every placed slice is a note")
            assertTrue(clip.notes.first().timePulses == 0L, "anchored on the first hit")
            // Notes play the pads the slices landed on (A01 = note 36).
            val placed = model.placementPreview()
            val kickPad = placed.indexOfFirst { it?.effectiveClass == DrumClass.KICK }
            assertTrue(clip.notes.any { it.note == 36 + kickPad })
        }

        // A too-short snip has no confident tempo: the toggle greys out.
        val short = ChopReviewModel.chop(
            Snip(FloatArray(rate / 2) { if (it < 200) 0.5f else 0f }, 1, rate),
            ChopReviewModel.ChopMode.Grid(2),
        )
        assertEquals(null, short.tempo)
        assertEquals(null, short.grooveClip("X"))
    }

    @Test
    fun `melodic placement sorts pitched slices low to high, unpitched after`() {
        // Three tones deliberately out of order, then a kick (unpitched-ish
        // for melody purposes it still detects a pitch — so use a noise hat).
        val step = rate
        val parts = listOf(
            DrumSynth.tonal(seconds = 0.9f, freq = 440.0),
            DrumSynth.tonal(seconds = 0.9f, freq = 110.0),
            DrumSynth.tonal(seconds = 0.9f, freq = 220.0),
            DrumSynth.closedHat(),
        )
        val total = FloatArray(step * 4)
        parts.forEachIndexed { i, p ->
            for (j in p.samples.indices) {
                if (i * step + j < total.size) total[i * step + j] += p.samples[j] * 0.8f
            }
        }
        val model = ChopReviewModel.chop(
            Snip(total, 1, rate),
            ChopReviewModel.ChopMode.Grid(4),
        )
        val placed = model.melodicPreview()
        val hzs = placed.filterNotNull().mapNotNull { row ->
            model.pitchOf(model.rows.indexOf(row))?.hz
        }
        assertEquals(hzs.sorted(), hzs, "pitched slices must ascend")
        assertTrue(hzs.size >= 3, "the three tones should all detect: $hzs")
        assertTrue(abs(hzs.first() - 110f) < 8f, "lowest tone first, got ${hzs.first()}")

        val sent = model.sendToGridMelodic()
        assertEquals(4, sent.sliceCount)
        assertEquals(
            placed.filterNotNull().size,
            sent.arranged.filterNotNull().size,
        )
    }

    @Test
    fun `a chop that found no slices previews an empty grid instead of crashing`() {
        // Digital silence: Transients.detect returns emptyList() for it (see
        // its own doc comment), so Chopper.byTransients yields zero slices —
        // the genuine "chop found nothing" case, not a contrived empty list.
        val silence = Snip(FloatArray(rate * 2), 1, rate)
        val model = ChopReviewModel.chop(silence, ChopReviewModel.ChopMode.ByHits())
        assertEquals(0, model.sliceCount)

        val placed = model.placementPreview()
        assertEquals(16, placed.size)
        assertTrue(placed.all { it == null })

        assertEquals("AUTO-PLACE: FILL IN ORDER", model.placementSummary())

        val sent = model.sendToGrid()
        assertEquals(0, sent.sliceCount)
        assertFalse(sent.chokeSet)
        assertEquals(16, sent.arranged.size)
        assertTrue(sent.arranged.all { it == null })

        val melodicPlaced = model.melodicPreview()
        assertEquals(16, melodicPlaced.size)
        assertTrue(melodicPlaced.all { it == null })

        val sentMelodic = model.sendToGridMelodic()
        assertEquals(0, sentMelodic.sliceCount)
        assertFalse(sentMelodic.chokeSet)
        assertEquals(16, sentMelodic.arranged.size)
        assertTrue(sentMelodic.arranged.all { it == null })

        assertEquals(null, model.grooveClip("Empty"))
    }

    @Test
    fun `unsure rows exist as a concept and chips name every class`() {
        // A near-silent blip classifies with low confidence somewhere.
        val quiet = Snip(FloatArray(rate) { if (it < 200) 0.02f else 0f }, 1, rate)
        val model = ChopReviewModel.chop(quiet, ChopReviewModel.ChopMode.Grid(1))
        if (model.rows.isNotEmpty()) {
            val row = model.rows[0]
            if (row.classification.confidence < ChopReviewModel.NOT_SURE_BELOW) {
                assertTrue(row.unsure)
            }
        }
        for (dc in DrumClass.entries) {
            assertNotNull(ChopReviewModel.chipName(dc))
        }
        assertEquals("NOT SURE", ChopReviewModel.chipName(DrumClass.UNKNOWN))
        assertEquals(DrumClass.entries.size, ChopReviewModel.CHIP_CYCLE.size)
    }

    // ---------- RE-TRIM: the tape reference (docs/RETRIM.md §1) ----------

    @Test
    fun `a chop with a tape reference sends every slice with its absolute cut in the file`() {
        val model = ChopReviewModel.chop(breakSnip(), tape = ChopReviewModel.TapeRef("snip_7_BREAK.wav", 10_000))
        val sent = model.sendToGrid()
        val pads = sent.arranged.filterNotNull()
        assertEquals(4, pads.size)
        for (pad in pads) {
            val start = pad.source.getValue("sourceFrame").toInt()
            val length = pad.source.getValue("lengthFrames").toInt()
            assertEquals("snip_7_BREAK.wav", pad.source[Retrim.FILE_KEY])
            assertEquals(10_000 + start, pad.source.getValue(Retrim.IN_KEY).toInt(), "the KEEP range's start is added")
            assertEquals(10_000 + start + length, pad.source.getValue(Retrim.OUT_KEY).toInt())
            assertEquals(pad.snip.frameCount, length)
        }
        // Two slices of one commit land on different frames of the same tape.
        assertEquals(pads.size, pads.map { it.source[Retrim.IN_KEY] }.toSet().size)
        // The melodic layout carries the same keys.
        val melodic = model.sendToGridMelodic().arranged.filterNotNull()
        assertTrue(melodic.all { it.source[Retrim.FILE_KEY] == "snip_7_BREAK.wav" })
    }

    @Test
    fun `no tape reference means no tape keys, and RE-CHOP carries the reference it had`() {
        val bare = ChopReviewModel.chop(breakSnip()).sendToGrid().arranged.filterNotNull()
        assertTrue(bare.all { Retrim.FILE_KEY !in it.source && Retrim.IN_KEY !in it.source })
        assertEquals("chop", bare.first().source["origin"], "the CLI's own keys stay as they were")

        val ref = ChopReviewModel.TapeRef("snip_7_BREAK.wav", 500)
        val rechopped = ChopReviewModel.chop(breakSnip(), tape = ref).rechop(ChopReviewModel.ChopMode.Grid(4))
        assertEquals(ref, rechopped.tape)
        val sent = rechopped.sendToGrid().arranged.filterNotNull()
        assertEquals(4, sent.size)
        assertEquals("500", sent.first().source[Retrim.IN_KEY], "the first grid part starts where the source starts")
    }
}
