package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One comparison in progress: which pad is under audition, what is being
 * compared, and what the engine should actually play on any given bar.
 *
 * The part worth testing is the *substitution*. `PadHit.Hit` carries a
 * window (`startFrame`/`endFrameExclusive`) measured against the pad's own
 * sample, so swapping the file without swapping the window either truncates
 * a longer candidate or asks the engine to read past the end of a shorter
 * one — and `Hit`'s own `init` throws on a window that inverts. None of
 * that is visible in `:app`, where there is nothing to test it with.
 */
class AuditionSessionTest {

    private fun session(vararg loudness: Float) = Audition.Session(
        slot = 5,
        candidates = loudness.mapIndexed { i, l ->
            Audition.Candidate(name = "TAKE ${i + 1}", sampleFile = "take${i + 1}.wav", loudness = l)
        },
    )

    /** A pad hit as the engine would resolve it: a window into the pad's own sample, panned and levelled. */
    private fun padHit(frames: Long = 1_000L) = PadHit.Hit(
        sampleFile = "pad.wav",
        startFrame = 0,
        endFrameExclusive = frames,
        gainLeft = 0.8f,
        gainRight = 0.4f,
        pitchRatio = 1.5,
    )

    // ---- who is under audition ----

    @Test
    fun `a slot with no audition on it resolves to nothing`() {
        val s = session(0.5f, 0.5f)
        assertNull(s.liveFor(slot = 6, posSteps = 0f, stepsPerBar = 16))
    }

    @Test
    fun `the audition slot resolves to a candidate`() {
        val s = session(0.5f, 0.5f)
        assertNotNull(s.liveFor(slot = 5, posSteps = 0f, stepsPerBar = 16))
    }

    @Test
    fun `the candidate changes on the bar line and not within a bar`() {
        val s = session(0.5f, 0.5f)
        val first = s.liveFor(5, posSteps = 0f, stepsPerBar = 16)!!
        val stillFirst = s.liveFor(5, posSteps = 15.9f, stepsPerBar = 16)!!
        val second = s.liveFor(5, posSteps = 16f, stepsPerBar = 16)!!
        assertEquals(first.candidate.sampleFile, stillFirst.candidate.sampleFile)
        assertEquals("take1.wav", first.candidate.sampleFile)
        assertEquals("take2.wav", second.candidate.sampleFile)
    }

    // ---- the substitution, which is where the sharp edges are ----

    /**
     * The window has to become the candidate's own.
     *
     * The pad's window was measured against the pad's sample. A candidate
     * that is longer would be cut off at the old end frame; one that is
     * shorter would have the engine reading past its end. Neither is
     * something a listener could attribute to the right cause — it would
     * just sound like the candidate was worse.
     */
    @Test
    fun `the swapped hit plays the candidate's whole length, not the pad's window`() {
        val s = session(0.5f, 0.5f)
        val live = s.liveFor(5, 0f, 16)!!
        val swapped = live.swapInto(padHit(frames = 1_000L), candidateFrames = 44_100L)
        assertEquals("take1.wav", swapped!!.sampleFile)
        assertEquals(0L, swapped.startFrame)
        assertEquals(44_100L, swapped.endFrameExclusive)
    }

    /**
     * The pad's own level, pan and velocity survive; the match gain
     * multiplies into them.
     *
     * A candidate is auditioning for a *role* in the kit, so it should be
     * heard in that role — same place in the stereo field, same level
     * relative to everything else. Only the loudness match is new.
     */
    @Test
    fun `the pad's level and pan survive, scaled by the match gain`() {
        // take2 is four times louder, so it is pulled down to take1.
        val s = session(0.25f, 1.0f)
        val second = s.liveFor(5, posSteps = 16f, stepsPerBar = 16)!!
        val swapped = second.swapInto(padHit(), candidateFrames = 2_000L)!!
        assertEquals(0.25f, second.gain, 1e-6f)
        assertEquals(0.8f * 0.25f, swapped.gainLeft, 1e-6f)
        assertEquals(0.4f * 0.25f, swapped.gainRight, 1e-6f)
    }

    /**
     * The pad's tuning is part of the role too. A candidate stepping into a
     * pad that plays at 1.5x should play at 1.5x — otherwise the comparison
     * is between two different pitches, which is not the question being
     * asked.
     */
    @Test
    fun `the pad's tuning carries over to the candidate`() {
        val s = session(0.5f, 0.5f)
        val swapped = s.liveFor(5, 0f, 16)!!.swapInto(padHit(), candidateFrames = 2_000L)!!
        assertEquals(1.5, swapped.pitchRatio, 1e-9)
    }

    /**
     * A candidate whose audio never loaded has no frames, and `Hit` refuses
     * a window that does not advance. Answering null lets the caller fall
     * back to the pad's own sound rather than throwing inside an audio
     * callback, which is not a place to find out.
     */
    @Test
    fun `a candidate with no frames is refused rather than throwing`() {
        val s = session(0.5f, 0.5f)
        val live = s.liveFor(5, 0f, 16)!!
        assertNull(live.swapInto(padHit(), candidateFrames = 0L))
        assertNull(live.swapInto(padHit(), candidateFrames = -1L))
    }

    // ---- blind, and choosing ----

    @Test
    fun `a session starts blind and reveals on request`() {
        val s = session(0.5f, 0.5f)
        assertEquals("A", s.round.labelFor(0))
        assertEquals("TAKE 1", s.round.reveal().labelFor(0))
    }

    @Test
    fun `choosing names the winning file, and the others are simply not chosen`() {
        val s = session(0.5f, 0.5f)
        assertEquals("take2.wav", s.candidates[s.chose(1)].sampleFile)
        // Decision 1 in the spec: nothing happens to the losers. They stay
        // named, playable, and exactly where they were.
        assertEquals(2, s.candidates.size)
    }

    @Test
    fun `a choice outside the roster is clamped rather than crashing the screen`() {
        val s = session(0.5f, 0.5f)
        assertTrue(s.chose(99) in s.candidates.indices)
        assertTrue(s.chose(-1) in s.candidates.indices)
    }
}
