package com.snipsnap.synth

import kotlin.math.sqrt

/**
 * A small starting bank around the three source choices accepted in the R2 audition.
 * These new voicings are proposals awaiting musical audition, not approved listening results.
 * Each patch saves the complete control position; the source profile follows its voice.
 *
 * Intermediate gates keep the starting positions away from accidental extinction. COLOR and
 * REJECT vary harmonic survival and the collected outline, rather than filling the bank with
 * different gains of the same neutral transmission. One deliberate HOLD loop serves each
 * original source voice. TRACE keeps the accepted moving pluck as its reference and offers
 * slower and quicker versions of that same gesture; all three remain finite notes.
 */
object LatticePresets {
    /** Promoted user-preset rows can paste into the same native patch helper. */
    private fun p(voice: LatticeVoice, name: String, vararg macros: Pair<String, Float>) =
        LatticePatch(name, voice, Lattice.defaults(voice) + macros.toMap())

    /** STAGES has quadratic travel so the first restoring gate has useful control space. */
    private fun stages(gates: Double): Float = sqrt((gates - 2.0) / 14.0).toFloat()

    private fun recipe(
        voice: LatticeVoice,
        name: String,
        gates: Double,
        turn: Float,
        twist: Float,
        color: Float,
        reject: Float,
        hold: Float = 0f,
    ) = p(
        voice, name,
        "TUNE" to .5f,
        "TURN" to turn,
        "STAGES" to stages(gates),
        "TWIST" to twist,
        "COLOR" to color,
        "REJECT" to reject,
        "HOLD" to hold,
    )

    private val presets = listOf(
        // The leading WARM patch retains the accepted finite musical direction.
        recipe(LatticeVoice.WARM, "Warm Passage", gates = 8.0,
            turn = 1f, twist = .5f, color = .45f, reject = 0f),
        recipe(LatticeVoice.WARM, "Close Weave", gates = 3.5,
            turn = .78f, twist = .28f, color = .75f, reject = .15f, hold = .22f),
        recipe(LatticeVoice.WARM, "Soft Outline", gates = 4.0,
            turn = .82f, twist = .70f, color = .60f, reject = .65f, hold = .32f),
        recipe(LatticeVoice.WARM, "Warm Thread", gates = 8.0,
            turn = .90f, twist = .55f, color = .50f, reject = .20f, hold = 1f),

        recipe(LatticeVoice.PLUCK, "Open Pluck", gates = 6.0,
            turn = .74f, twist = .50f, color = .25f, reject = 0f),
        recipe(LatticeVoice.PLUCK, "Plucked Outline", gates = 4.0,
            turn = .85f, twist = .78f, color = .70f, reject = .35f, hold = .08f),
        recipe(LatticeVoice.PLUCK, "Split Pluck", gates = 3.0,
            turn = .90f, twist = .30f, color = 1f, reject = .15f, hold = .22f),
        recipe(LatticeVoice.PLUCK, "Pluck Thread", gates = 5.0,
            turn = .88f, twist = .70f, color = .70f, reject = .45f, hold = 1f),

        recipe(LatticeVoice.HOLLOW, "Hollow Passage", gates = 5.0,
            turn = .88f, twist = .50f, color = .55f, reject = 0f),
        recipe(LatticeVoice.HOLLOW, "Odd Window", gates = 3.0,
            turn = .86f, twist = .30f, color = 1f, reject = .12f, hold = .18f),
        recipe(LatticeVoice.HOLLOW, "Hollow Trace", gates = 4.0,
            turn = .92f, twist = .83f, color = .78f, reject = .68f, hold = .38f),
        recipe(LatticeVoice.HOLLOW, "Hollow Thread", gates = 6.0,
            turn = .92f, twist = .35f, color = .85f, reject = .35f, hold = 1f),

        p(LatticeVoice.TRACE, "Ringing Trace", "TUNE" to .5f, "SPEED" to .5f, "HOLD" to 0f),
        p(LatticeVoice.TRACE, "Slow Trace", "TUNE" to .5f, "SPEED" to .25f, "HOLD" to 0f),
        p(LatticeVoice.TRACE, "Quick Trace", "TUNE" to .5f, "SPEED" to .75f, "HOLD" to 0f),
    )

    fun all(): List<LatticePatch> = presets
    fun patchesForVoice(voice: LatticeVoice): List<LatticePatch> = presets.filter { it.voice == voice }
    fun forVoice(voice: LatticeVoice): List<LatticePatch> = patchesForVoice(voice)
}
