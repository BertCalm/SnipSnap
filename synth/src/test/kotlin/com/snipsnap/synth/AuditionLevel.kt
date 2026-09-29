package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * The loudness every audition clip is rendered at, shared by each engine's
 * audition generator ([PluckAuditionGenerator], [TinesAuditionGenerator])
 * so a clip from one listening page and a clip from the next are heard at
 * the same level, by the same rule.
 */
internal object AuditionLevel {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    /**
     * One loudness for every clip, for a fair A/B: the spike measured
     * shipped PLUCK as peak-limited, so loudness would decide the
     * comparison otherwise. The measure is [Loudness.of] - the RMS of the
     * loudest 200 ms window, the same measure `Dsp.levelTo` uses - not a
     * whole-file RMS: a whole-file measure divides a short thud's energy
     * over silence it doesn't have and a long ring's energy over tail it
     * does, so at equal loudest-moment level a longer clip reads quieter by
     * whole-file RMS and gets over-boosted here; clip length must not be
     * what decides the A/B. A peak guard keeps the file in range. A stereo
     * clip is metered on its fold, as [Loudness.of] always does, and keeps
     * its two channels — the same gain on both, so the image is untouched.
     */
    fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        val loudness = Loudness.of(snip)
        var g = AUDITION_LEVEL / loudness.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > 0.99f) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = snip.channels, sampleRate = snip.sampleRate)
    }
}
