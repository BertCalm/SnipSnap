package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The scipy calls BOROUGH's prototype (`prototype/borough/`) makes that EMBER's port ([EmberDsp]) did not need,
 * written out in Double: the way down from 176.4 kHz (`resample_poly(y, 1, 4)`), the perc stacks' Fourier
 * resample (`signal.resample`), and an FFT of any length for both and for the parity measure's `hilbert`.
 * Each function names the call it stands in for and is checked against it (BoroughDspTest, numbers from scipy).
 */
internal object BoroughDsp {

    // ------------------------------------------------------------------ FFT

    /** In-place radix-2 FFT of ([re], [im]), length a power of two; [inverse] runs the conjugate transform, unscaled. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT length $n is not a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val ang = sign * 2 * PI / len
            val half = len / 2
            // Twiddles from cos/sin each step (not a running product): exact enough at 2^20 points.
            val wr = DoubleArray(half) { cos(ang * it) }
            val wi = DoubleArray(half) { sin(ang * it) }
            var s = 0
            while (s < n) {
                for (k in 0 until half) {
                    val a = s + k; val b = a + half
                    val xr = re[b] * wr[k] - im[b] * wi[k]
                    val xi = re[b] * wi[k] + im[b] * wr[k]
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                s += len
            }
            len = len shl 1
        }
    }

    private fun pow2(len: Int): Int { var s = 1; while (s < len) s = s shl 1; return s }

    /**
     * The discrete Fourier transform of any length (numpy's `fft`, or with [inverse] its unscaled conjugate), by
     * Bluestein's chirp: three power-of-two FFTs. Returns (re, im).
     */
    fun dft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        if (n == 0) return DoubleArray(0) to DoubleArray(0)
        if (n and (n - 1) == 0) { val r = re.copyOf(); val i = im.copyOf(); fft(r, i, inverse); return r to i }
        val sign = if (inverse) 1.0 else -1.0
        // chirp w_k = exp(sign i pi k^2 / n); k^2 taken mod 2n so the angle stays exact for long transforms
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        for (k in 0 until n) {
            val q = (k.toLong() * k) % (2L * n)
            val a = sign * PI * q / n
            cr[k] = cos(a); ci[k] = sin(a)
        }
        val m = pow2(2 * n - 1)
        val ar = DoubleArray(m); val ai = DoubleArray(m)
        for (k in 0 until n) { ar[k] = re[k] * cr[k] - im[k] * ci[k]; ai[k] = re[k] * ci[k] + im[k] * cr[k] }
        val br = DoubleArray(m); val bi = DoubleArray(m)
        br[0] = cr[0]; bi[0] = -ci[0]
        for (k in 1 until n) { br[k] = cr[k]; bi[k] = -ci[k]; br[m - k] = cr[k]; bi[m - k] = -ci[k] }
        fft(ar, ai); fft(br, bi)
        for (k in 0 until m) {
            val r = ar[k] * br[k] - ai[k] * bi[k]
            val i = ar[k] * bi[k] + ai[k] * br[k]
            ar[k] = r; ai[k] = i
        }
        fft(ar, ai, inverse = true)
        val outR = DoubleArray(n); val outI = DoubleArray(n)
        for (k in 0 until n) {
            val r = ar[k] / m; val i = ai[k] / m
            outR[k] = r * cr[k] - i * ci[k]; outI[k] = r * ci[k] + i * cr[k]
        }
        return outR to outI
    }

    // ------------------------------------------------------------------ rates

    /** scipy's `resample_poly(·, 4, 1)` filter, per tap: firwin(81, 1/4, kaiser 5), unit DC gain. */
    private val DOWN4: DoubleArray by lazy {
        fun i0(x: Double): Double { var sum = 1.0; var term = 1.0; var k = 1; while (term > 1e-17 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }; return sum }
        val taps = 81; val half = 40; val beta = 5.0
        val h = DoubleArray(taps) { k ->
            val x = 0.25 * (k - half)
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            sinc * i0(beta * sqrt(1 - (2.0 * k / (taps - 1) - 1).pow(2))) / i0(beta)
        }
        val sum = h.sum()
        DoubleArray(taps) { h[it] / sum }
    }

    /**
     * scipy's `resample_poly(x, 1, 4)`: 176.4 kHz down to 44.1 kHz through the same 81-tap Kaiser low-pass,
     * zero delay; ceil(n / 4) samples. out[m] = sum_j h[j] x[4m + 40 - j].
     */
    fun down4(x: DoubleArray): DoubleArray {
        val h = DOWN4
        val n = x.size
        return DoubleArray((n + 3) / 4) { m ->
            var acc = 0.0
            val c = 4 * m + 40
            val j0 = max(0, c - (n - 1)); val j1 = min(h.size - 1, c)
            for (j in j0..j1) acc += h[j] * x[c - j]
            acc
        }
    }

    /**
     * scipy's `signal.resample(x, num)` for a real signal: the Fourier method. The spectrum is cut (or padded) to
     * [num]'s band, the unpaired bin at the band edge doubled when shrinking and halved when growing, as scipy
     * does, then brought back at [num] samples.
     */
    fun resample(x: DoubleArray, num: Int): DoubleArray {
        val nx = x.size
        require(nx > 0 && num > 0)
        val (xr, xi) = dft(x, DoubleArray(nx))
        val sFac = nx.toDouble() / num
        val m = min(num, nx)
        val m2 = m / 2 + 1
        val yr = DoubleArray(num); val yi = DoubleArray(num)
        for (k in 0 until m2) { yr[k] = xr[k] / sFac; yi[k] = xi[k] / sFac }
        if (m % 2 == 0 && num != nx) { val g = if (num < nx) 2.0 else 0.5; yr[m / 2] *= g; yi[m / 2] *= g }
        // irfft(n = num): the one-sided spectrum up to num / 2, Hermitian above; DC and an even num's Nyquist read real only.
        val half = num / 2
        yi[0] = 0.0
        if (num % 2 == 0) yi[half] = 0.0
        for (k in half + 1 until num) { yr[k] = 0.0; yi[k] = 0.0 }
        for (k in 1 until (num + 1) / 2) { yr[num - k] = yr[k]; yi[num - k] = -yi[k] }
        val (outR, _) = dft(yr, yi, inverse = true)
        return DoubleArray(num) { outR[it] / num }
    }

    /** |scipy's `hilbert(x)`|: the analytic signal's envelope, at the signal's own length. */
    fun hilbertEnvelope(x: DoubleArray): DoubleArray {
        val n = x.size
        val (re, im) = dft(x, DoubleArray(n))
        for (k in 0 until n) {
            val h = when {
                k == 0 -> 1.0
                n % 2 == 0 && k == n / 2 -> 1.0
                k < (n + 1) / 2 -> 2.0
                else -> 0.0
            }
            re[k] *= h; im[k] *= h
        }
        val (ar, ai) = dft(re, im, inverse = true)
        return DoubleArray(n) { sqrt(ar[it] * ar[it] + ai[it] * ai[it]) / n }
    }
}
