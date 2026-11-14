package com.snipsnap.synth

/**
 * How one pluck meets the string in the model-4 exciter. A hard pluck is a narrow pick: an
 * instant contact, the built pick filter and the built mix of contact noise and pitched tone. A
 * soft pluck is a broad, rounded finger: the contact lasts [widthMs] (which low-passes the
 * excitation, so the short high strings and upper harmonics are barely set moving), a darker pick
 * filter, and more pitched tone relative to contact noise.
 *
 * [DEFAULT] reproduces the built exciter's arithmetic exactly; every existing render uses it.
 * The shape only changes the excitation burst. Strings, bodies, venue, gains and finishing are
 * untouched, so a shape can never add feedback, a new oscillator or a level stage.
 *
 * @property widthMs how long the contact presses, in milliseconds. 0 is the built instant contact.
 *   Otherwise the burst is smoothed by a raised-cosine pulse this wide (first spectral null at
 *   2 / width: 1 ms reaches its null at 2 kHz), centred on the scheduled pluck time.
 * @property pickBrightness multiplies the pick low-pass cutoff (1 = built brightness, smaller is darker).
 * @property toneShift added to each string's pitched-tone share (its "foundation"), so contact noise
 *   falls by the same amount. The sum is capped at 1 (pure tone, no noise).
 */
data class NacreStrikeShape(val widthMs: Double, val pickBrightness: Double, val toneShift: Double) {
    init {
        require(widthMs.isFinite() && widthMs in 0.0..MAX_WIDTH_MS) {
            "NACRE strike contact width must be finite and within 0..$MAX_WIDTH_MS ms, got $widthMs"
        }
        require(pickBrightness.isFinite() && pickBrightness > 0.0 && pickBrightness <= 1.0) {
            "NACRE strike pick brightness must be finite and within (0, 1], got $pickBrightness"
        }
        require(toneShift.isFinite() && toneShift in 0.0..MAX_TONE_SHIFT) {
            "NACRE strike tone shift must be finite and within 0..$MAX_TONE_SHIFT, got $toneShift"
        }
    }

    companion object {
        const val MAX_WIDTH_MS = 4.0
        const val MAX_TONE_SHIFT = .25
        val DEFAULT = NacreStrikeShape(0.0, 1.0, 0.0)
    }
}
