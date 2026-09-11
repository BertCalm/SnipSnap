package com.snipsnap.shell

/**
 * How pads are numbered across banks, in one place.
 *
 * The MPC counts pads in banks of sixteen: slots 1..16 are bank A, 17..32
 * bank B, and so on. A pad's name is its bank letter and its position
 * inside that bank - "A01", "B16" - which is what the CLI prints, what the
 * lineage records, and what the export side writes.
 *
 * This exists because that rule was written out six times in `:app` and
 * four of those copies said `"A%02d".format(slot)` - correct only while
 * nothing above slot 16 could ever be looked at. `PadGrid` even carried a
 * comment explaining that reconciling them was somebody else's job. The
 * September UAT's finding 11 made it somebody's job: bank B pads are
 * editable now, so a formula that calls slot 17 "A17" is no longer
 * dormant, it is wrong on screen.
 */
object PadBanks {

    /** Pads in one bank: the MPC's 4x4. */
    const val SIZE = 16

    /** Which bank a slot falls in, counting from zero: 1..16 is 0, 17..32 is 1. */
    fun bankOf(slot: Int): Int {
        require(slot >= 1) { "slots are 1-based, got $slot" }
        return (slot - 1) / SIZE
    }

    /** The bank's letter, counting from zero: 0 is 'A'. */
    fun letter(bank: Int): Char {
        require(bank >= 0) { "banks are 0-based, got $bank" }
        return 'A' + bank
    }

    /** Every slot in one bank, low to high. */
    fun slots(bank: Int): IntRange {
        require(bank >= 0) { "banks are 0-based, got $bank" }
        return (bank * SIZE + 1)..(bank * SIZE + SIZE)
    }

    /** "A01", "B16" - the tag the CLI, the lineage and the export side all use. */
    fun tag(slot: Int): String {
        val n = (slot - 1) % SIZE + 1
        // Locale.ROOT: this is an identifier, now the ONE place every
        // screen's pad tag comes from - PadNoteMap.labelForPad's own KDoc
        // explains why (%02d localizes its digits under the default
        // locale; a tag reaching a filename or an MPC card must not).
        return "%c%02d".format(java.util.Locale.ROOT, letter(bankOf(slot)), n)
    }

    /**
     * How many banks a kit occupies, from the slots it has filled.
     *
     * Always at least one: an empty kit is a kit with an empty bank A, not
     * a kit with no banks. Counts to the highest slot used rather than the
     * number of pads, because a kit whose only pad sits on B03 still has
     * two banks - the screen has to be able to reach it.
     */
    fun banksUsed(slots: Collection<Int>): Int =
        slots.maxOrNull()?.let { bankOf(it) + 1 } ?: 1
}
