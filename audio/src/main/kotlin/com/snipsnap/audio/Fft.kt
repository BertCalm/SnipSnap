package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radix-2 Cooley-Tukey FFT.
 *
 * Small and dependency-free on purpose: the classifier needs a spectrum and
 * nothing else, and pulling a DSP library into a module that ships inside an
 * APK is a poor trade for one transform.
 */
object Fft {

    /** In-place forward transform. [re] and [im] must be the same power-of-two length. */
    fun forward(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size) { "re and im must be the same length" }
        require(n > 0 && (n and (n - 1)) == 0) { "length must be a power of two: $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe

                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm

                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Magnitude spectrum of real input, bins `0..size/2`.
     *
     * Input is Hann-windowed and zero-padded or truncated to [size]. The window
     * matters: an abrupt cut leaks broadband energy that reads as noise, which
     * would make every hit look like a hi-hat.
     */
    fun magnitudeSpectrum(samples: FloatArray, size: Int = 4096): FloatArray {
        require(size > 0 && (size and (size - 1)) == 0) { "size must be a power of two: $size" }

        val re = FloatArray(size)
        val im = FloatArray(size)
        val n = minOf(samples.size, size)

        for (i in 0 until n) {
            val window = 0.5f - 0.5f * cos(2.0 * PI * i / (size - 1)).toFloat()
            re[i] = samples[i] * window
        }

        forward(re, im)

        val bins = size / 2 + 1
        return FloatArray(bins) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** Centre frequency of spectrum bin [bin]. */
    fun binToHz(bin: Int, fftSize: Int, sampleRate: Int): Float =
        bin.toFloat() * sampleRate / fftSize

    /** Largest power of two at or below [n]; 0 for non-positive input. */
    fun floorPowerOfTwo(n: Int): Int {
        if (n <= 0) return 0
        var p = 1
        while (p shl 1 in 1..n) p = p shl 1
        return p
    }
}
