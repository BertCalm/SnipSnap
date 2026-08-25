package com.snipsnap.app.ui

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Personality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToastsTest {

    /** The regression this function exists to prevent. */
    @Test
    fun `failure speaks even when personality is off`() {
        for (level in Personality.entries) {
            assertEquals(
                Copy.CREATE_FAILED,
                newTapeToast(created = false, name = "NIGHT BUS", personality = level),
                "failure was silenced at $level",
            )
        }
    }

    @Test
    fun `success is celebrated at MILD and FULL`() {
        for (level in listOf(Personality.MILD, Personality.FULL)) {
            assertEquals(
                Copy.FRESH_TAPE,
                newTapeToast(created = true, name = "NIGHT BUS", personality = level),
            )
        }
    }

    @Test
    fun `success is silent at OFF`() {
        assertNull(newTapeToast(created = true, name = "NIGHT BUS", personality = Personality.OFF))
    }

    @Test
    fun `the TEST egg replaces the celebration, and is still a joke`() {
        assertEquals(
            "VERY CREATIVE.",
            newTapeToast(created = true, name = "TEST", personality = Personality.FULL),
        )
        assertNull(newTapeToast(created = true, name = "TEST", personality = Personality.OFF))
    }
}
