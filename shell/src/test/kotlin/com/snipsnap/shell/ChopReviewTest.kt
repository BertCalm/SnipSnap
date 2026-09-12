package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import java.io.File
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
        assertEquals(null, ChopReviewModel.chop(Snip(FloatArray(rate * 2), 1, rate), byHits(8)).autoCount(), "no hits: no count, not one")
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
        // One to one: two fresh slices within the tolerance of one corrected
        // chip (a grid of 500-frame parts under a one-part grid) inherit it
        // once, on the nearest, never both.
        val short = Snip(DrumSynth.kick().samples.copyOf(16_000), 1, rate)
        val one = ChopReviewModel.chop(short, ChopReviewModel.ChopMode.Grid(1))
        one.setLabel(0, DrumClass.TOM)
        val many = one.rechopKeeping(ChopReviewModel.ChopMode.Grid(32))
        assertTrue(many.rows[1].slice.sourceFrame <= ChopReviewModel.CARRY_TOLERANCE_FRAMES, "the second part is within the tolerance too: ${many.rows[1].slice.sourceFrame}")
        assertEquals(listOf(0), many.rows.withIndex().filter { it.value.override == DrumClass.TOM }.map { it.index }, "the chip lands once, on the nearest")
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

    /**
     * A break at 120 BPM (a hit every half second, 22050 frames), eight
     * bars of it so the tempo is unmistakable, with [nudges] moving named
     * hits off the pulse by that many frames (negative = early).
     */
    private fun pulsed(nudges: Map<Int, Int> = emptyMap(), extra: List<Int> = emptyList()): Snip {
        val step = rate / 2
        val total = FloatArray(step * 10)
        val kit = listOf(DrumSynth.kick(), DrumSynth.closedHat(), DrumSynth.snare(), DrumSynth.closedHat())
        fun put(at: Int, hit: Snip, gain: Float) { for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * gain }
        for (s in 1..8) put(s * step + (nudges[s] ?: 0), kit[(s - 1) % 4], 0.8f)
        for (at in extra) put(at, DrumSynth.snare(), 0.5f)
        return Snip(total, 1, rate)
    }

    @Test
    fun `ON THE GRID snaps late cuts to the pulse, never cuts after an attack, and folds two hits on one line`() {
        val nudges = mapOf(2 to 441, 3 to -441, 5 to 882)
        val off = ChopReviewModel.chop(pulsed(nudges), byHits(16))
        val tempo = assertNotNull(off.tempo, "eight bars at 120 have a tempo")
        assertTrue(Math.abs(tempo.bpm - 120f) < 3f, "heard ~120: ${tempo.bpm}")
        val on = ChopReviewModel.chop(pulsed(nudges), byHits(16, cut = ChopReviewModel.Cut.ON).copy(grid = ChopReviewModel.GridSnap.SIXTEENTH))
        assertEquals(off.sliceCount, on.sliceCount, "no hit lost to the grid")
        assertEquals("BY HITS · ON THE 16TH", on.modeLabel())
        // The grid's spacing is fitted to the hits, not read off the estimate: the true 16th here is 5512.5 frames.
        val step = assertNotNull(on.gridStep)
        assertTrue(Math.abs(step - rate / 8.0) < 40, "the grid fits the hits' own pulse: $step")
        assertEquals(null, off.gridStep)
        val anchor = on.cutFrames().first()
        var moved = 0
        for (i in on.rows.indices) {
            val was = off.cutFrames()[i]
            val now = on.cutFrames()[i]
            assertTrue(now <= was, "a cut never lands after the one the detector made: slice ${i + 1} $now vs $was")
            val phase = ((now - anchor) / step) % 1.0
            val onLine = Math.min(phase, 1.0 - phase) < 0.02
            assertTrue(onLine || now == was, "slice ${i + 1} is on the pulse or where the hit was: phase $phase")
            if (now != was) moved++
        }
        assertTrue(moved >= 2, "the late hits moved onto the pulse: $moved")
        // The early hit (3) keeps its own cut: the click is never shaved.
        assertEquals(off.cutFrames()[2], on.cutFrames()[2])
        // A flam — a second hit 60 ms after the sixth, its own hit to the ear (past the 30 ms gap) — on an 8th grid is one slice, the stronger's.
        val flam = ChopReviewModel.chop(pulsed(extra = listOf(6 * (rate / 2) + 2646)), byHits(16))
        assertEquals(9, flam.sliceCount, "the ear hears the flam as its own hit")
        val flamOn = ChopReviewModel.chop(pulsed(extra = listOf(6 * (rate / 2) + 2646)), byHits(16).copy(grid = ChopReviewModel.GridSnap.EIGHTH))
        assertEquals(8, flamOn.sliceCount, "the flam folds onto one line")
        // The grid rides the bench like everything else: the tempo is measured once and shared.
        val again = on.rechopKeeping(byHits(16).copy(grid = ChopReviewModel.GridSnap.OFF))
        assertEquals(off.cutFrames(), again.cutFrames())
        assertTrue(again.tempo === on.tempo, "one measurement per source")
    }

    /** Two kicks, two snares (one soft), a hat — five slices, three sounds. */
    private fun repeats(): Snip {
        val step = rate / 2
        val total = FloatArray(step * 7)
        fun put(at: Int, hit: Snip, gain: Float) { for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * gain }
        put(1 * step, DrumSynth.kick(), 0.8f)
        put(2 * step, DrumSynth.kick(), 0.7f)
        put(3 * step, DrumSynth.snare(), 0.8f)
        put(4 * step, DrumSynth.snare(), 0.5f)
        put(5 * step, DrumSynth.closedHat(), 0.8f)
        return Snip(total, 1, rate)
    }

    @Test
    fun `FOLD groups the same sound, never across classes, and lands a chain pad`() {
        val model = ChopReviewModel.chop(repeats(), byHits(8))
        assertEquals(5, model.sliceCount)
        val folds = model.folds()
        assertEquals(listOf(2, 2, 1), folds.map { it.size }, "kicks, snares, the hat")
        assertEquals(listOf(1, 3, 5), folds.map { it.lead.n }, "folds in capture order, led by their first slice")
        assertEquals(listOf("×2 TAKES", "TAKE 2 OF 2 · = 1", "×2 TAKES", "TAKE 2 OF 2 · = 3", null), model.foldTags())
        val placed = model.foldedPreview()
        assertEquals(3, placed.count { it != null })
        assertEquals(DrumClass.KICK, placed[0]?.lead?.effectiveClass)
        assertEquals(DrumClass.SNARE, placed[1]?.lead?.effectiveClass)
        // A relabelled chip is a different sound: the second kick as a TOM folds with nothing.
        model.setLabel(1, DrumClass.TOM)
        assertEquals(4, model.folds().size, "never across classes")
        model.clearOverride(1)
        // Landing: one chain pad per fold of many, the lead first, the takes cycling under it.
        val send = model.sendToGridFolded()
        assertEquals(3, send.sliceCount, "pads, not slices")
        val kickPad = assertNotNull(send.arranged[0])
        assertEquals(1, kickPad.takes.size)
        assertEquals("2", kickPad.source["folded"])
        assertEquals(model.rows[0].slice.sourceFrame.toString(), kickPad.source["sourceFrame"], "the lead's provenance")
        val dir = java.nio.file.Files.createTempDirectory("fold").toFile()
        try {
            val kit = KitBuilderModel.fromChop("Folded", send.arranged, dir).kit
            val kick = assertNotNull(kit.pad(1))
            val chain = assertNotNull(kick.chain, "a folded pad is a chain")
            assertEquals(2, chain.sliceCount)
            assertEquals(listOf(0L, model.rows[0].slice.snip.frameCount.toLong()), chain.boundaries)
            assertEquals(2, chain.cycle)
            val wav = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile))
            assertEquals(model.rows[0].slice.snip.frameCount + model.rows[1].slice.snip.frameCount, wav.frameCount, "both takes end to end")
            assertEquals(null, assertNotNull(kit.pad(3)).chain, "a fold of one is a plain pad")
            // ONTO an existing kit's bank carries the takes too: the same chain, through `assign`.
            val onto = KitBuilderModel.create("Onto", File(dir, "onto"))
            val landed = onto.landArranged(send.arranged, 1)
            assertEquals(3, landed.size)
            val ontoKick = assertNotNull(onto.pad(landed[0]))
            assertEquals(chain.boundaries, assertNotNull(ontoKick.chain, "ONTO lands the chain").boundaries)
            assertEquals(wav.frameCount, com.snipsnap.audio.WavReader.read(File(File(dir, "onto"), ontoKick.sampleFile)).frameCount)
            assertEquals(null, assertNotNull(onto.pad(landed[2])).chain)
        } finally {
            dir.deleteRecursively()
        }
        assertEquals("5 SLICES FOLDED ONTO 3 PADS. CHOKE GROUP SET.", Copy.foldedToGrid(5, 3, true))
        assertEquals("1 SLICE FOLDED ONTO 1 PAD.", Copy.foldedToGrid(1, 1, false))
        assertEquals("'K' BANK B: 5 SLICES FOLDED ONTO 3 PADS.", Copy.foldedOnto("K", 'B', 5, 3, 0))
    }

    /** Eight hits 100 ms apart, each an 80 ms burst, the tape ending on the last: the ear hears every hit, and there is nothing between them. */
    private fun gated(): Snip {
        val step = rate / 10
        val total = FloatArray(step * 9)
        val burst = (0.08f * rate).toInt()
        for (h in 1..8) {
            val at = h * step
            for (i in 0 until burst) if (at + i < total.size) total[at + i] += 0.8f * Math.sin(2.0 * Math.PI * 180.0 * i / rate).toFloat() * Math.exp(-i / (0.04 * rate)).toFloat()
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `GHOSTS are the spaces between the hits, named for the hit before, landed as gate pads, and a gated break has none`() {
        val hits = ChopReviewModel.chop(breakSnip(), byHits(8))
        val ghosts = ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.Ghosts(byHits(8)))
        assertTrue(ghosts.ghosts)
        assertEquals(4, ghosts.sliceCount, "a space after every hit")
        assertEquals(4, ghosts.hitsHeard, "and the bench steps from the hits underneath")
        assertEquals(4, hits.hitsHeard)
        assertEquals("GHOSTS", ghosts.modeLabel())
        assertEquals(listOf("AFTER KICK 1", "AFTER HAT CL 2", "AFTER SNARE 3", "AFTER HAT OP 4"), ghosts.rows.map { it.ghostOf })
        assertTrue(ghosts.rows.all { it.effectiveClass == DrumClass.LOOP && !it.unsure }, "a ghost is texture, never NOT SURE")
        for (i in 0 until 4) {
            val hit = hits.rows[i].slice
            val ghost = ghosts.rows[i].slice
            assertTrue(ghost.sourceFrame > hit.sourceFrame, "the ghost starts after its hit's attack: ${ghost.sourceFrame} vs ${hit.sourceFrame}")
            val end = if (i + 1 < 4) hits.rows[i + 1].slice.sourceFrame else breakSnip().frameCount
            assertEquals(end, ghost.sourceFrame + ghost.snip.frameCount, "and runs to the next cut")
            assertTrue(ghost.snip.frameCount >= (com.snipsnap.audio.Chopper.GHOST_MIN_SEC * rate).toInt())
        }
        // The bench reaches the hits underneath: one hit fewer, one ghost fewer; the header carries the hits' extras.
        val fewer = ghosts.rechopKeeping(ChopReviewModel.withHits(ghosts.mode, byHits(3, cut = ChopReviewModel.Cut.EARLY)))
        assertEquals(3, fewer.sliceCount)
        assertEquals("GHOSTS · CUT EARLY", fewer.modeLabel())
        assertEquals(byHits(3, cut = ChopReviewModel.Cut.EARLY), ChopReviewModel.hitsOf(fewer.mode))
        assertEquals(byHits(8), ChopReviewModel.withHits(ChopReviewModel.ChopMode.Grid(4), byHits(8)), "a grid becomes the hits")
        // Landing: gate pads named for their hit, balanced, RE-TRIM keys on every one.
        val taped = ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.Ghosts(byHits(8)), ChopReviewModel.TapeRef("snip_1_X.wav", 0))
        val send = taped.sendToGrid()
        val pads = send.arranged.filterNotNull()
        assertEquals(4, pads.size)
        assertTrue(pads.all { !it.oneShot }, "hold the pad, hold the room")
        assertTrue(pads.all { it.level != null }, "through the balancer")
        assertEquals(setOf("AFTER KICK 1", "AFTER HAT CL 2", "AFTER SNARE 3", "AFTER HAT OP 4"), pads.map { it.displayName }.toSet())
        assertTrue(pads.all { Retrim.FILE_KEY in it.source && it.source["ghost"] == it.displayName })
        val dir = java.nio.file.Files.createTempDirectory("ghost").toFile()
        try {
            val kit = KitBuilderModel.fromChop("Ghosts", send.arranged, dir).kit
            assertTrue(kit.pads.all { !it.oneShot && it.displayName.startsWith("AFTER ") }, "the assembler keeps the name and the gate")
            val onto = KitBuilderModel.create("Onto", File(dir, "onto"))
            val landed = onto.landArranged(send.arranged, 1)
            assertTrue(landed.map { onto.pad(it)!! }.all { !it.oneShot && it.displayName.startsWith("AFTER ") }, "and so does ONTO")
        } finally {
            dir.deleteRecursively()
        }
        // A tight, gated break: the hits are heard, the spaces are not.
        val gatedHits = ChopReviewModel.chop(gated(), byHits(16))
        assertTrue(gatedHits.sliceCount >= 6, "the ear hears the gated hits: ${gatedHits.sliceCount}")
        assertEquals(0, ChopReviewModel.chop(gated(), ChopReviewModel.ChopMode.Ghosts(byHits(16))).sliceCount, "nothing between the hits")
        // MERGE and SPLIT keep the names: a split ghost is still the space after the same hit.
        val merged = assertNotNull(ghosts.merged(0))
        assertEquals(listOf("AFTER KICK 1", "AFTER SNARE 3", "AFTER HAT OP 4"), merged.rows.map { it.ghostOf })
        val split = merged.split(0)
        if (split != null) assertEquals("AFTER KICK 1", split.rows[1].ghostOf)
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
