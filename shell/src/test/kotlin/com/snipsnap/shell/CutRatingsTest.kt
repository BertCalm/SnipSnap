package com.snipsnap.shell

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WS2 of `docs/WORKSHOP.md`: CONFIRM ALL on the chop model, the bench's
 * own record of how a chop was reached, and the cut rating that carries
 * both to the desk.
 */
class CutRatingsTest {

    private val rate = 44_100

    /** Four hits half a second apart — `ChopReviewTest`'s own break, so the two files chop the same tape. */
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
    fun `CONFIRM ALL vouches for the chips left alone, and a picker agreement counts on its own`() {
        val model = ChopReviewModel.chop(breakSnip())
        assertTrue(model.rows.size >= 3, "the break chops into at least three rows: ${model.rows.size}")
        assertEquals(emptyList(), model.labeledConfirmations(), "nothing is vouched for until a human says so")
        assertEquals(emptyList(), model.teachHarvest())
        assertEquals(model.rows.size, model.confirmable())
        assertFalse(model.confirmed)

        // The picker's own agreement: choosing what the machine chose.
        model.setLabel(0, model.rows[0].classification.drumClass)
        val agreed = model.labeledConfirmations()
        assertEquals(1, agreed.size, "an agreement is the human's word, logged without CONFIRM ALL")
        assertTrue(agreed[0].confirmation)
        assertEquals(model.rows[0].classification.drumClass, agreed[0].label)
        assertEquals(model.rows.size - 1, model.confirmable(), "a chip already agreed with is not left alone")

        // A correction beside it.
        model.cycleLabel(1)
        assertEquals(1, model.labeledOverrides().size)
        assertEquals(2, model.teachHarvest().size, "one correction, one confirmation")
        assertFalse(model.teachHarvest()[0].confirmation, "corrections first")

        // CONFIRM ALL: every chip left alone, and nothing that was corrected.
        val vouched = model.confirmAll()
        assertEquals(model.rows.size - 2, vouched, "the two chips the human already touched are not left alone")
        assertTrue(model.confirmed)
        val confirmations = model.labeledConfirmations()
        assertEquals(model.rows.size - 1, confirmations.size, "everything but the corrected chip")
        assertTrue(confirmations.all { it.confirmation && it.label == it.machineSaid })
        assertEquals(model.rows.size, model.teachHarvest().size, "the whole chop is now the human's word")
        assertEquals(1, model.teachHarvest().count { !it.confirmation })

        // A re-chop is new slices, new opinions: the confirmation does not follow.
        val fresh = model.rechopKeeping(model.mode)
        assertFalse(fresh.confirmed)
        assertEquals(fresh.rows.count { it.override != null && !it.overridden }, fresh.labeledConfirmations().size, "only carried agreements survive, never the blanket confirmation")
        assertTrue(model.confirmed, "and the old model is untouched")
    }

