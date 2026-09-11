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
     * change while nothing about the kits does. The elapsed time is clamped
     * at zero before it is divided rather than letting a negative day count
     * fall through a branch, so a future timestamp — a clock that moved, a
     * file copied from a machine ahead of this one — is TODAY by
     * construction instead of by coincidence.
     *
     * A caller with no timestamp should pass none: see [agoOrNull], which
     * exists because `File.lastModified()` answers `0L` for a file it cannot
     * read, and 0L is a real instant this would faithfully report as some
     * thousands of weeks ago.
     */
    fun ago(thenMillis: Long, nowMillis: Long): String {
        val elapsed = (nowMillis - thenMillis).coerceAtLeast(0L)
        val days = (elapsed / DAY_MS).toInt()
        return when {
            days == 0 -> "TODAY"
            days == 1 -> "YESTERDAY"
            days < 14 -> "$days D AGO"
            else -> "${days / 7} W AGO"
        }
    }

    /**
     * [ago], or null when [thenMillis] is not a timestamp anyone recorded.
     *
     * `File.lastModified()` returns `0L` for a file that does not exist or
     * cannot be read, and 1970 is a perfectly good instant — so a kit whose
     * `kit.json` went missing would otherwise wear a confident "2853 W AGO".
     * A row that says nothing is right; a row that says something false is
     * not.
     */
    fun agoOrNull(thenMillis: Long, nowMillis: Long): String? =
        if (thenMillis <= 0L) null else ago(thenMillis, nowMillis)
}
