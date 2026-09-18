package com.snipsnap.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bar counts RECORD can be set to from scratch.
 *
 * J17 in `docs/UX_JOURNEY_PLAN_2026_09.md`: `recordBars` was a `var`
 * initialised to 2 that no control ever reassigned — a mutable variable
 * that could only hold its initial value. The model has always supported
 * any bar count; the seam was cut and nothing was attached to it.
 *
 * The rule that makes this worth testing rather than eyeballing: [Take]
 * refuses `bars` outside 1..64 **at the arm**, deliberately, so that "a
 * take that cannot become a clip is refused before it records". A stepper
 * that could walk outside that range would turn a UI tap into a throw at
 * the worst possible moment — the instant the count-in starts.
 */
class RecordBarsTest {

    @Test
    fun `the ladder is ascending, distinct, and starts at one bar`() {
        assertEquals(LiveRecord.BARS.sorted(), LiveRecord.BARS)
        assertEquals(LiveRecord.BARS.distinct(), LiveRecord.BARS)
        assertEquals(1, LiveRecord.BARS.first())
    }

    @Test
    fun `the default RECORD length is on the ladder`() {
        assertTrue(LiveRecord.DEFAULT_BARS in LiveRecord.BARS, "the screen opens on a rung the stepper cannot return to")
    }

    /**
     * The whole point. Every value reachable by stepping has to be one
     * [Take] will accept — the alternative is a stepper that arms a take
     * and throws.
     */
    @Test
    fun `no amount of stepping reaches a length RECORD would refuse`() {
        var bars = LiveRecord.DEFAULT_BARS
        for (step in listOf(1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, 1, -1)) {
            bars = LiveRecord.barsAfter(bars, step)
            assertTrue(bars in LiveRecord.BARS, "stepped off the ladder to $bars")
            // Constructing it is the assertion: Take's own init throws on
            // a bar count it cannot turn into a clip.
            LiveRecord.Take(bars)
        }
    }

    @Test
    fun `stepping up and down again returns to where it started`() {
        for (bars in LiveRecord.BARS) {
            assertEquals(bars, LiveRecord.barsAfter(LiveRecord.barsAfter(bars, 1), -1), "up-then-down moved $bars")
        }
    }

    /**
     * Wrapping, not clamping, and for a reason: the ladder is four rungs
     * on a phone, so a thumb reaching the end and finding the button dead
     * has to work out which way to go back. Wrapping keeps one control
     * able to reach every value.
     */
    @Test
    fun `the ends wrap rather than dead-ending`() {
        assertEquals(LiveRecord.BARS.first(), LiveRecord.barsAfter(LiveRecord.BARS.last(), 1))
        assertEquals(LiveRecord.BARS.last(), LiveRecord.barsAfter(LiveRecord.BARS.first(), -1))
    }

    /**
     * A value that is not on the ladder at all — a clip loaded from
     * somewhere else, a stale saved setting — must not strand the stepper.
     * It lands on the nearest rung rather than answering something absurd.
     */
    @Test
    fun `a length that is not on the ladder steps onto it rather than away`() {
        for (odd in listOf(3, 5, 7, 12, 64)) {
            assertTrue(LiveRecord.barsAfter(odd, 1) in LiveRecord.BARS, "stepping up from $odd left the ladder")
            assertTrue(LiveRecord.barsAfter(odd, -1) in LiveRecord.BARS, "stepping down from $odd left the ladder")
        }
    }

    @Test
    fun `every rung is a length the model can actually hold`() {
        for (bars in LiveRecord.BARS) {
            assertTrue(bars in 1..64, "$bars is outside Take's own bounds")
            LiveRecord.Take(bars)
        }
    }
}
