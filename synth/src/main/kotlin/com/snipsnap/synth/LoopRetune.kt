package com.snipsnap.synth

import kotlin.math.abs

/**
 * BORE's route to a loop that closes by period, lifted out of [Arco] (where it ran as `Arco.retuned`) with its text unchanged, so that
 * ARCO's LOOP and held key and NACRE GROTTO's held bow ([NacreGrotto]) retune by the one copy. ARCO's pins prove the move changed no byte.
 */
internal object LoopRetune {
    /**
     * The most times the loop's pitch is corrected so its whole periods fill its whole frames, and how close to
     * 1 the measured length over the wanted one must be to stop early (BORE's numbers, which hold at ARCO's top
     * note too: 3e-7 of a two second loop is about a tenth of a raw sample, 0.0005 of a period at A5, ERHU's top,
     * where a period is 200 raw samples).
     */
    const val LOOP_PASSES = 5
    const val LOOP_CONVERGED = 3e-7

    /** A stretch after [retuned]: the raw string, the pitch that was played to get it, and how many corrections it took (0 when the planned pitch already closed). */
    internal class Retuned(val raw: FloatArray, val tunedHz: Double, val passes: Int)

    /**
     * BORE's route to a loop that closes by period, the one place it runs: [render] the stretch at [target], measure the *played* length of the
     * plan's whole periods after [warm] frames ([Bore.measureLoopSamples]), and render again at `tuned * ratio`, up to [LOOP_PASSES] times, until
     * the ratio is within [LOOP_CONVERGED] of 1. ARCO's LOOP ([Arco.renderLoopMeasured]) and held key ([Arco.renderHeldMeasured]) and NACRE GROTTO's held bow all run it, each with its
     * own stretch, so the two cannot retune differently.
     */
    fun retuned(target: Float, plan: Bore.LoopPlan, warm: Int, render: (Float) -> FloatArray): Retuned {
        val over = Dsp.OVERSAMPLE
        val wanted = plan.frames.toDouble() * over
        var tuned = target.toDouble()
        var raw = render(tuned.toFloat())
        var passes = 0
        for (pass in 1..LOOP_PASSES) {
            val ratio = Bore.measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
            if (abs(ratio - 1.0) < LOOP_CONVERGED) break
            tuned *= ratio
            raw = render(tuned.toFloat())
            passes = pass
        }
        return Retuned(raw, tuned, passes)
    }
}
