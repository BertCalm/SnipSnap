package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Tuner

/**
 * In-key sampling at the kit level: retune every tonal pad onto the nearest
 * note of the chosen key by setting the pad's coarse/fine tune — fields the
 * MPC program already has, so the correction is free and non-destructive.
 *
 * Only [DrumClass.TONAL] pads are touched: retuning a kick's fundamental
 * "into key" is a classic way to ruin a kick, and the classifier already
 * knows the difference. Pads where no confident pitch is found are left
 * alone — an unpitched hit should never be corrected.
 */
object InKey {

    fun apply(
        arranged: List<ArrangedPad?>,
        rootSemitone: Int,
        scale: Scale,
    ): List<ArrangedPad?> = arranged.map { pad ->
        if (pad == null || pad.drumClass != DrumClass.TONAL) return@map pad
        val tune = Tuner.inKey(pad.snip, rootSemitone, scale) ?: return@map pad
        pad.copy(tuneCoarse = tune.tuneCoarse, tuneFine = tune.tuneFine)
    }
}
