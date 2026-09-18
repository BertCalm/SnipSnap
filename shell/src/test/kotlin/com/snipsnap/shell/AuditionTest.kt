package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AUDITION's decisions, which are the parts that can be wrong silently.
 *
 * See `docs/AUDITION_SPEC_2026_09.md`. Two candidates, played inside the
 * player's own pattern, swapping on the bar line, loudness-matched, blind
 * until a deliberate reveal.
 *
 * Everything here is pure: which candidate a bar belongs to, what gain each
 * one needs to be judged fairly, and what the player is told while blind.
 * The audio path around it lives in `:app` and cannot be tested at all
 * (there is no Kotlin test source set there), which is the reason to keep
 * as much of the thinking as possible on this side of the line.
 */
class AuditionTest {

    // ---- which candidate is sounding ----

    @Test
    fun `candidates alternate one bar each`() {
        assertEquals(0, Audition.liveAt(0))
        assertEquals(1, Audition.liveAt(1))
        assertEquals(0, Audition.liveAt(2))
        assertEquals(1, Audition.liveAt(3))
    }

    /**
     * A bar before the start is not a crash and not a negative index.
     *
     * The playback clock's position is a `Float` that other code already
     * resets, anchors and interpolates against — `startRecording` sets it to
     * 0 and counts in *before* it, and `recordHit` interpolates between
     * frames. A `%` here would hand back `-1` for the bar before the
     * downbeat and index off the front of the list. `Math.floorMod`'s
     * behaviour is the contract, not an accident.
     */
    @Test
    fun `a bar before the downbeat still names a real candidate`() {
        assertEquals(1, Audition.liveAt(-1))
        assertEquals(0, Audition.liveAt(-2))
        assertTrue(Audition.liveAt(-7) in 0 until Audition.CANDIDATES)
    }

    @Test
    fun `the same bar always gives the same candidate`() {
        // Stability within a bar is the whole point — a swap mid-bar would
        // be audible as a glitch rather than a comparison.
        repeat(20) { assertEquals(Audition.liveAt(4), Audition.liveAt(4)) }
    }

    // ---- bars out of the clock's position ----

    @Test
    fun `the bar is the floor of the position, not the rounding`() {
        assertEquals(0, Audition.barOf(0f, stepsPerBar = 16))
        assertEquals(0, Audition.barOf(15.9f, stepsPerBar = 16))
        assertEquals(1, Audition.barOf(16f, stepsPerBar = 16))
        assertEquals(2, Audition.barOf(33.2f, stepsPerBar = 16))
    }

    /**
     * Not every clip is 4/4.
     *
     * `GrooveScreen`'s own clock had this bug once: an ORBIT clip declares
     * its meter, and a two-bar 3/4 clip looped at a hardcoded 32 steps
     * "played eight of silence on the end of every pass". A bar length
     * taken as 16 would put the swap in the wrong place for exactly the
     * same reason, so the caller passes the clip's own.
     */
    @Test
    fun `a clip that is not four four still swaps on its own bar line`() {
        assertEquals(0, Audition.barOf(11f, stepsPerBar = 12))
        assertEquals(1, Audition.barOf(12f, stepsPerBar = 12))
        assertEquals(2, Audition.barOf(24f, stepsPerBar = 12))
    }

    @Test
    fun `a nonsense bar length is refused rather than dividing by zero`() {
        assertEquals(0, Audition.barOf(40f, stepsPerBar = 0))
        assertEquals(0, Audition.barOf(40f, stepsPerBar = -4))
    }

    // ---- loudness matching: the rule that keeps the test honest ----

    /**
     * The louder candidate wins a blind comparison near-universally, and
     * listeners cannot hear past it. Without this the feature does not
     * merely fail to help — it produces a confident, repeatable, wrong
     * answer. Spec rule 4.
     */
    @Test
    fun `matching equalises two candidates of different loudness`() {
        val gains = Audition.matchGains(listOf(0.5f, 0.25f))
        val matched = listOf(0.5f * gains[0], 0.25f * gains[1])
        assertEquals(matched[0], matched[1], 1e-6f)
    }

    /**
     * Matched DOWN to the quietest, never up.
     *
     * Boosting a candidate to meet a louder one can push it into clipping,
     * and a clipped candidate loses the comparison for a reason that has
     * nothing to do with whether it was the better sound — the same class of
     * confound the rule exists to remove.
     */
    @Test
    fun `no candidate is ever boosted`() {
        for (pair in listOf(listOf(0.9f, 0.1f), listOf(0.1f, 0.9f), listOf(0.4f, 0.4f))) {
            for (g in Audition.matchGains(pair)) {
                assertTrue(g <= 1f, "gain $g would boost a candidate: $pair")
                assertTrue(g > 0f, "gain $g would silence a candidate: $pair")
            }
        }
    }

