package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * September UAT, finding 16: the shelf sorts by most-recently-edited and
 * never said when, so its own order had no visible reason.
 */
class AgesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_726_000_000_000L

    @Test
    fun `the first two days get words, not numbers`() {
        assertEquals("TODAY", Ages.ago(now, now))
        assertEquals("TODAY", Ages.ago(now - day + 1, now), "just under a day is still today")
        assertEquals("YESTERDAY", Ages.ago(now - day, now))
        assertEquals("YESTERDAY", Ages.ago(now - 2 * day + 1, now))
    }

    @Test
    fun `days up to a fortnight, then weeks`() {
        assertEquals("2 D AGO", Ages.ago(now - 2 * day, now))
        assertEquals("13 D AGO", Ages.ago(now - 13 * day, now))
        // 14 days read as "14 D AGO" is a number nobody parses at a glance.
        assertEquals("2 W AGO", Ages.ago(now - 14 * day, now))
        assertEquals("7 W AGO", Ages.ago(now - 52 * day, now))
    }

    /**
     * A clock that moved, or a kit folder copied from a machine running
     * ahead of this one. A negative day count would render "-3 D AGO",
     * which reads as a bug rather than as a date.
     */
    @Test
    fun `a timestamp from the future reads TODAY, never a negative count`() {
        assertEquals("TODAY", Ages.ago(now + day, now))
        assertEquals("TODAY", Ages.ago(now + 400 * day, now))
    }

    /**
     * `File.lastModified()` answers 0L for a file it cannot read, and 1970 is
     * a perfectly good instant - so a kit whose `kit.json` went missing would
     * otherwise wear a confident "2853 W AGO". A row that says nothing is
     * right; a row that says something false is not.
     */
    @Test
    fun `an absent timestamp has no age, rather than an age from 1970`() {
        assertNull(Ages.agoOrNull(0L, now), "the lastModified() sentinel")
        assertNull(Ages.agoOrNull(-1L, now))
        assertEquals("TODAY", Ages.agoOrNull(now, now), "a real timestamp still answers")
        assertEquals(Ages.ago(now - 3 * day, now), Ages.agoOrNull(now - 3 * day, now))
    }

    @Test
    fun `every reading shouts and is short enough for a row`() {
        val samples = listOf(0L, day, 3 * day, 13 * day, 14 * day, 300 * day)
        for (age in samples) {
            val s = Ages.ago(now - age, now)
            assertEquals(s.uppercase(java.util.Locale.ROOT), s, "TapeOS shouts: $s")
            assertTrue(s.length <= 12, "it sits beside the pad count on a kit row: $s")
            assertTrue(!s.endsWith("."), "furniture, not a sentence: $s")
        }
    }

    @Test
    fun `the age only ever moves one way as a thing gets older`() {
        // Not a formatting claim - a monotonicity one. A shelf whose ages
        // jump around as days pass would be worse than no ages at all.
        var previous = -1
        for (d in 0..400) {
            val rank = when (val s = Ages.ago(now - d * day, now)) {
                "TODAY" -> 0
                "YESTERDAY" -> 1
                else -> if (s.endsWith("D AGO")) 2 + s.substringBefore(" ").toInt() else 1000 + s.substringBefore(" ").toInt()
            }
            assertTrue(rank >= previous, "day $d went backwards: ${Ages.ago(now - d * day, now)}")
            previous = rank
        }
    }
}
