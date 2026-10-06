package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * The loudness every audition clip is rendered at - a copy of `:synth`'s
 * test-source `AuditionLevel`, which is internal to that module, so a
 * `:shell` page (MUTATE's BECOME gate) is heard at the same level, by the
 * same rule, as every `:synth` page. `AuditionCopiesTest` fails if the two
 * stop matching in code.
 */
internal object AuditionLevel {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    /**
     * One loudness for every clip, for a fair A/B. The measure is
     * [Loudness.of] - the RMS of the loudest 200 ms window - not a
     * whole-file RMS, so clip length does not decide the comparison. A peak
     * guard keeps the file in range; a stereo clip keeps both channels at
     * one gain, so its image is untouched.
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