    @Test
    fun `equally loud candidates are both left alone`() {
        assertEquals(listOf(1f, 1f), Audition.matchGains(listOf(0.6f, 0.6f)))
    }

    /**
     * A silent candidate must not drag the target to zero and mute the
     * other one — that would turn "this take is empty" into "both takes are
     * empty", which is a far more confusing thing to hear.
     */
    @Test
    fun `a silent candidate does not silence the one beside it`() {
        val gains = Audition.matchGains(listOf(0.5f, 0f))
        assertEquals(1f, gains[0], "the audible candidate was pulled down to silence")
        assertTrue(gains[1].isFinite(), "the silent candidate produced ${gains[1]}")
    }

    @Test
    fun `two silent candidates produce finite gains rather than NaN`() {
        for (g in Audition.matchGains(listOf(0f, 0f))) assertTrue(g.isFinite(), "got $g")
    }

    // ---- blind, then revealed ----

    @Test
    fun `while blind the candidates are neutral letters, not their names`() {
        val round = Audition.Round(listOf("DRONE", "DRONE 2"))
        assertEquals("A", round.labelFor(0))
        assertEquals("B", round.labelFor(1))
        assertNotEquals("DRONE", round.labelFor(0))
    }

    @Test
    fun `revealed, they are named`() {
        val round = Audition.Round(listOf("DRONE", "DRONE 2")).reveal()
        assertEquals("DRONE", round.labelFor(0))
        assertEquals("DRONE 2", round.labelFor(1))
    }

    /**
     * The reveal is a one-way door. Blind → labelled is possible; the
     * reverse is not, because a player cannot un-know which was which. The
     * spec turns on this: it is why the reveal is a deliberate act rather
     * than a timer that quietly spends the one uncontaminated listen.
     */
    @Test
    fun `revealing twice is still revealed`() {
        assertTrue(Audition.Round(listOf("A", "B")).reveal().reveal().revealed)
    }

    @Test
    fun `a round starts blind`() {
        assertTrue(!Audition.Round(listOf("DRONE", "DRONE 2")).revealed)
    }

    // ---- finding a comparison from PAD SHEET (spec decision 2) ----

    private fun pad(slot: Int, name: String) =
        KitPad(slot = slot, sampleFile = "${name.lowercase().replace(' ', '_')}.wav", displayName = name, drumClass = DrumClass.TONAL)

    @Test
    fun `baseNameOf strips the freshStem counting suffix, not the word itself`() {
        assertEquals("DRONE", Audition.baseNameOf("DRONE"))
        assertEquals("DRONE", Audition.baseNameOf("DRONE 2"))
        assertEquals("DRONE", Audition.baseNameOf("DRONE 3"))
        // No trailing number: nothing to strip.
        assertEquals("KICK ROOM", Audition.baseNameOf("KICK ROOM"))
    }

    @Test
    fun `siblings share a base name and never include the pad asked about`() {
        val pads = listOf(pad(1, "DRONE"), pad(2, "DRONE 2"), pad(3, "DRONE 3"), pad(4, "SNARE"))
        val siblings = Audition.siblingsOf(pads, 1)
        assertEquals(setOf(2, 3), siblings.map { it.slot }.toSet())
        assertTrue(siblings.none { it.slot == 1 })
    }

    @Test
    fun `siblingsOf is symmetric - either sibling finds the other`() {
        val pads = listOf(pad(1, "DRONE"), pad(2, "DRONE 2"))
        assertEquals(listOf(2), Audition.siblingsOf(pads, 1).map { it.slot })
        assertEquals(listOf(1), Audition.siblingsOf(pads, 2).map { it.slot })
    }

    @Test
    fun `a pad with no matching base name has nothing to audition against`() {
        val pads = listOf(pad(1, "DRONE"), pad(2, "SNARE"))
        assertTrue(Audition.siblingsOf(pads, 1).isEmpty())
    }

    @Test
    fun `an empty or unknown slot offers nothing`() {
        assertTrue(Audition.siblingsOf(emptyList(), 1).isEmpty())
        assertTrue(Audition.siblingsOf(listOf(pad(1, "DRONE")), 99).isEmpty())
    }
}
