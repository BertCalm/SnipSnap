package com.snipsnap.shell

/**
 * How the app says a thing can be taken back.
 *
 * The September specs settled that undo is a labelling problem before it
 * is a stack: *every destructive action names its way back at the moment
 * it happens.* This object is that vocabulary — one phrasing per way back,
 * so the app answers "can I undo this?" the same way on every screen.
 *
 * **There are exactly three answers, because the app only has three.**
 * A row goes to a bin and waits ([BIN], [MIND]); a control on the screen
 * steps it back ([UNDO]); or nothing does ([GONE]). The third is not a
 * failure to build the first two — some things genuinely cannot be
 * reversed, and a line that implies otherwise is worse than one that
 * admits it, which is the whole reason this object exists. `ReversalTest`
 * counts the [GONE] sites, and that count is the evidence that decides
 * whether the deferred undo stack is worth building.
 *
 * **[DAYS] is read, never retyped.** Four separate objects each declare
 * their own bin retention ([KitBuilderModel.BIN_KEEP_DAYS], [SnipStore.BIN_DAYS],
 * [Rooms.BIN_DAYS] and `KitShelf.BIN_DAYS` in `:app`), and three `Copy`
 * lines used to promise "30 DAYS" as a literal that agreed with all four
 * only by luck. Changing the retention would have left the app promising
 * the old number in the user's own language while deleting on the new one
 * — a label that lies about the way back, which is exactly the defect this
 * work exists to remove. `ReversalTest` pins the four together.
 */
object Reversal {

    /**
     * How long every bin in this app holds what was thrown into it.
     *
     * Declared here because this is where the *promise* is made. The four
     * objects that do the sweeping keep their own constants — they are
     * storage policy, and one of them lives in `:app` where `:shell`
     * cannot see it — so this is not their single source of truth. It is
     * the single source of the sentence, and `ReversalTest` fails if any
     * of them drifts away from it.
     */
    const val DAYS = 30

    /**
     * A row went to a bin and will wait there.
     *
     * Word for word what `Copy.DELETE_SNIP` already said. [BIN] and [MIND]
     * exist to stop four bins and three sentences drifting apart, not to
     * reword them — so they are deliberately not in the plain style [GONE]
     * uses. Rewording them belongs to the copy rewrite, which owns every
     * line that was already on screen.
     */
    const val BIN = "THE BIN KEEPS IT $DAYS DAYS."

    /**
     * The same fact for the lines that name what was thrown out rather
     * than the bin that caught it — word for word what `kitDeleted`,
     * `snipDeleted` and `roomForgotten` already said. See [BIN] on why
     * these two keep their old wording.
     */
    const val MIND = "$DAYS DAYS TO CHANGE YOUR MIND."

    /** A control on this screen steps it back, right now. */
    const val UNDO = "TAP UNDO TO REVERSE THIS."

    /**
     * Nothing takes it back.
     *
     * Written plainly, which is the point: the bar wipe said "THE MACHINE
     * FORGIVES" for months, and that reads as an offer of forgiveness where
     * none is on offer. A user who trusts a line like that loses work they
     * believed was recoverable. Say it cannot be undone, then say what they
     * can do instead; see [goneBut] for that second half.
     *
     * **Plain words, existing case.** These lines are new, so they are
     * written as clear labels rather than in the voice the rest of `Copy`
     * uses. They still shout, because `PersonalityTest`'s laws hold for
     * every `Copy` constant and this is not the change that retires them.
     */
    const val GONE = "THIS CANNOT BE UNDONE."

    /**
     * [GONE] with the honest recourse appended: not an undo, but the work
     * that stands in for one.
     *
     * [instead] is an instruction, not a consolation — "TAP THE STEPS BACK
     * IN", not "DON'T WORRY". Its own trailing full stop is trimmed before
     * one is added, so a caller that writes a whole sentence and a caller
     * that writes a clause both land on exactly one.
     */
    fun goneBut(instead: String): String = "$GONE ${instead.trimEnd('.', ' ')}."
}
