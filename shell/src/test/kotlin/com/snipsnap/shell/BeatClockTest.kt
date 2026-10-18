package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The numbers GROOVE's frame loop used to carry inline, pinned where the JVM
 * suite can reach them. Every case here is one the loop got right on a
 * device and nothing but a device could say so; once the clock lives in a
 * player that outlasts the screen, that is not a place to find out.
 */
class BeatClockTest {

    private val sixteenth = GrooveEdit.STEP_PULSES

    @Test
    fun `a beat is four sixteenths, so 120 BPM is eight steps a second`() {
        assertEquals(8.0, BeatClock.stepsPerSecond(120f))
        assertEquals(4.0, BeatClock.stepsPerSecond(60f))
    }

    @Test
    fun `a stale frame gap is capped at a tenth of a second`() {
        // A backgrounded app hands the first frame back a quarter second late.
        assertEquals(100_000_000L, BeatClock.dtNanos(now = 1_250_000_000L, last = 1_000_000_000L))
        assertEquals(16_666_667L, BeatClock.dtNanos(now = 1_016_666_667L, last = 1_000_000_000L))
        val capped = BeatClock.advance(0f, BeatClock.dtNanos(1_250_000_000L, 1_000_000_000L), BeatClock.stepsPerSecond(120f))
        assertEquals(0.8f, capped, 1e-6f, "a tenth of a second at 120 BPM is 0.8 of a step, however late the frame was")
    }

    @Test
    fun `a clock that restarted behind its own last frame does not run backwards`() {
        assertEquals(0L, BeatClock.dtNanos(now = 900_000_000L, last = 1_000_000_000L))
        assertEquals(5f, BeatClock.advance(5f, 0L, BeatClock.stepsPerSecond(92f)))
    }

    @Test
    fun `a frame advances the needle by the elapsed steps, as a Float`() {
        // 60 fps at 120 BPM: 1/60 s is 2/15 of a step.
        val np = BeatClock.advance(10f, 16_666_667L, BeatClock.stepsPerSecond(120f))
        assertEquals(10f + 0.13333334f, np, 1e-6f)
    }

    @Test
    fun `the loop wraps once, at or past its length`() {
        assertFalse(BeatClock.wrapped(31.99f, 32f))
        assertTrue(BeatClock.wrapped(32f, 32f), "a needle exactly at the top has gone round")
        assertEquals(0.1f, BeatClock.wrap(32.1f, 32f), 1e-5f)
        assertEquals(31.99f, BeatClock.wrap(31.99f, 32f))
    }

    @Test
    fun `a note after the needle and up to it is crossed, one at the needle is not`() {
        assertTrue(BeatClock.crossed(p = 4f, lastPos = 3.9f, np = 4.1f, totalSteps = 32f))
        assertTrue(BeatClock.crossed(p = 4f, lastPos = 3.5f, np = 4f, totalSteps = 32f), "up to and including where the needle is")
        assertFalse(BeatClock.crossed(p = 4f, lastPos = 4f, np = 4.2f, totalSteps = 32f), "a note at the old position has already sounded")
        assertFalse(BeatClock.crossed(p = 8f, lastPos = 3.9f, np = 4.1f, totalSteps = 32f))
    }

    @Test
    fun `the wrap clause sounds step zero on the pass after the first`() {
        // 31.9 to 32.1 over a 32-step loop: the needle went round.
        assertTrue(BeatClock.crossed(p = 0f, lastPos = 31.9f, np = 32.1f, totalSteps = 32f), "step 0 is passed through the second clause")
        assertTrue(BeatClock.crossed(p = 31.95f, lastPos = 31.9f, np = 32.1f, totalSteps = 32f), "the last step is passed through the first")
        assertFalse(BeatClock.crossed(p = 0.5f, lastPos = 31.9f, np = 32.1f, totalSteps = 32f), "step 0.5 is on the far side of the wrapped needle")
        // And the tick after the wrap does not sound it a second time.
        val wrapped = BeatClock.wrap(32.1f, 32f)
        assertFalse(BeatClock.crossed(p = 0f, lastPos = wrapped, np = wrapped + 0.13f, totalSteps = 32f))
    }

