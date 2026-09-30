package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln

/**
 * A finer pitch measurement than the promoted [com.snipsnap.audio.Pitch]
 * detector's autocorrelation gives - that one's own lag resolution
 * (`sampleRate / lag`, uninterpolated) is coarser than the 5-cent bounds
 * SILK's own tuning tests carry at its voices' low roots (SilkTest first
 * measured this directly: `PluckSpectra.peakHz`'s plain narrow Goertzel
 * scan was a full-blown octave-of-cents short at OUD's 65 Hz root, its
 * bin width there wider than the ±5-cent bound being tested).
 *
 * Promoted out of `SilkTest` (its original home) once `StringsTest` needed
 * the identical measurement for `Strings.course`'s own tuning claims -
 * shared rather than reimplemented a second time.
 */
internal object FineTuning {

    fun cents(measured: Double, want: Double) = 1200.0 * ln(measured / want) / ln(2.0)

    /**
     * The loudest spectral peak within one semitone of [wantHz], read from
     * [fromSec] over [bodySeconds] and zero-padded to a large FFT with
     * parabolic interpolation.
     */
    fun measuredHz(snip: Snip, wantHz: Float, fromSec: Float = 0.05f, bodySeconds: Float = 0.25f): Double {
        val rate = snip.sampleRate
        val from = (fromSec * rate).toInt().coerceIn(0, snip.samples.size)
        val bodyLen = minOf(snip.samples.size - from, (bodySeconds * rate).toInt())
        require(bodyLen > 8) { "measuredHz needs samples past $fromSec s (buffer is ${snip.samples.size} samples)" }
        var n = 1
        while (n < 65536) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until bodyLen) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (bodyLen - 1)).toFloat()
            re[i] = snip.samples[from + i] * w
        }
        Fft.forward(re, im)
        val mag = DoubleArray(n / 2) { hypot(re[it].toDouble(), im[it].toDouble()) }
        val binHz = rate.toDouble() / n
        val radiusBins = maxOf(1, (wantHz * 0.059 / binHz).toInt())
        val centerBin = (wantHz / binHz).toInt()
        var bestBin = centerBin
        var bestMag = -1.0
        for (b in maxOf(1, centerBin - radiusBins)..minOf(mag.size - 2, centerBin + radiusBins)) {
            if (mag[b] > bestMag) {
                bestMag = mag[b]
                bestBin = b
            }
        }
        val a = mag[bestBin - 1]
        val b2 = mag[bestBin]
        val c = mag[bestBin + 1]
        val denom = a - 2.0 * b2 + c
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        return (bestBin + delta) * binHz
    }

    /**
     * [measuredHz] directly on a raw sample buffer at [rate] - for callers
     * (like `StringsTest`) that render with [Strings]' own functions rather
     * than a full engine's [Snip]-returning `render`.
     */
    fun measuredHz(samples: FloatArray, rate: Int, wantHz: Float, fromSec: Float = 0.05f, bodySeconds: Float = 0.25f): Double =
        measuredHz(Snip(samples, channels = 1, sampleRate = rate), wantHz, fromSec, bodySeconds)
}
