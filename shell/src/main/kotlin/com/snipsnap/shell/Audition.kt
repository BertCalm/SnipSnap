package com.snipsnap.shell

/**
 * Choosing between two versions of a sound by hearing them where the sound
 * will actually live.
 *
 * The design and the reasons behind each rule are in
 * `docs/AUDITION_SPEC_2026_09.md`; this is the part of it that can be
 * decided without an audio device, and therefore the part that can be
 * proved. The screen and the engine wiring live in `:app`, which has no
 * Kotlin test source set — so anything here that could instead have been
 * written inline over there is deliberately on this side of that line.
 *
 * **Not [ShapeAudition]**, which shares the word and means something else:
 * that one *renders* a preview of a single pad so the SHAPE card can play
 * what its knobs would do. This one *compares* two finished sounds and
 * never renders anything. The overlap is unfortunate and left rather than
 * renamed, because AUDITION is the name of the feature on screen — but a
 * reader meeting both in this package should know which is which.
 *
 * What the caller still owns: reading the clock's position, appending the
 * candidates to the kit's own bank build (the way `PadEngine.load` already
 * appends the count-in clicks — never `loadSnips`, which replaces the whole
 * bank), and triggering the bank index this object names. Because the
 * candidates are in the same bank as the kit, swapping between them is an
 * integer choice at trigger time rather than a bank rebuild: there is no
 * gap to hear and nothing to make sample-accurate by hand.
 */
object Audition {

    /**
     * Two candidates, always.
     *
     * Not a limit waiting to be raised. Two bars is about two seconds at
     * working tempos, which keeps the comparison inside the window where
     * small differences are still audible; three candidates is six seconds
     * a side and puts the player back to comparing memories, which is the
     * exact failure the bar-swap exists to avoid. More than two is handled
     * by running knockout pairs, never by a longer rotation.
     */
    const val CANDIDATES = 2

    /**
     * Which candidate sounds during [bar].
     *
     * `floorMod`, not `%`: the clock's position is a float that other code
     * already resets and interpolates around — the count-in runs before the
     * downbeat — so a bar can legitimately be negative, and `%` would hand
     * back a negative index.
     */
    fun liveAt(bar: Int, candidates: Int = CANDIDATES): Int =
        if (candidates <= 0) 0 else Math.floorMod(bar, candidates)

    /**
     * The bar [posSteps] falls in, given the clip's own [stepsPerBar].
     *
     * Floor, not rounding: a position 15.9 steps into a 16-step bar is still
     * in that bar, and rounding it up would swap the candidate a fraction
     * early — audible as a glitch rather than as a comparison.
     *
     * [stepsPerBar] is a parameter because not every clip is 4/4. The
     * playback clock in `GrooveScreen` had exactly this bug: an ORBIT clip
     * declares its own meter, and a two-bar 3/4 clip looped at a hardcoded
     * 32 steps "played eight of silence on the end of every pass". A
     * non-positive value cannot divide, so it answers bar 0 rather than
     * throwing into an audio callback.
     */
    fun barOf(posSteps: Float, stepsPerBar: Int): Int =
        if (stepsPerBar <= 0) 0 else Math.floorDiv(posSteps.toInt(), stepsPerBar)

    /** Below this a candidate is silence rather than a quiet sound, and cannot be scaled to match anything. */
    private const val AUDIBLE = 1e-5f

    /**
     * Per-candidate gains that put every candidate at the same perceived
     * loudness, given each one's measured loudness (`Loudness.of`).
     *
     * **Why this is not optional.** In a blind comparison the louder
     * candidate wins near-universally and listeners cannot hear past it. Two
     * versions of a snare a decibel apart produce a confident, repeatable,
     * *wrong* answer — so skipping this is worse than not building the
     * feature, because it manufactures false confidence rather than merely
     * failing to help.
     *
     * **Matched down to the quietest, never up.** Boosting to meet a louder
     * candidate can push it into clipping, and a clipped candidate loses for
     * a reason that has nothing to do with whether it was the better sound —
     * which is the same class of confound this exists to remove.
     *
     * A candidate that measures silent is left at unity and excluded from
     * the target: it has no level to match, and letting it set the target
     * would mute everything beside it, turning "this take is empty" into
     * "both takes are empty".
     *
     * The app already holds the other half of this: `Punch.rescaleToLoudness`
     * applies a loudness target for the same reason, so that PUNCH is not
     * judged on "the loudness-war confound a transient/saturation stage
     * should not be".
     */
    fun matchGains(loudnesses: List<Float>): List<Float> {
        val target = loudnesses.filter { it > AUDIBLE }.minOrNull() ?: return loudnesses.map { 1f }
        return loudnesses.map { if (it <= AUDIBLE) 1f else (target / it).coerceIn(0f, 1f) }
    }

    /**
     * One comparison in progress: what is being compared, and whether the
     * player has asked to be told which is which.
     *
     * Starts blind. Blind protects the first impression, which is the only
     * uncontaminated judgement available — and it matters most for a
     * candidate the app generated, which labelled would be rejected on
     * principle or accepted out of novelty, neither of which is about the
     * sound.
     */
    data class Round(
        val names: List<String>,
        val revealed: Boolean = false,
    ) {
        /**
         * What to show for candidate [index] — a neutral letter while blind,
         * its real name once revealed.
         */
        fun labelFor(index: Int): String =
            if (revealed) names.getOrElse(index) { LETTERS.getOrElse(index) { "?" } }
            else LETTERS.getOrElse(index) { "?" }

        /**
         * Tell the player which was which.
         *
         * **A one-way door, and the spec turns on it.** Blind → labelled is
         * possible; the reverse is not, because nobody can un-know which
         * candidate they were preferring. That is why there is no `hide()`
         * here and why the reveal is a deliberate act rather than a timer:
         * a countdown that fires after four bars would quietly spend the one
         * honest listen the player gets. The loop does not stop at the
         * reveal — hearing them labelled answers a second, different
         * question: not which is better, but what the difference *was*.
         */
        fun reveal(): Round = if (revealed) this else copy(revealed = true)
    }

    private val LETTERS = listOf("A", "B", "C", "D")
}