    @Test
    fun `a needle restarted at zero never crosses step zero, which is why a take anchors with its own click`() {
        assertFalse(BeatClock.crossed(p = 0f, lastPos = 0f, np = 0.13f, totalSteps = 32f))
        assertFalse(BeatClock.crossed(p = 0f, lastPos = 0f, np = 31.9f, totalSteps = 32f))
    }

    @Test
    fun `forEachNoteCrossed walks a clip's notes in its own order`() {
        val clip = Mpc3Clip(
            "T",
            bars = 2,
            notes = listOf(
                Mpc3Note(note = 36, timePulses = 4 * sixteenth, velocity = 1f),
                Mpc3Note(note = 38, timePulses = 0L, velocity = 1f),
                Mpc3Note(note = 42, timePulses = 4 * sixteenth, velocity = 1f),
            ),
        )
        val total = GrooveEdit.stepsInClip(clip).toFloat()
        val mid = mutableListOf<Int>()
        BeatClock.forEachNoteCrossed(clip, 3.9f, 4.1f, total) { mid += it.note }
        assertEquals(listOf(36, 42), mid)
        val wrap = mutableListOf<Int>()
        BeatClock.forEachNoteCrossed(clip, total - 0.1f, total + 0.1f, total) { wrap += it.note }
        assertEquals(listOf(38), wrap, "the note at step 0 comes round through the wrap clause")
    }

    @Test
    fun `beats are every fourth step and a bar's downbeat is the accent`() {
        val seen = mutableListOf<Boolean>()
        BeatClock.forEachBeatCrossed(32f, 3.9f, 4.1f, 16) { seen += it }
        assertEquals(listOf(false), seen)
        seen.clear()
        BeatClock.forEachBeatCrossed(32f, 15.5f, 16.5f, 16) { seen += it }
        assertEquals(listOf(true), seen, "step 16 is bar two's downbeat")
        seen.clear()
        BeatClock.forEachBeatCrossed(32f, 31.9f, 32.1f, 16) { seen += it }
        assertEquals(listOf(true), seen, "the wrap passes step 0 through the second clause, and it is a downbeat")
    }

    @Test
    fun `a three-four bar is twelve steps and its downbeat is every third beat`() {
        val accents = mutableMapOf<Int, Boolean>()
        for (beat in listOf(4, 8, 12, 16, 20)) {
            BeatClock.forEachBeatCrossed(24f, beat - 0.1f, beat + 0.1f, 12) { accents[beat] = it }
        }
        assertEquals(mapOf(4 to false, 8 to false, 12 to true, 16 to false, 20 to false), accents)
        val wrap = mutableListOf<Boolean>()
        BeatClock.forEachBeatCrossed(24f, 23.9f, 24.1f, 12) { wrap += it }
        assertEquals(listOf(true), wrap, "the wrap passes step 0, a downbeat in any meter")
    }

    @Test
    fun `the bar counts from one`() {
        assertEquals(1, BeatClock.bar(0f, 16))
        assertEquals(1, BeatClock.bar(15.99f, 16))
        assertEquals(2, BeatClock.bar(16f, 16))
        assertEquals(3, BeatClock.bar(24f, 12))
    }

    @Test
    fun `a touch is placed from the last tick, capped a tenth of a second either way`() {
        val sps = BeatClock.stepsPerSecond(120f)
        val anchor = 1_000_000_000L
        assertEquals(10.4, BeatClock.posAtHit(10f, anchor, anchor + 50_000_000L, sps), 1e-9)
        assertEquals(10.8, BeatClock.posAtHit(10f, anchor, anchor + 500_000_000L, sps), 1e-9, "a hit long after the last tick is capped at a tenth of a second")
        assertEquals(9.2, BeatClock.posAtHit(10f, anchor, anchor - 500_000_000L, sps), 1e-9, "and so is one stamped long before it")
        assertEquals(9.9, BeatClock.posAtHit(10f, anchor, anchor - 12_500_000L, sps), 1e-9, "a hit a frame early keeps its small negative offset")
    }
}
