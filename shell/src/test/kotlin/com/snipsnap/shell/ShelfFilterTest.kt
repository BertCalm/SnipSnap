package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * September UAT, finding 16: the shelf sorts but does not find. These pin
 * the tap-only half — what the cycle does, what the filter keeps, and the
 * one thing it must never do quietly, which is empty the shelf.
 */
class ShelfFilterTest {

    private data class Kit(val name: String, val status: DubStamp.Status?)

    private val shelf = listOf(
        Kit("BREAKS", DubStamp.Status.DRAFT),
        Kit("HOUSE", DubStamp.Status.ON_CARD),
        Kit("JUNGLE", DubStamp.Status.DUBBED),
        Kit("TRAP", DubStamp.Status.DRAFT),
    )

    @Test
    fun `the cycle starts at everything and comes back to it`() {
        assertNull(ShelfFilter.CYCLE.first(), "the shelf opens showing every kit")
        var at = ShelfFilter.next(null)
        val seen = mutableListOf(at)
        repeat(ShelfFilter.CYCLE.size - 1) {
            at = ShelfFilter.next(at)
            seen += at
        }
        assertNull(at, "one full turn returns to showing everything")
        assertEquals(
            DubStamp.Status.entries.toSet(),
            seen.filterNotNull().toSet(),
            "every state a row can wear must be reachable from the chip",
        )
    }

    /**
     * The cycle is built from `DubStamp.Status.entries`, so a fourth state
     * added there is reachable without anyone editing this object. If that
     * ever stops being true the chip would silently hide a whole state.
     */
    @Test
    fun `the cycle covers every dub state, without being told them`() {
        assertEquals(DubStamp.Status.entries.size + 1, ShelfFilter.CYCLE.size)
        assertEquals(ShelfFilter.CYCLE.size, ShelfFilter.CYCLE.toSet().size, "no state twice")
    }

    @Test
    fun `no filter is every kit, in the order given`() {
        assertEquals(shelf, ShelfFilter.apply(shelf, null) { it.status })
    }

    @Test
    fun `a filter keeps only its own state and does not reorder`() {
        assertEquals(
            listOf("BREAKS", "TRAP"),
            ShelfFilter.apply(shelf, DubStamp.Status.DRAFT) { it.status }.map { it.name },
        )
        assertEquals(
            listOf("HOUSE"),
            ShelfFilter.apply(shelf, DubStamp.Status.ON_CARD) { it.status }.map { it.name },
        )
    }

    /**
     * A kit whose stamp has not been read yet has no status. It is dropped
     * while a filter is on — which is exactly why the screen must not offer
     * the chip until the read lands. Pinned here so the rule is visible to
     * whoever wires this up next.
     */
    @Test
    fun `a kit with no status read yet is not claimed for any filter`() {
        val unread = listOf(Kit("UNREAD", null))
        assertEquals(unread, ShelfFilter.apply(unread, null) { it.status }, "unfiltered, it shows")
        for (status in DubStamp.Status.entries) {
            assertTrue(
                ShelfFilter.apply(unread, status) { it.status }.isEmpty(),
                "an unread stamp must not be counted as $status",
            )
        }
    }

    /**
     * The failure this feature could actually cause: a shelf that empties
     * itself reads as lost work, not as a filter. The line has to name the
     * filter, the real count, and the way back.
     */
    @Test
    fun `an empty result says the kits are still there and how to see them`() {
        val line = Copy.shelfFilterEmpty(DubStamp.Status.ON_CARD, total = 4)
        assertTrue(Copy.dubChip(DubStamp.Status.ON_CARD) in line, "names the filter that did it: $line")
        assertTrue("4" in line, "names how many are really there: $line")
        assertTrue("SHOW" in line, "names the chip that undoes it: $line")
        assertTrue(line.endsWith("."), "a line the screen says, not furniture: $line")
        assertEquals(line.uppercase(java.util.Locale.ROOT), line, "TapeOS shouts: $line")
    }

    /**
     * The screen hides the chip during a pick flow (SNIPS to PAD, BREED),
     * on a shelf too short to filter, and before the stamps are read. A
     * filter left applied in any of those removes kits with no control on
     * screen to bring them back - and during SNIPS to PAD a kit that
     * cannot be seen cannot be picked, so a forgotten DRAFT filter would
     * make a snip unplaceable on most of the shelf for no stated reason.
     */
    @Test
    fun `a filter with no chip on screen does not bite`() {
        for (status in DubStamp.Status.entries) {
            assertNull(
                ShelfFilter.effective(status, offered = false),
                "$status must not narrow the shelf while its chip is hidden",
            )
            assertEquals(status, ShelfFilter.effective(status, offered = true))
        }
        assertNull(ShelfFilter.effective(null, offered = true))
        assertNull(ShelfFilter.effective(null, offered = false))

        // And through apply(), which is how the screen actually uses it.
        assertEquals(
            shelf,
            ShelfFilter.apply(shelf, ShelfFilter.effective(DubStamp.Status.DRAFT, offered = false)) { it.status },
            "every kit stays pickable while the chip is away",
        )
    }

    /**
     * `Copy.shelfFilterEmpty` tells the user a tap brings their kits back.
     * Plain `next` only keeps that promise from the last state in the
     * cycle: from DRAFT it steps to DUBBED, which may be just as empty, and
     * the line would have lied twice before anything came back.
     */
    @Test
    fun `a tap from an empty shelf goes straight back to everything`() {
        for (status in DubStamp.Status.entries) {
            assertNull(
                ShelfFilter.nextFrom(status, showingNothing = true),
                "the line promises one tap back, so $status must return to ALL",
            )
        }
        // With kits on screen it is an ordinary cycle step.
        for (status in ShelfFilter.CYCLE) {
            assertEquals(
                ShelfFilter.next(status),
                ShelfFilter.nextFrom(status, showingNothing = false),
            )
        }
    }

    @Test
    fun `the header chip and the row chip name a state the same way`() {
        for (status in DubStamp.Status.entries) {
            assertTrue(
                Copy.dubChip(status) in Copy.shelfFilter(status),
                "the shelf must not call a state one thing on a row and another in the header: " +
                    "${Copy.dubChip(status)} vs ${Copy.shelfFilter(status)}",
            )
        }
        assertEquals(Copy.SHELF_FILTER_ALL, Copy.shelfFilter(null))
    }

    @Test
    fun `the filter chip reads like the sort chip beside it`() {
        // Both are state, not commands: one glance down the header says what
        // the shelf is doing, in one grammar.
        for (label in listOf(Copy.shelfFilter(null)) + DubStamp.Status.entries.map { Copy.shelfFilter(it) }) {
            assertTrue("▸" in label, "the header's chips share one shape: $label vs ${Copy.SHELF_SORT_RECENT}")
            assertTrue(!label.endsWith("."), "furniture, not a sentence: $label")
            assertEquals(label.uppercase(java.util.Locale.ROOT), label, "TapeOS shouts: $label")
        }
    }
}
