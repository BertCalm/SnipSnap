package com.snipsnap.shell

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The door to the WORKSHOP (`docs/WORKSHOP.md`): the knock, and what it says. */
class WorkshopTest {

    @Test
    fun `seven taps inside the window open it, counting down`() {
        val knock = Workshop.Knock()
        val seen = (1..Workshop.KNOCKS).map { i -> knock.tap(i * 100L) }
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), seen, "each tap says how many are left; the last opens")
    }

    @Test
    fun `a pause longer than the window starts the knock over`() {
        val knock = Workshop.Knock()
        knock.tap(0L)
        knock.tap(500L)
        assertEquals(4, knock.tap(1_000L), "three taps in, four to go")
        // A gap of exactly the window still counts; one past it does not.
        assertEquals(3, knock.tap(1_000L + Workshop.KNOCK_WINDOW_MS))
        assertEquals(Workshop.KNOCKS - 1, knock.tap(1_000L + Workshop.KNOCK_WINDOW_MS + Workshop.KNOCK_WINDOW_MS + 1), "back to one tap")
    }

    @Test
    fun `the very first tap is never inside the window of a tap that never happened`() {
        // lastMs starts at 0; a first tap at time 0 must read as tap one,
        // not as tap two of a knock that began at the epoch.
        val knock = Workshop.Knock()
        assertEquals(Workshop.KNOCKS - 1, knock.tap(0L))
    }

    @Test
    fun `once it opens, the next tap starts a fresh knock`() {
        val knock = Workshop.Knock(knocks = 2)
        assertEquals(1, knock.tap(0L))
        assertEquals(0, knock.tap(10L), "opened")
        assertEquals(1, knock.tap(20L), "and the count began again rather than opening on every tap")
    }

    @Test
    fun `the knock speaks only over its last three taps`() {
        // Six, five, four to go: silent. Three, two, one: the countdown.
        // Zero is the opening itself, which has its own line.
        for (remaining in Workshop.KNOCKS - 1 downTo Workshop.HINT_FROM + 1) assertFalse(Workshop.hints(remaining), "$remaining to go stays quiet")
        for (remaining in Workshop.HINT_FROM downTo 1) assertTrue(Workshop.hints(remaining), "$remaining to go says so")
        assertFalse(Workshop.hints(0), "the opening itself is not a countdown of zero")
    }

    @Test
    fun `the knock refuses a knock of no taps`() {
        assertTrue(runCatching { Workshop.Knock(knocks = 0) }.isFailure)
        assertTrue(runCatching { Workshop.Knock(windowMs = -1) }.isFailure)
    }
}
