package com.snipsnap.audio

/**
 * The one ceiling every summed render shares: a kit preview, a session
 * mixdown, an arranged song, and the loop grid's intervals.
 *
 * Each of those used to carry its own `0.95f` and its own copy of the scan
 * and the scale, four copies of one rule. They agreed only because nobody
 * had changed one yet, and the loop grid had no copy at all: six engaged
 * tracks bounced at a peak of 5.1 straight into [WavWriter]'s clamp, which
 * is a hard clip (docs/plans/2026-10-30-song-making.md, phase 1).
 *
 * This is a peak ceiling, not a limiter: under it nothing is touched, over
 * it the whole buffer is scaled by one number, so the balance inside the
 * buffer survives and nothing is distorted. Loudness is a different
 * question and a later phase.
 */
object SongBus {

    /**
     * The highest sample magnitude a summed render may reach. A little under
     * full scale, so a mix never meets the clamp and the resampling an MPC
     * does on load has room.
     */
    const val PEAK = 0.95f

    /**
     * The largest magnitude in [samples]. A NaN never wins the comparison,
     * so it reads as absent rather than as loud.
     */
    fun peakOf(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * Scale [samples] in place so no magnitude exceeds [PEAK]. A buffer
     * already under it is left exactly as it was, so a quiet render's bytes
     * never move.
     */
    fun ceiling(samples: FloatArray) {
        val peak = peakOf(samples)
        if (peak > PEAK) {
            val k = PEAK / peak
            for (i in samples.indices) samples[i] *= k
        }
    }
}
