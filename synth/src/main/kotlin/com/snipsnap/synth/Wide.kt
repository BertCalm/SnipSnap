package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * WIDE, the first compound board: tape -> EQ -> ensemble -> room.
 *
 * This order is part of its sound, independently of [FxChain]'s fixed rack
 * order. The mappings preserve the first WIDE audition at AMT .35, .7 and 1.
 * AMT changes macro intensity rather than crossfading a delayed wet signal
 * against the source. Zero is a hard bypass; nonzero amounts retain Tape's
 * and Ensemble's short delay and tone, including near zero.
 *
 * Renders an unlevelled one-shot. The processors retain their own shared
 * peak matching; no audition gain, repeated hits or mono fold is added here.
 * Each call starts fresh effect state. Loop seams and a continuous live bus
 * need their own rendering policy.
 */
object Wide {
    const val MAX_TAIL_SECONDS = Spring.MAX_TAIL_SECONDS

    fun process(snip: Snip, amount: Float = 1f): Snip {
        require(amount.isFinite() && amount in 0f..1f) { "WIDE amount must be finite and in 0..1: $amount" }
        if (amount == 0f || snip.frameCount == 0) return snip
        var out = snip
        for (stage in stages(amount)) out = stage.run(out, stage.macros)
        check(out.frameCount.toLong() <= snip.frameCount.toLong() + (MAX_TAIL_SECONDS * snip.sampleRate).toLong()) {
            "WIDE exceeded its Spring tail budget"
        }
        return out
    }

    /** Shared with the audition's cumulative diagnostics and recipe readout. */
    internal class Stage(
        val name: String,
        val macros: Map<String, Float>,
        val run: (Snip, Map<String, Float>) -> Snip,
    )

    internal fun stages(amount: Float): List<Stage> {
        require(amount.isFinite() && amount in 0f..1f) { "WIDE amount must be finite and in 0..1: $amount" }
        if (amount == 0f) return emptyList()
        fun toward(flat: Float, target: Float): Float = flat + amount * (target - flat)
        return listOf(
            Stage("Tape", linkedMapOf(
                "WOBBLE" to .45f * amount, "DRIVE" to .3f * amount, "AGE" to .4f * amount,
            ), Tape::process),
            Stage("EQ", linkedMapOf(
                "BASS" to toward(.5f, .46f), "MID" to toward(.5f, .48f), "AIR" to toward(.5f, .6f),
            ), Eq::process),
            Stage("Ensemble", linkedMapOf(
                "DEPTH" to .55f * amount, "RATE" to .5f, "WIDTH" to amount, "SECTION" to 0f,
            ), Ensemble::process),
            Stage("Spring", linkedMapOf(
                "SIZE" to .6f * amount, "TONE" to toward(.5f, .6f), "MIX" to .3f * amount,
            ), Spring::process),
        )
    }
}
