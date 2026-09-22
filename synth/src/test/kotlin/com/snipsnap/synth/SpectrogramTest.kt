package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpectrogramTest {

    private val rate = Dsp.RATE

    /**
     * Single-bin Goertzel energy at [hz] — a light local probe, since
     * `Pghi`'s own test-only Goertzel helper is `:audio`-internal and
     * not visible from `:synth`'s test source.
     */
    private fun energyAt(samples: FloatArray, hz: Float): Double {
        val n = samples.size
        val k = (0.5 + n * hz / rate).toInt()
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var q1 = 0.0
        var q2 = 0.0
        for (s in samples) {
            val q0 = coeff * q1 - q2 + s
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - q1 * q2 * coeff
    }

    /**
     * The row a given [hz] paints to on [Spectrogram]'s own log scale
     * (~40 Hz at the bottom, Nyquist at the top) — mirrored here so a
     * test can choose a row and know in advance what it should sound
     * like, without reaching into `Spectrogram`'s own private mapping.
     */
    private fun rowFor(hz: Float, height: Int, sampleRate: Int = rate): Int {
        val minHz = 40f
        val nyquist = sampleRate / 2f
        val highFraction = ln(hz / minHz) / ln(nyquist / minHz)
        return ((1f - highFraction) * (height - 1)).toInt().coerceIn(0, height - 1)
    }

    @Test
    fun `a bright horizontal line inverts to a tone near that row's own frequency`() {
        val width = 64
        val height = 64
        val targetHz = 1000f
        val row = rowFor(targetHz, height)
        val photo = Photo.grey(width, height) { _, y -> if (y == row) 1f else 0f }
        val snip = Spectrogram.read(photo, seconds = 1f, sampleRate = rate)
        val onTarget = energyAt(snip.samples, targetHz)
        val faraway = energyAt(snip.samples, targetHz * 3f)
        assertTrue(onTarget > faraway * 5, "energy at $targetHz Hz ($onTarget) should dominate a control far away ($faraway)")
    }

    @Test
    fun `a black photo is silence`() {
        val photo = Photo.grey(32, 32) { _, _ -> 0f }
        val snip = Spectrogram.read(photo, seconds = 0.5f, sampleRate = rate)
        assertTrue(snip.samples.all { it == 0f }, "a black photo has nothing to read")
    }

    @Test
    fun `a bright vertical line is a click near that column's own time`() {
        val width = 40
        val height = 40
        val column = width / 2
        val photo = Photo.grey(width, height) { x, _ -> if (x == column) 1f else 0f }
        val snip = Spectrogram.read(photo, seconds = 1f, sampleRate = rate)
        val n = snip.samples.size
        val third = n / 3
        fun energy(range: IntRange): Double {
            var sum = 0.0
            for (i in range) sum += snip.samples[i].toDouble() * snip.samples[i]
            return sum
        }
        val early = energy(0 until third)
        val mid = energy(third until 2 * third)
        val late = energy(2 * third until n)
        assertTrue(mid > early && mid > late, "a vertical line's click should land near the middle third: early=$early mid=$mid late=$late")
    }

    @Test
    fun `read refuses a non-positive length`() {
        val photo = Photo.grey(8, 8) { _, _ -> 0.5f }
        assertFailsWith<IllegalArgumentException> { Spectrogram.read(photo, seconds = 0f) }
        assertFailsWith<IllegalArgumentException> { Spectrogram.read(photo, seconds = -1f) }
    }
}