    @Test
    fun `a ghost or a rung is never confirmed, because the classifier never named it`() {
        val ghosts = ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.Ghosts())
        assertTrue(ghosts.rows.isNotEmpty(), "the break has spaces between its hits")
        assertTrue(ghosts.rows.all { it.label != null }, "every ghost row is named by construction")
        assertEquals(0, ghosts.confirmable())
        assertEquals(0, ghosts.confirmAll())
        assertEquals(emptyList(), ghosts.labeledConfirmations(), "a LOOP given by construction is not a verdict to vouch for")
    }

    @Test
    fun `tries and hand edits are counted, and a re-chop starts the hand count over`() {
        val m0 = ChopReviewModel.chop(breakSnip())
        assertEquals(listOf(0, 0, 0), listOf(m0.tries, m0.merges, m0.splits))

        val m1 = m0.rechop()
        val m2 = m1.rechopKeeping(m1.mode)
        assertEquals(1, m1.tries)
        assertEquals(2, m2.tries, "every chop after the first is a try, carried or not")

        val m3 = assertNotNull(m2.merged(0), "MERGE of the first two slices")
        assertEquals(listOf(2, 1, 0), listOf(m3.tries, m3.merges, m3.splits))
        assertTrue(m3.edited)
        val m4 = assertNotNull(m3.split(0), "SPLIT the joined slice at its inner hit")
        assertEquals(listOf(2, 1, 1), listOf(m4.tries, m4.merges, m4.splits))

        val m5 = m4.rechop()
        assertEquals(listOf(3, 0, 0), listOf(m5.tries, m5.merges, m5.splits), "the detector's cuts again: nothing moved by hand yet")
    }

    @Test
    fun `the rating records the bench's settings and what it took`() {
        val hits = ChopReviewModel.ChopMode.ByHits(maxSlices = 8, ear = ChopReviewModel.Ear.FINE, cut = ChopReviewModel.Cut.EARLY)
        val model = ChopReviewModel.chop(breakSnip(), hits)
        model.cycleLabel(0)
        model.confirmAll()

        val r = CutRatings.of(model, 4)
        assertEquals(4, r.stars)
        assertEquals("HITS", r.mode)
        assertEquals(8, r.count)
        assertEquals("FINE", r.ear)
        assertEquals("EARLY", r.cut)
        assertEquals("OFF", r.grid)
        assertEquals(model.sliceCount, r.rows)
        assertEquals(0, r.tries)
        assertEquals(0, r.handEdits)
        assertEquals(1, r.corrected)
        assertEquals(model.sliceCount - 1, r.confirmed)
        assertEquals(3f, r.seconds, 0.001f, "six half-second steps")
        assertEquals("HITS ×8 · FINE · CUT EARLY · SNAP OFF", r.setting)

        val grid = CutRatings.of(ChopReviewModel.chop(breakSnip(), ChopReviewModel.ChopMode.Grid(4)), 2)
        assertEquals("GRID", grid.mode)
        assertEquals(4, grid.count)
        assertEquals(null, grid.ear)
        assertEquals("GRID ×4", grid.setting)

        assertTrue(runCatching { CutRatings.of(model, 0) }.isFailure, "a rating is 1..${CutRatings.STARS}")
        assertTrue(runCatching { CutRatings.of(model, CutRatings.STARS + 1) }.isFailure)
    }

    private val rated = CutRatings.Rating(
        stars = 4, mode = "HITS", count = 16, ear = "NORMAL", cut = "ON", grid = "OFF",
        rows = 12, tries = 1, merges = 0, splits = 1, corrected = 1, confirmed = 11, seconds = 8.2f, bpm = 92.3f,
    )
    private val gridded = rated.copy(stars = 2, mode = "GRID", count = 8, ear = null, cut = null, grid = null, bpm = null)

    @Test
    fun `ratings round-trip jsonl, and a torn or impossible line is dropped`() {
        val jsonl = CutRatings.toJsonl(listOf(rated, gridded))
        assertEquals(2, jsonl.trim().lines().size, "one line per rating")
        assertEquals(listOf(rated, gridded), CutRatings.fromJsonl(jsonl), "every field survives, nulls included")

        val torn = jsonl + "{\"stars\":3,\"mo"
        assertEquals(2, CutRatings.fromJsonl(torn).size, "a killed append loses only its own line")
        // The writer's own spelling of the number is not this test's
        // business, so the rewrite matches whatever it wrote and proves it
        // changed something before relying on it.
        val line = CutRatings.toJsonl(listOf(rated))
        val offScale = line.replace(Regex("\"stars\":\\s*4(\\.0)?"), "\"stars\": 9")
        assertTrue(offScale != line, "the rewrite found the stars in: $line")
        assertEquals(2, CutRatings.fromJsonl(jsonl + offScale).size, "a rating off the scale is not a rating")
    }

    @Test
    fun `append accumulates and read of nothing is empty`() {
        val temp = java.nio.file.Files.createTempDirectory("cuts").toFile()
        try {
            val file = File(temp, CutRatings.FILE_NAME)
            assertEquals(emptyList(), CutRatings.read(file))
            CutRatings.append(file, listOf(rated))
            CutRatings.append(file, listOf(gridded))
            CutRatings.append(file, emptyList()) // no-op
            assertEquals(listOf(rated, gridded), CutRatings.read(file))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `the summary sums by setting, best first, and says when there is nothing`() {
        assertEquals("cut ratings: none logged yet\n", CutRatings.summary(emptyList()))
        val text = CutRatings.summary(listOf(rated.copy(stars = 5), rated.copy(stars = 3, tries = 3), gridded))
        val lines = text.trim().lines()
        assertEquals("cut ratings: 3 chops rated, 3.3 stars on average", lines[0])
        assertTrue(lines[1].startsWith("  4.0 stars  n=2   HITS ×16 · NORMAL · CUT ON · SNAP OFF"), lines[1])
        assertTrue("tries 2.0" in lines[1] && "hand edits 1.0" in lines[1], lines[1])
        assertTrue(lines[2].startsWith("  2.0 stars  n=1   GRID ×8"), lines[2])
    }

    /** The harness half: cut ratings in the calibration folder get summed on every test run. */
    @Test
    fun `the calibration cut ratings are summed when present`() {
        val file = File("../reference/calibration/${CutRatings.FILE_NAME}")
        val ratings = CutRatings.read(file)
        if (ratings.isEmpty()) {
            println("no ${CutRatings.FILE_NAME} in reference/calibration/ - nothing rated yet")
            return
        }
        print(CutRatings.summary(ratings))
        assertTrue(ratings.isNotEmpty())
    }
}
