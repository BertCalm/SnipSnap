package com.snipsnap.audio

/** A detected fundamental: where it is and how sure the detector is. */
data class PitchEstimate(
    val hz: Float,
    /** Normalized autocorrelation at the chosen period, 0..1. Below ~0.5 is a guess. */
    val confidence: Float,
)

/**
 * Autocorrelation pitch detection.
 *
 * Zero-crossing counting reads harmonics and the spectral centroid reads
 * brightness — neither is pitch. Autocorrelation over the note body (after
 * the attack transient) finds the actual period: the smallest lag whose
 * correlation is a local peak near the global maximum, so strong harmonics
 * don't fold the estimate up an octave.
 *
 * Born as the test harness for the melodic engines (every "TUNE tunes"
 * assertion runs through here); promoted to product code because in-key
 * sampling needs the same answer about captured audio.
 */
object Pitch {

    private const val MIN_HZ = 40f    // below VELVET's sub octave, above rumble
    private const val MAX_HZ = 1200f
    private const val MIN_WINDOW_FRAMES = 1200

    /**
     * Detect the fundamental of [snip]'s body, or null when there isn't one
     * to find (silence, noise, or too short to measure).
     *
     * [fromSec] skips the attack — a pluck's pick or a drum's click is
     * noise, and the note lives after it. [windowSec] is how much body to
     * correlate over.
     */
    fun detect(snip: Snip, fromSec: Float = 0.05f, windowSec: Float = 0.25f): PitchEstimate? {
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        val from = (fromSec * snip.sampleRate).toInt().coerceAtLeast(0)
        val to = minOf(mono.size, from + (windowSec * snip.sampleRate).toInt())
        val n = to - from
        if (n < MIN_WINDOW_FRAMES) return null

        val minLag = (snip.sampleRate / MAX_HZ).toInt().coerceAtLeast(2)
        val maxLag = minOf((snip.sampleRate / MIN_HZ).toInt(), n - 2)
        if (maxLag <= minLag + 2) return null

        var energy = 0.0
        for (i in from until to) energy += mono[i].toDouble() * mono[i]
        if (energy <= 1e-9) return null

        val r = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in from until to - lag) sum += mono[i].toDouble() * mono[i + lag]
            r[lag] = sum / energy
        }
        var rmax = 0.0
        for (lag in minLag..maxLag) if (r[lag] > rmax) rmax = r[lag]
        if (rmax < 0.3) return null // nothing periodic enough to call a note

        for (lag in minLag + 1 until maxLag) {
            if (r[lag] > 0.85 * rmax && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                return PitchEstimate(
                    hz = snip.sampleRate.toFloat() / lag,
                    confidence = r[lag].toFloat().coerceIn(0f, 1f),
                )
            }
        }
        return null
    }
}
