package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Tempo-fit by repitch — the classic sampler answer, and the revered
 * lo-fi move: resample by the tempo ratio so a 95 BPM loop plays clean at
 * 92, pitch riding along with the speed the way an SP or an MPC60 always
 * did it. No phase vocoder, no artifacts; the pitch shift *is* the sound.
 *
 * The sinc [Resampler] does the arithmetic: content resampled to a
 * virtual rate, then reinterpreted at the original — duration scales by
 * `from/to`, pitch by `to/from`.
 */
object TempoFit {

    /** Fits past a factor of two each way are a different instrument. */
    const val MAX_RATIO = 2f

    fun repitch(snip: Snip, fromBpm: Float, toBpm: Float): Snip {
        require(fromBpm > 0f && toBpm > 0f) { "tempos must be positive: $fromBpm -> $toBpm" }
        val speed = toBpm / fromBpm
        require(speed in 1f / MAX_RATIO..MAX_RATIO) {
            "a $fromBpm -> $toBpm fit is a factor of %.2f - past double/half speed, that's not a fit".format(java.util.Locale.ROOT, speed)
        }
        if (abs(speed - 1f) < 1e-4f) return snip

        val virtualRate = (snip.sampleRate * fromBpm / toBpm).roundToInt()
        val stretched = Resampler.resample(snip, virtualRate)
        return Snip(stretched.samples, stretched.channels, snip.sampleRate)
    }

    /** What the repitch costs in pitch — for the honest printout. */
    fun semitones(fromBpm: Float, toBpm: Float): Float =
        (12.0 * ln(toBpm.toDouble() / fromBpm) / ln(2.0)).toFloat()
}
