package com.snipsnap.shell

/**
 * Narrowing the kit shelf to one dub state, with no keyboard.
 *
 * September UAT, finding 16: the shelf sorts but does not find. A search
 * box is the obvious answer and is not the one taken here — that screen's
 * list sits under a `Column` with no `imePadding` (see `KitRenameDialog`'s
 * own KDoc), so a keyboard may cover the very rows it is filtering, and
 * there is no device in this repo's CI to prove otherwise. A control that
 * cannot be verified is not a fix, it is a guess with a test next to it.
 *
 * What a tap-only filter gives up is finding a kit by name. What it
 * answers is the question a shelf of twenty actually raises — *which of
 * these have I not dubbed yet* — using the state [DubStamp] already
 * records and the chips the rows already wear.
 *
 * **Not persisted, deliberately.** The sort is remembered because a sort
 * shows every kit either way. A filter hides them, and a hidden filter
 * restored on next open is how a user decides the app lost their work.
 * Every fresh open of the shelf shows the whole shelf.
 */
object ShelfFilter {

    /**
     * The cycle a single control walks: everything, then each state in
     * [DubStamp.Status] order, then back to everything.
     *
     * One cycling chip rather than four filter chips, for the reason the
     * menu row taught (finding 9): four legal 48dp targets would take most
     * of a 390dp row. It is also the shape the shelf's sort toggle already
     * uses, so the two controls beside each other behave the same way.
     *
     * `null` is "no filter" rather than a fourth enum case, so a caller
     * that ignores this object entirely still gets every kit.
     */
    val CYCLE: List<DubStamp.Status?> = listOf(null) + DubStamp.Status.entries

    /** The next state after [current], wrapping. An unknown value starts the cycle over. */
    fun next(current: DubStamp.Status?): DubStamp.Status? {
        val i = CYCLE.indexOf(current)
        return if (i < 0) CYCLE[0] else CYCLE[(i + 1) % CYCLE.size]
    }

    /**
     * The filter that is actually in force: [current] while the chip is
     * [offered], and none of it otherwise.
     *
     * **The invariant this object exists to keep.** A filter narrows the
     * shelf only while the control that undoes it is on screen. The screen
     * hides that chip in three situations - a pick flow (SNIPS to PAD,
     * BREED), a shelf too short to be worth filtering, and before the dub
     * stamps have been read - and in every one of them a filter left
     * quietly applied would remove kits with no way to get them back. The
     * SNIPS to PAD case is the sharp one: a kit that cannot be seen cannot
     * be picked, so a forgotten DRAFT filter would make a snip unplaceable
     * on most of the shelf for no stated reason.
     *
     * Taking the rule rather than repeating the condition means the two
     * cannot drift: whatever decides to draw the chip decides what filters.
     */
    fun effective(current: DubStamp.Status?, offered: Boolean): DubStamp.Status? =
        if (offered) current else null

    /**
     * Where a tap on the chip goes, given whether the shelf is currently
     * showing nothing ([showingNothing]).
     *
     * From an empty result it goes straight back to everything rather than
     * on to the next state. `Copy.shelfFilterEmpty` promises the user that
     * a tap brings their kits back, and plain [next] only keeps that
     * promise from the last state in the cycle - from DRAFT it would step
     * to DUBBED, which may be just as empty, and the line would have lied
     * twice before anything came back.
     */
    fun nextFrom(current: DubStamp.Status?, showingNothing: Boolean): DubStamp.Status? =
        if (showingNothing) null else next(current)

    /**
     * [items] narrowed to [current], or all of them when it is null.
     *
     * [statusOf] answers null for a kit whose stamp has not been read yet.
     * Those are dropped while a filter is on, which is why the caller must
     * not offer the control until the statuses are in: filtering against
     * answers nobody has yet would empty the shelf and blame the user's
     * kits for it.
     */
    fun <T> apply(items: List<T>, current: DubStamp.Status?, statusOf: (T) -> DubStamp.Status?): List<T> =
        if (current == null) items else items.filter { statusOf(it) == current }
}
