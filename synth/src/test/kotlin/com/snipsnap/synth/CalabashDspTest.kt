package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CALABASH's arithmetic against scipy's: the approved prototype is scipy, so the port's filters and resampler are
 * held to numbers scipy 1.18.1 computed (`signal.butter(..., output='sos')` then `sosfreqz`, `resample_poly`,
 * `iirpeak`). A pairing of poles into sections may differ from scipy's; the filter may not, so the responses are
 * what is compared.
 */
class CalabashDspTest {

    private fun response(sos: CalabashDsp.Sos, hz: Double, fs: Int): Double {
        val w = 2 * PI * hz / fs
        var mag = 1.0
        for (s in sos.sections) {
            // H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2) at z = e^{iw}
            val nr = s[0] + s[1] * cos(w) + s[2] * cos(2 * w)
            val ni = -s[1] * sin(w) - s[2] * sin(2 * w)
            val dr = 1 + s[3] * cos(w) + s[4] * cos(2 * w)
            val di = -s[3] * sin(w) - s[4] * sin(2 * w)
            mag *= hypot(nr, ni) / hypot(dr, di)
        }
        return mag
    }

    private val freqs = doubleArrayOf(50.0, 125.0, 1000.0, 2500.0, 5000.0, 12000.0)

    private fun check(sos: CalabashDsp.Sos, fs: Int, want: DoubleArray, label: String) {
        for ((i, f) in freqs.withIndex()) {
            val got = response(sos, f, fs)
            val tol = 1e-9 * want[i] + 1e-15
            assertTrue(abs(got - want[i]) <= tol, "$label at $f Hz: $got against scipy's ${want[i]}")
        }
    }

    @Test fun `butter matches scipy's low, high and band designs`() {
        check(CalabashDsp.butter(2, CalabashDsp.Kind.LOW, 900.0, fs = 176_400), 176_400,
            doubleArrayOf(0.999995238667, 0.999814059939, 0.629406470131, 0.128379733835, 0.0322176472685, 0.00545536523498), "low 2 900")
    }

    @Test fun `the first-order low pass, the high pass and the band passes match scipy`() {
        val cases = listOf(
            Triple(CalabashDsp.butter(1, CalabashDsp.Kind.LOW, 11000.0, fs = 176_400), 176_400,
                doubleArrayOf(0.999989932862, 0.999937085202, 0.995996406713, 0.975712837609, 0.911953788816, 0.674813499059)),
            Triple(CalabashDsp.butter(2, CalabashDsp.Kind.HIGH, 2500.0, fs = 176_400), 176_400,
                doubleArrayOf(0.0003994716547, 0.00249669718859, 0.157819486359, 0.707106781187, 0.970368460277, 0.999113166217)),
            Triple(CalabashDsp.butter(2, CalabashDsp.Kind.BAND, 2200.0, 9500.0, fs = 176_400), 176_400,
                doubleArrayOf(0.000306420708686, 0.00191751498107, 0.133843681565, 0.840169178944, 0.999930700447, 0.447094520816)),
            Triple(CalabashDsp.thirdOctave(125.0), 44_100,
                doubleArrayOf(0.000147856378168, 0.999999999998, 7.42648385758e-07, 1.73961284393e-08, 9.45917781652e-10, 1.03588283899e-11)),
            Triple(CalabashDsp.thirdOctave(40.0), 44_100,
                doubleArrayOf(0.0699458839066, 4.64415546461e-05, 7.35806571388e-09, 1.80761200634e-10, 9.89541281757e-12, 1.08565321139e-13)),
            Triple(CalabashDsp.butter(2, CalabashDsp.Kind.BAND, 1200.0, 9000.0, fs = 44_100), 44_100,
                doubleArrayOf(0.00135423577293, 0.00848171494648, 0.5398766425, 0.997530073639, 0.995812538725, 0.342635613817)),
        )
        for ((i, c) in cases.withIndex()) {
            // scipy's printed values carry 12 significant figures: hold to that.
            for ((j, f) in freqs.withIndex()) {
                val got = response(c.first, f, c.second)
                assertTrue(abs(got - c.third[j]) <= 1e-9 * c.third[j] + 1e-15, "case $i at $f Hz: $got against scipy's ${c.third[j]}")
            }
        }
    }

    @Test fun `upsample4 is resample_poly(x, 4, 1)`() {
        val taps = CalabashDsp.UP4
        assertEquals(81, taps.size)
        assertEquals(4.0, taps.sum(), 1e-12)
        assertEquals(1.000636565089112, taps[40], 1e-14)
        assertEquals(0.2965428196043752, taps[37], 1e-14)
        val x = DoubleArray(64) { sin(it * 0.3) + (0.1 * it) % 3.0 }
        val y = CalabashDsp.upsample4(x, 256)
        val want = doubleArrayOf(3.71900947310767e-18, 0.0667907333652824, 0.16298775358253, 0.277285743152411,
            0.395771981016939, 0.505701209590903, 0.602265848291751, 0.686866581171524)
        for (i in want.indices) assertEquals(want[i], y[i], 1e-12, "y[$i]")
        assertEquals(3.44018848753632, y[100], 1e-12)
        assertEquals(0.147746038903135, y[255], 1e-12)
    }

    @Test fun `iirpeak is scipy's`() {
        val (b, a) = CalabashDsp.iirpeak(2000.0, 5.0, 176_400)
        assertEquals(0.00707352221530133, b[0], 1e-15)
        assertEquals(-0.00707352221530133, b[2], 1e-15)
        assertEquals(-1.9808161407290916, a[1], 1e-14)
        assertEquals(0.9858529555693973, a[2], 1e-14)
    }

    @Test fun `gainCurve is the per-sample interpolated dB curve`() {
        val xp = doubleArrayOf(0.0, 0.01, 0.05, 0.2)
        val fp = doubleArrayOf(-3.0, 0.0, -20.0, -60.0)
        val dt = 1.3 / 44_100
        val g = CalabashDsp.gainCurve(12_000, dt, xp, fp, right = -100.0)
        for (s in g.indices step 37) {
            val want = CalabashDsp.db(CalabashDsp.interp(s * dt, xp, fp, -100.0))
            assertEquals(want, g[s], 1e-9 * want + 1e-300, "sample $s")
        }
    }

    @Test fun `conv is the linear convolution, direct and by FFT`() {
        val rng = CalabashDsp.Rng(7)
        val short = DoubleArray(5000) { if (it in 100..140) rng.normal() else 0.0 }
        val long = rng.normals(5000)
        val direct = CalabashDsp.conv(short, long, 5000)
        val dense = rng.normals(3000)
        val viaFft = CalabashDsp.conv(dense, long, 5000)
        fun naive(x: DoubleArray, h: DoubleArray, n: Int) = DoubleArray(n) { i -> var s = 0.0; for (k in 0..i) if (k < x.size && i - k < h.size) s += x[k] * h[i - k]; s }
        val a = naive(short, long, 5000); val b = naive(dense, long, 5000)
        for (i in 0 until 5000) {
            assertEquals(a[i], direct[i], 1e-9, "direct $i")
            assertEquals(b[i], viaFft[i], 1e-8, "fft $i")
        }
    }
}
