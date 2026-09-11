package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip

/**
 * A–E as a pure function of the loaded state. Lives here, not in
 * `GrooveScreen.kt`, for two reasons: the app module has no unit-test source
 * set, so A–E's semantics could not be asserted anywhere while this was a
 * private UI function; and both the needle roll and the playback clock call
 * it, so it has to be one implementation or audio and display can disagree
 * about what is on screen.
 *
 * A–D are recomputed live and never stored. E is the one stateful program,
 * passed in.
 */
object GrooveProgram {

    /**
     * [feel] is the axis as the stepper reads it: −100 (fully tight) through
     * 0 (as played) to +100 (fully loose).
     *
     * The feel applies to [base] FIRST, so A, C and D all inherit it.
     *
     * **PROG B is deliberately exempt** — it is handed the UNFELT [base].
     * [GrooveVariations.swing] quantizes internally, and B is the
     * on-the-grid program by design ("ON THE GRID, PUSHED LATE"). Handing it
     * a felt clip would not leave it unchanged, it would shift whichever
     * notes the lean pushed across swing's rounding boundary — so the
     * exemption is implemented by what B is given, not by swing being immune.
     * The screen discloses this as "RIDES A · C · D".
     *
     * **PROG E is exempt too**: hand-placed steps, already on the grid, and
     * the user's own. Returned exactly as passed in.
     */
    fun compute(
        index: Int,
        base: Mpc3Clip,
        swingPercent: Int,
        feel: Int,
        feelTemplate: GrooveFeel.Template,
        eClip: Mpc3Clip?,
    ): Mpc3Clip? {
        val felt = if (feel == 0) base else GrooveFeel.applyFeel(base, feel / 100f, feelTemplate)
        return when (index) {
            0 -> felt
            1 -> GrooveVariations.swing(base, swingPercent)
            2 -> GrooveVariations.halfTime(felt)
            3 -> GrooveVariations.sparse(felt)
            else -> eClip
        }
    }
}
