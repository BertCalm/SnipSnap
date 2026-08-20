package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * Autocorrelation pitch estimate for test assertions.
 *
 * Zero-crossing counting reads harmonics, and the spectral centroid reads
 * brightness — neither is pitch. Autocorrelation over the note body (after
 * the pick transient) finds the actual period: the smallest lag whose
 * correlation is a local peak near the global maximum, so strong harmonics
 * don't fold the estimate up an octave.
 */
internal object TestPitch {

    fun estimate(snip: Snip, fromSec: Float = 0.08f, windowSec: Float = 0.25f): Float {
        val from = (fromSec * snip.sampleRate).toInt()
        val to = minOf(snip.samples.size, from + (windowSec * snip.sampleRate).toInt())
        val n = to - from
        require(n > 1200) { "window too short for pitch estimate" }

        val minLag = snip.sampleRate / 1200  // 1.2 kHz ceiling
        val maxLag = snip.sampleRate / 40    // 40 Hz floor - VELVET's sub octave reaches 55 Hz

        var energy = 0.0
        for (i in from until to) energy += snip.samples[i].toDouble() * snip.samples[i]
        if (energy <= 1e-9) return 0f

        val r = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in from until to - lag) sum += snip.samples[i].toDouble() * snip.samples[i + lag]
            r[lag] = sum / energy
        }
        var rmax = 0.0
        for (lag in minLag..maxLag) if (r[lag] > rmax) rmax = r[lag]

        for (lag in minLag + 1 until maxLag) {
            if (r[lag] > 0.85 * rmax && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                return snip.sampleRate.toFloat() / lag
            }
        }
        return 0f
    }
}
