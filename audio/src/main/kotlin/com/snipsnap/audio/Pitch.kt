package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A detected fundamental: where it is and how sure the detector is. */
data class PitchEstimate(
    val hz: Float,
    /** Normalized autocorrelation at the chosen period candidate, 0..1. Below ~0.5 is a guess. */
    val confidence: Float,
)

/**
 * Autocorrelation pitch detection.
 *
 * Zero-crossing counting reads harmonics and the spectral centroid reads
 * brightness — neither is pitch. Autocorrelation over the note body (after
 * the attack transient) finds the actual period: the smallest lag whose
 * correlation is a local peak near the global maximum, so strong harmonics
 * don't fold the estimate up an octave. A nearby spectral peak then refines
 * that candidate: inharmonic upper modes can pull a correlation maximum off
 * the audible fundamental even when the period evidence is confident.
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
                // A parabola through the peak and its neighbours places the period between
                // whole samples, which the lag alone cannot (nine cents a step near 1 kHz).
                val curve = r[lag - 1] - 2 * r[lag] + r[lag + 1]
                val shift = if (abs(curve) > 1e-12) (.5 * (r[lag - 1] - r[lag + 1]) / curve).coerceIn(-.5, .5) else 0.0
                return PitchEstimate(
                    hz = refine(mono, from, n, snip.sampleRate, (snip.sampleRate / (lag + shift)).toFloat()),
                    confidence = r[lag].toFloat().coerceIn(0f, 1f),
                )
            }
        }
        return null
    }

    /** Refine only the selected period's neighborhood; never select a new octave.
     * A missing fundamental has no substantial local peak and keeps its period estimate. */
    private fun refine(samples: FloatArray, from: Int, n: Int, rate: Int, candidate: Float): Float {
        var size = 1
        while (size < n) size = size shl 1
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (i in 0 until n) {
            val window = .5 - .5 * cos(2 * PI * i / (n - 1))
            real[i] = (samples[from + i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        fun power(bin: Int) = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
        val binHz = rate.toDouble() / size
        val semitone = 2.0.pow(1.0 / 12)
        val lowerHz = candidate / semitone
        val upperHz = candidate * semitone
        val lower = ceil(lowerHz / binHz).toInt().coerceAtLeast(1)
        val upper = floor(upperHz / binHz).toInt().coerceAtMost(size / 2 - 1)
        if (lower > upper) return candidate

        var peak = -1
        var strongest = 0.0
        var globalPower = 0.0
        for (bin in 1 until size / 2) globalPower = maxOf(globalPower, power(bin))
        for (bin in lower..upper) {
            val p = power(bin)
            if (p > strongest && p >= power(bin - 1) && p >= power(bin + 1)) {
                peak = bin
                strongest = p
            }
        }
        // Reject distant partial leakage and noise near an absent fundamental. The
        // autocorrelation still owns confidence and the decision that this is a note.
        if (peak < 0 || strongest < globalPower * .005) return candidate
        // Move only to a separate component that outweighs the candidate's own bin. A peak
        // within a bin of the candidate is the candidate's own Hann lobe: in a short window a
        // bin spans tens of cents, so interpolating it is coarser than the interpolated period
        // (TIDE's BUZZ SAW read 32 cents flat that way). An inharmonic pull leaves the candidate
        // nearly empty with the real fundamental standing well clear of it.
        val candidateBin = (candidate / binHz).roundToInt()
        if (abs(peak - candidateBin) < 2 || strongest < power(candidateBin) * 10) return candidate
        val left = ln(power(peak - 1).coerceAtLeast(1e-30))
        val middle = ln(strongest.coerceAtLeast(1e-30))
        val right = ln(power(peak + 1).coerceAtLeast(1e-30))
        val denominator = left - 2 * middle + right
        val offset = if (abs(denominator) > 1e-12) .5 * (left - right) / denominator else 0.0
        val refined = (peak + offset.coerceIn(-.5, .5)) * binHz
        return if (refined.isFinite() && refined in lowerHz..upperHz) refined.toFloat() else candidate
    }
}
