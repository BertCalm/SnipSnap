package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceWatchTest {

    private val zeros = FloatArray(100)

    @Test
    fun `ticks once the silent run reaches the hold, then again a hold later`() {
        val watch = SilenceWatch(holdFrames = 250)
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.silent)
        assertTrue(watch.feed(zeros, 100), "300 silent frames crosses a 250 hold")
        assertEquals(0L, watch.silentFrames, "the run restarts after a tick")
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.feed(zeros, 100), "a second tick another hold later")
    }

    @Test
    fun `any sound resets the run, and dither counts as sound`() {
        val watch = SilenceWatch(holdFrames = 250)
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        val dither = FloatArray(100) { if (it == 57) 1e-6f else 0f }
        assertFalse(watch.feed(dither, 100))
        assertFalse(watch.silent, "one live sample ends the silent run")
        assertEquals(0L, watch.silentFrames)
        // Nowhere near a tick now: the old 200 frames don't carry over.
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.feed(zeros, 100))
    }

    @Test
    fun `only count samples are read, and NaN is not silence`() {
        val watch = SilenceWatch(holdFrames = 10)
        val tail = FloatArray(100) { if (it >= 5) 1f else 0f }
        assertFalse(watch.feed(tail, 5), "the loud tail past count is never looked at")
        assertEquals(5L, watch.silentFrames)
        val nan = floatArrayOf(0f, Float.NaN)
        assertFalse(watch.feed(nan, 2))
        assertFalse(watch.silent, "a NaN is a broken stream, not dead air")
    }

    @Test
    fun `forSeconds sizes the hold from the rate and refuses nonsense`() {
        assertEquals(88_200, SilenceWatch.forSeconds(2.0, 44_100).holdFrames)
        assertFailsWith<IllegalArgumentException> { SilenceWatch.forSeconds(0.0, 44_100) }
        assertFailsWith<IllegalArgumentException> { SilenceWatch(0) }
        assertFailsWith<IllegalArgumentException> { SilenceWatch(10, threshold = -1f) }
    }

    @Test
    fun `a negative count is refused, not subtracted from the run`() {
        val watch = SilenceWatch(holdFrames = 150)
        watch.feed(zeros, 100)
        assertFailsWith<IllegalArgumentException> { watch.feed(zeros, -1) }
        assertEquals(100L, watch.silentFrames, "the run is untouched by the refused call")
    }

    @Test
    fun `reset forgets a run in progress`() {
        val watch = SilenceWatch(holdFrames = 150)
        watch.feed(zeros, 100)
        watch.reset()
        assertFalse(watch.silent)
        assertFalse(watch.feed(zeros, 100), "after reset the hold starts over")
    }

    // ==================== MIC-mute specificity (muted mic vs. quiet room) ====================
    //
    // These pin down the exact property the MIC wiring in MicSessionService
    // leans on: a run of exact digital zeros this long ticks, but a run of
    // this same length carrying a genuine (if very quiet) noise floor never
    // does, no matter how close to zero that floor sits. A wrong answer
    // here would either miss a real mic mute or, worse, falsely tell
    // someone recording a quiet room that their mic died.

    @Test
    fun `exact zeros held the full window tick, a quiet noise floor never does`() {
        val holdFrames = 10_000
        val exactZeroWatch = SilenceWatch(holdFrames)
        val zeroBlock = FloatArray(1_000)
        var ticked = false
        repeat(10) {
            if (exactZeroWatch.feed(zeroBlock, zeroBlock.size)) ticked = true
        }
        assertTrue(ticked, "a sustained run of exact zeros must eventually cross the hold")

        for (floor in listOf(1e-6f, 1e-4f)) {
            val quietWatch = SilenceWatch(holdFrames)
            // A tiny but genuine, alternating-sign noise floor — never exact
            // zero, the way real dither/thermal noise never is.
            val quietBlock = FloatArray(1_000) { if (it % 2 == 0) floor else -floor }
            var quietTicked = false
            // Feed well past the hold (20x its frame count) — a real quiet
            // room recorded this long must never once read as muted.
            repeat(20) {
                if (quietWatch.feed(quietBlock, quietBlock.size)) quietTicked = true
            }
            assertFalse(quietTicked, "a noise floor of $floor must never be mistaken for a muted source")
        }
    }

    @Test
    fun `normal audio levels never tick regardless of how long they run`() {
        val watch = SilenceWatch(holdFrames = 5_000)
        val loud = FloatArray(1_000) { i -> if (i % 2 == 0) 0.3f else -0.3f }
        var ticked = false
        repeat(20) {
            if (watch.feed(loud, loud.size)) ticked = true
        }
        assertFalse(ticked, "ordinary program material must never read as silence")
    }

    @Test
    fun `a single live sample near the end of an otherwise-silent full-length run resets, no tick`() {
        // The specificity proof: a run just one sample short of a false
        // tick, broken at the last possible moment, must not tick at all —
        // proof the hold can't be "mostly" crossed, only crossed cleanly.
        val holdFrames = 1_000
        val watch = SilenceWatch(holdFrames)
        val almostAllSilent = FloatArray(holdFrames) { if (it == holdFrames - 1) 1e-3f else 0f }
        assertFalse(watch.feed(almostAllSilent, almostAllSilent.size))
        assertFalse(watch.silent, "the one live sample at the very end ends the run")
        assertEquals(0L, watch.silentFrames)
        // Confirm it wasn't a fluke: a genuinely unbroken run of the same
        // length now does tick.
        assertTrue(watch.feed(FloatArray(holdFrames), holdFrames))
    }

    @Test
    fun `a sample exactly at the threshold still counts as silence, only strictly above breaks it`() {
        val watch = SilenceWatch(holdFrames = 10, threshold = 1e-8f)
        val atThreshold = floatArrayOf(1e-8f, -1e-8f)
        assertFalse(watch.feed(atThreshold, 2), "not yet at the hold")
        assertEquals(2L, watch.silentFrames, "a sample exactly at the threshold must not break the run")
        val justOver = floatArrayOf(2e-8f)
        watch.feed(justOver, 1)
        assertFalse(watch.silent, "a sample strictly above the threshold must break the run")
    }

    /**
     * Pins the exact latch shape `MicSessionService`'s reader loop hand-
     * rolls around a bare [SilenceWatch] (rather than reusing
     * [BlockWatch], whose verdict and KDoc are INSIDE's "app blocks the
     * tape" concept, not MIC's "hearing nothing"): `feed()` returning true
     * sets the verdict, `feed()` returning false while [silent] is also
     * false clears it, and — the hazard [BlockWatch]'s own KDoc documents
     * for the isomorphic INSIDE case — `feed()` returning false while
     * [silent] is *also* true (the tick's own block, where the run just
     * restarted at zero) must leave the verdict alone rather than
     * clearing it one block after raising it.
     */
    @Test
    fun `the verdict latch a caller builds around feed does not drop on the tick's own block`() {
        val holdFrames = 100
        val watch = SilenceWatch(holdFrames)
        var verdict = false
        fun apply(block: FloatArray, count: Int) {
            if (watch.feed(block, count)) verdict = true else if (!watch.silent) verdict = false
        }

        val zeroBlock = FloatArray(60)
        apply(zeroBlock, 60)
        assertFalse(verdict, "not yet at the hold")
        apply(zeroBlock, 60)
        assertTrue(verdict, "120 silent frames crosses the 100 hold")

        // The block right after a tick: feed() returns false (the run just
        // restarted) but watch.silent is ALSO false for the same reason —
        // this must not be misread as "sound arrived, clear the verdict".
        assertFalse(watch.silent, "silentFrames was zeroed by the tick itself")
        apply(FloatArray(1), 1)
        assertTrue(verdict, "the verdict must survive the tick's own restart block")

        // Real sound arriving does clear it.
        val loud = floatArrayOf(0.5f)
        apply(loud, 1)
        assertFalse(verdict, "actual sound arriving clears the verdict")
    }
}
