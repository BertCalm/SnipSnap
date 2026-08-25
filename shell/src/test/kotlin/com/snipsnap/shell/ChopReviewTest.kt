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
}
