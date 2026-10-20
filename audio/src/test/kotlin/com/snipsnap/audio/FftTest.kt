package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun `dc lands entirely in bin zero`() {
        val re = FloatArray(8) { 1f }
        val im = FloatArray(8)
        Fft.forward(re, im)

        assertEquals(8f, re[0], 1e-4f)
        for (i in 1 until 8) {
            assertTrue(abs(re[i]) < 1e-3f, "bin $i should be empty, got ${re[i]}")
        }
    }

    @Test
    fun `a sine lands in its own bin`() {
        val n = 64
        val bin = 5
        val samples = FloatArray(n) { sin(2.0 * PI * bin * it / n).toFloat() }
        val re = samples.copyOf()
        val im = FloatArray(n)
        Fft.forward(re, im)

        val magnitudes = FloatArray(n / 2 + 1) {
            kotlin.math.sqrt(re[it] * re[it] + im[it] * im[it])
        }
        val loudest = magnitudes.indices.maxBy { magnitudes[it] }
        assertEquals(bin, loudest)
    }

    @Test
    fun `preserves energy`() {
        // Parseval's theorem: an FFT that loses or invents energy is broken in a
        // way that a single-bin test can miss.
        val n = 32
        val samples = FloatArray(n) { sin(2.0 * PI * 3 * it / n).toFloat() + 0.3f }
        val timeEnergy = samples.sumOf { (it * it).toDouble() }

        val re = samples.copyOf()
        val im = FloatArray(n)
        Fft.forward(re, im)
        val freqEnergy = (0 until n).sumOf { (re[it] * re[it] + im[it] * im[it]).toDouble() } / n

        assertEquals(timeEnergy, freqEnergy, timeEnergy * 1e-3)
    }

    @Test
    fun `magnitude spectrum finds a tone at the right frequency`() {
        val rate = 44_100
        val size = 4096
        val hz = 1000.0
        val samples = FloatArray(size) { sin(2.0 * PI * hz * it / rate).toFloat() }

        val spectrum = Fft.magnitudeSpectrum(samples, size)
        val loudest = spectrum.indices.maxBy { spectrum[it] }
        val foundHz = Fft.binToHz(loudest, size, rate)

        assertTrue(abs(foundHz - hz) < 20f, "expected ~$hz Hz, got $foundHz Hz")
    }

    @Test
    fun `magnitude spectrum has the expected bin count`() {
        assertEquals(4096 / 2 + 1, Fft.magnitudeSpectrum(FloatArray(4096), 4096).size)
    }

    @Test
    fun `short input is zero padded`() {
        val spectrum = Fft.magnitudeSpectrum(FloatArray(100) { 1f }, 1024)
        assertEquals(1024 / 2 + 1, spectrum.size)
        assertTrue(spectrum[0] > 0f)
    }

    @Test
    fun `handles the degenerate single-sample case`() {
        val re = floatArrayOf(3f)
        val im = floatArrayOf(0f)
        Fft.forward(re, im)
        assertEquals(3f, re[0])
    }

    @Test
    fun `rejects non power of two sizes`() {
        assertFailsWith<IllegalArgumentException> { Fft.forward(FloatArray(6), FloatArray(6)) }
        assertFailsWith<IllegalArgumentException> { Fft.forward(FloatArray(8), FloatArray(4)) }
        assertFailsWith<IllegalArgumentException> { Fft.magnitudeSpectrum(FloatArray(10), 100) }
    }

    @Test
    fun `bin frequencies scale with the rate`() {
        assertEquals(0f, Fft.binToHz(0, 4096, 44_100))
        assertEquals(44_100f / 2, Fft.binToHz(2048, 4096, 44_100), 0.1f)
    }

    @Test
    fun `floors to a power of two`() {
        assertEquals(0, Fft.floorPowerOfTwo(0))
        assertEquals(0, Fft.floorPowerOfTwo(-5))
        assertEquals(1, Fft.floorPowerOfTwo(1))
        assertEquals(4, Fft.floorPowerOfTwo(7))
        assertEquals(4096, Fft.floorPowerOfTwo(4096))
        assertEquals(4096, Fft.floorPowerOfTwo(8191))
    }

    @Test
    fun `matches a naive double DFT closely at 4096 points`() {
        // The accuracy contract the Double twiddle recurrence buys: a
        // Float recurrence measured ~2e-5 relative here against the
        // same naive DFT, before the fix.
        val n = 4096
        var seed = 42L
        fun rnd(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 40) and 0xFFFFFF) / 8388608.0 - 1.0
        }
        val x = DoubleArray(n) { rnd() }
        val re = FloatArray(n) { x[it].toFloat() }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var maxErr = 0.0
        var maxMag = 0.0
        for (k in 0 until n step 37) {
            var dr = 0.0
            var di = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                dr += x[t] * kotlin.math.cos(a)
                di += x[t] * kotlin.math.sin(a)
            }
            val err = kotlin.math.sqrt((re[k] - dr) * (re[k] - dr) + (im[k] - di) * (im[k] - di))
            if (err > maxErr) maxErr = err
            val mag = kotlin.math.sqrt(dr * dr + di * di)
            if (mag > maxMag) maxMag = mag
        }
        assertTrue(maxErr / maxMag < 1e-5, "relative DFT error ${maxErr / maxMag}")
    }

    @Test
    fun `magnitude spectrum of a single sample does not divide by zero`() {
        val spectrum = Fft.magnitudeSpectrum(floatArrayOf(0.5f), 1)
        assertEquals(1, spectrum.size)
        assertTrue(spectrum[0].isFinite() && spectrum[0] > 0f, "got ${spectrum[0]}")
    }

}
