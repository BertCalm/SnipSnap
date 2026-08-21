package com.snipsnap.audio

import kotlin.math.roundToInt

/**
 * What in-key tuning decided for one snip: where it was, where it's going,
 * and the coarse/fine offsets that take it there — the exact fields an MPC
 * pad already has, so the retune costs nothing at export.
 */
data class TuneResult(
    val detectedHz: Float,
    val confidence: Float,
    /** The in-key note the pad will sound as. */
    val targetMidi: Int,
    val targetName: String,
    /** Semitones for the pad's coarse tune, -36..36. */
    val tuneCoarse: Int,
    /** Cents for the pad's fine tune, -100..100. */
    val tuneFine: Int,
)

/**
 * In-key sampling: detect a captured snip's pitch and compute the pad
 * tuning that lands it on the nearest note of the user's key.
 *
 * Sample a bassline off a video, and every tonal pad plays in key with the
 * track the moment the kit loads — the feature that turns captured melodic
 * material from "close" into "usable". Non-destructive by construction:
 * the WAV is untouched, only the program's tune fields move.
 */
object Tuner {

    /** Below this the detector is guessing, and a guessed retune is worse than none. */
    const val MIN_CONFIDENCE = 0.5f

    /**
     * Tuning to bring [snip] onto the nearest note of [scale] rooted at
     * [rootSemitone] (semitones above C). Null when no confident pitch is
     * found — an unpitched hit should never be "corrected".
     */
    fun inKey(snip: Snip, rootSemitone: Int, scale: Scale): TuneResult? {
        val estimate = Pitch.detect(snip) ?: return null
        if (estimate.confidence < MIN_CONFIDENCE) return null

        val target = Scales.nearestInKey(estimate.hz, rootSemitone, scale)
        val offsetSemis = Scales.hzToMidi(Scales.midiToHz(target)) - Scales.hzToMidi(estimate.hz)
        // Total correction in cents, split MPC-style: whole semitones coarse,
        // the remainder fine.
        val cents = (offsetSemis * 100f).roundToInt()
        var coarse = cents / 100
        var fine = cents % 100
        if (fine > 50) { coarse += 1; fine -= 100 }
        if (fine < -50) { coarse -= 1; fine += 100 }
        if (coarse !in -36..36) return null // out of the MPC's reach; leave it alone

        return TuneResult(
            detectedHz = estimate.hz,
            confidence = estimate.confidence,
            targetMidi = target,
            targetName = Scales.nameOf(target),
            tuneCoarse = coarse,
            tuneFine = fine,
        )
    }
}
