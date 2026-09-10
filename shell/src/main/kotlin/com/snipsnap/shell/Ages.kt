package com.snipsnap.shell

/**
 * How long ago something was, in the shelf's own words.
 *
 * September UAT, finding 16: the kit shelf sorts by most-recently-edited but
 * never showed *when*, so the order it was already in had no visible reason.
 * A row that says TODAY or 3 D AGO explains the list without the user having
 * to work out the rule.
 *
 * `RoomRow` was already computing this shape inline; both read it here now,
 * so the two lists cannot drift into saying the same age two ways.
 */
object Ages {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * [thenMillis] as an age against [nowMillis] — TODAY, YESTERDAY, then
     * days, then weeks once a count of days stops being something anyone
     * reads at a glance.
     *
     * Whole days, floored: something four hours old on either side of
     * midnight is still TODAY, because the alternative is a shelf whose ages
     * change while nothing about the kits does. A future timestamp — a
     * clock that moved, a file copied from a machine ahead of this one —
     * reads TODAY rather than a negative count.
     */
    fun ago(thenMillis: Long, nowMillis: Long): String {
        val days = ((nowMillis - thenMillis) / DAY_MS).toInt()
        return when {
            days <= 0 -> "TODAY"
            days == 1 -> "YESTERDAY"
            days < 14 -> "$days D AGO"
            else -> "${days / 7} W AGO"
        }
    }
}
