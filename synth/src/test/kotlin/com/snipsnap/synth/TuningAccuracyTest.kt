package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLUCK lands within five cents across its whole TUNE range, every voice,
 * every semitone. PLUCK only - this class name used to read as a
 * fleet-wide guarantee it never measured; the other four melodic engines
 * have no gate here and no claim is made about them.
 *
 * PLUCK's integer delay line used to render off-pitch by an amount that
 * depended on the fractional remainder at each frequency (see [Pluck]'s
 * `ks()` doc for the two separate causes) - a pentatonic run of pads was
 * out of tune with itself, not merely transposed.
 *
 * This measures the fix with a real spectrum ([Fft]), not autocorrelation:
 * [com.snipsnap.audio.Pitch.detect]'s single-window autocorrelation locked
 * onto HARP's own 2nd harmonic at TUNE semitone 20 (it reported 1047 Hz;
 * the true fundamental, confirmed by FFT, was 523.93 Hz - 0.28 cents off).
 * That is exactly the "autocorrelation cannot distinguish a real
 * subharmonic from a detector octave error" trap - measured directly, not
 * assumed - so it is disqualified as this gate's instrument in favor of a
 * windowed FFT peak search near the note we already know we asked for.
 */
class TuningAccuracyTest {

    private fun cents(a: Double, b: Double) = 1200.0 * (ln(a / b) / ln(2.0))

    /**
     * The loudest spectral peak within one semitone of [wantHz] - wide
     * enough to have caught the old truncation error (worth up to ~21
     * cents), narrow enough that it can never lock onto a harmonic (the
     * nearest one is 12 semitones away).
     */
    private fun measuredHz(snip: Snip, wantHz: Float): Double {
        val rate = snip.sampleRate
        val from = (0.05f * rate).toInt()
        val bodyLen = minOf(snip.samples.size - from, (0.25f * rate).toInt())
        var n = 1
        while (n < 65536) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        // Window the body we actually have, then zero-pad to n for
        // resolution - windowing across the full padded length would taper
        // against silence instead of the signal's own edges.
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
        // Parabolic interpolation around the peak bin (Smith, DSP guide ch.
        // 9): binHz alone (0.67Hz here) is far coarser than the ±5-cent
        // gate at these frequencies.
        val a = mag[bestBin - 1]
        val b2 = mag[bestBin]
        val c = mag[bestBin + 1]
        val denom = a - 2.0 * b2 + c
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        return (bestBin + delta) * binHz
    }

    @Test
    fun `every Pluck semitone lands within five cents`() {
        for (voice in PluckVoice.entries) {
            for (semi in 0..Pluck.TUNE_SEMITONES) {
                val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
                // DOUBLE off: every voice defaults it nonzero, and it's a
                // deliberate second, detuned string (a chorus effect) - the
                // gate is about the delay line's own tuning, not the
                // chorus spread on top of it.
                val snip = Pluck.render(voice, mapOf("TUNE" to macro, "DOUBLE" to 0f))
                val want = Pluck.frequencyFor(voice, semi)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                assertTrue(err <= 5.0, "$voice semitone $semi is $err cents off (want $want, got $measured)")
            }
        }
    }
}
