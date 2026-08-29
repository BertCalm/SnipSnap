package com.snipsnap.audio

/**
 * Time-scale modification — tempo changes, pitch does not, and the
 * kick still punches.
 *
 * The plan was Driedger & Müller's hybrid (HPSS split, vocoder for the
 * harmonic half, short-frame OLA for the percussive), which exists to
 * fix the classic phase vocoder's smeared transients. Measurement
 * overruled the plan: [Pghi]'s heap-integrated phases don't have that
 * disease — a stretched kick's attack rise measured 33 ms against the
 * original's 33 ms, while the hybrid's *unaligned sum* of two
 * independently-stretched halves smeared it to 51 ms. Modern phase
 * reconstruction ate the reason the hybrid existed, so retime is the
 * PGHI vocoder alone: simpler, and measurably sharper.
 *
 * Mono by design, like [Pghi]; a stereo source is folded first.
 */
object Retime {

    /** Refused past double or half speed, like [TempoFit]. */
    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2f

    /** The source [ratio]× longer (2 = half speed), pitch untouched. */
    fun retime(source: Snip, ratio: Float, seed: Long = 0): Snip {
        require(ratio in MIN_RATIO..MAX_RATIO) {
            "retime wants $MIN_RATIO..$MAX_RATIO (past double or half speed the seams show) - got $ratio"
        }
        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        require(mono.frameCount > 0) { "the source is empty" }
        return Pghi.stretch(mono, ratio, seed)
    }
}
