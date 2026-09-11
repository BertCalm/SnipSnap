package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackTakesTest {

    @Test
    fun `windows tile 1 to 127 with LIVE last, the way GHOSTS always has`() {
        assertEquals(listOf(1..63, 64..127), StackTakes.windows(1))
        assertEquals(listOf(1..41, 42..84, 85..127), StackTakes.windows(2))
        assertEquals(listOf(1..31, 32..63, 64..95, 96..127), StackTakes.windows(3))
        for (soft in 1..StackTakes.MAX_SOFT) {
            val w = StackTakes.windows(soft)
            assertEquals(1, w.first().first, "velocity 0 is note-off; the softest zone starts at 1")
            assertEquals(127, w.last().last)
            for (i in 1 until w.size) assertEquals(w[i - 1].last + 1, w[i].first, "zones are contiguous")
        }
        assertFailsWith<IllegalArgumentException> { StackTakes.windows(0) }
        assertFailsWith<IllegalArgumentException> { StackTakes.windows(4) }
    }

    @Test
    fun `zone names are softest first and LIVE is never one of them`() {
        assertEquals(listOf("SOFT"), StackTakes.zoneNames(1))
        assertEquals(listOf("SOFT", "MID"), StackTakes.zoneNames(2))
        assertEquals(listOf("SOFT", "MID", "HARD"), StackTakes.zoneNames(3))
        assertFailsWith<IllegalArgumentException> { StackTakes.zoneNames(4) }
    }

    @Test
    fun `dBFS is honest about silence`() {
        assertEquals(0f, StackTakes.dbfs(1f))
        assertEquals(-6.02f, StackTakes.dbfs(0.5f), 0.01f)
        assertEquals(Float.NEGATIVE_INFINITY, StackTakes.dbfs(0f))
    }

    @Test
    fun `a soft take is flagged only when it peaks over LIVE by more than rounding`() {
        assertNull(StackTakes.overLiveDb(0.5f, 1f), "quieter than live: nothing to say")
        assertNull(StackTakes.overLiveDb(1f, 1f), "equal: nothing to say")
        assertNull(StackTakes.overLiveDb(1.05f, 1f), "+0.4 dB is rounding, not a warning")
        val over = StackTakes.overLiveDb(1f, 0.5f)
        assertNotNull(over)
        assertEquals(6.02f, over, 0.01f)
        assertNull(StackTakes.overLiveDb(0f, 0f), "two silent files have no level to compare")
        assertNull(StackTakes.overLiveDb(1f, 0f), "a silent live sample has no level to be over")
    }

    @Test
    fun `the warning line names the zone and the number`() {
        val line = Copy.stackOverLive("SOFT", 3.04f)
        assertTrue(line.startsWith("SOFT IS +3.0 DB OVER LIVE."), line)
        assertEquals("1 REAL TAKE STACKED UNDER LIVE. THIS PAD IS LAYERED NOW - CLEAR SOFT HITS TO TREAT IT AGAIN.", Copy.stacked(1))
        assertTrue(Copy.stacked(3).startsWith("3 REAL TAKES STACKED"))
    }
}
