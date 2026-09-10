package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PadBanksTest {

    /**
     * The bank boundary is the whole point, so it is tested at the seam
     * rather than in the middle: 16 is the last of A, 17 the first of B.
     * Four of `:app`'s six copies of this rule read `"A%02d".format(slot)`,
     * which agrees everywhere below 17 and nowhere above it - exactly the
     * shape of bug that hides until a feature makes the range reachable.
     */
    @Test
    fun `the tag names the bank and the position inside it`() {
        assertEquals("A01", PadBanks.tag(1))
        assertEquals("A16", PadBanks.tag(16))
        assertEquals("B01", PadBanks.tag(17))
        assertEquals("B16", PadBanks.tag(32))
        assertEquals("C01", PadBanks.tag(33))
        // The formula the four bad copies used, spelled out so the
        // disagreement is on the record rather than in a commit message.
        assertTrue(PadBanks.tag(17) != "A%02d".format(17))
    }

    @Test
    fun `bank and slots agree with each other at every boundary`() {
        for (bank in 0..3) {
            val slots = PadBanks.slots(bank)
            assertEquals(PadBanks.SIZE, slots.count(), "bank $bank is not $PadBanks.SIZE pads")
            for (slot in slots) {
                assertEquals(bank, PadBanks.bankOf(slot), "slot $slot should be in bank $bank")
                assertEquals(PadBanks.letter(bank), PadBanks.tag(slot).first())
            }
            // No gaps and no overlaps between neighbouring banks.
            assertEquals(PadBanks.slots(bank).last + 1, PadBanks.slots(bank + 1).first)
        }
    }

    /**
     * What KIT's bank switch is built on. A kit whose only pad sits on B03
     * still has two banks - counting pads instead of reaching for the
     * highest slot would hide it, and the screen would have no way to get
     * there. An empty kit has one empty bank, not zero banks.
     */
    @Test
    fun `banks used counts to the highest slot, not the number of pads`() {
        assertEquals(1, PadBanks.banksUsed(emptyList()), "an empty kit still has bank A")
        assertEquals(1, PadBanks.banksUsed(listOf(1)))
        assertEquals(1, PadBanks.banksUsed((1..16).toList()))
        assertEquals(2, PadBanks.banksUsed(listOf(17)), "one pad on B01 is still two banks")
        assertEquals(2, PadBanks.banksUsed(listOf(3, 19)), "a sparse kit that reaches B")
        assertEquals(2, PadBanks.banksUsed((1..32).toList()))
        assertEquals(3, PadBanks.banksUsed(listOf(33)))
    }

    /** Slots are 1-based everywhere in this codebase; 0 is a caller's bug, not bank A. */
    @Test
    fun `a slot below one is refused rather than silently read as bank A`() {
        assertFailsWith<IllegalArgumentException> { PadBanks.bankOf(0) }
        assertFailsWith<IllegalArgumentException> { PadBanks.tag(0) }
        assertFailsWith<IllegalArgumentException> { PadBanks.slots(-1) }
        assertFailsWith<IllegalArgumentException> { PadBanks.letter(-1) }
    }

    /** MutateSheet's own tag is the same rule; it must not drift back apart. */
    @Test
    fun `MutateSheet padTag and PadBanks tag agree`() {
        for (slot in 1..48) {
            assertEquals(PadBanks.tag(slot), MutateSheet.padTag(slot), "slot $slot")
        }
    }
}
