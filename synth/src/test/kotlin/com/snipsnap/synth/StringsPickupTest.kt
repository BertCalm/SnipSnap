package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R0's claims (docs/superpowers/plans/2026-09-30-magnet-r0.md): the comb's delay
 * exists once, the pickup is the Jaffe-Smith comb read off the string's output,
 * and a humbucker's two combs are time-aligned so the coil spacing notches.
 * The "no audio change" claim is StringsTest's frozen grids.
 */
class StringsPickupTest {

    // ---------- the shared delay ----------

    @Test
    fun `the comb delay is the rounded position times rate over freq, at least 1`() {
        assertEquals(100, Strings.combDelay(0.5f, 100f, 20_000))
        assertEquals(10, Strings.combDelay(0.10f, 100f, 10_000))
        assertEquals(14, Strings.combDelay(0.14f, 100f, 10_000))
        assertEquals(1, Strings.combDelay(0.0001f, 100f, 10_000), "a vanishing position still combs by one sample")
        // Rounds half up like roundToInt: 0.125 * 10_000 / 100 = 12.5.
        assertEquals(13, Strings.combDelay(0.125f, 100f, 10_000))
    }

    @Test
    fun `the exciter's comb and the pickup read the same delay`() {
        // pluckExciter grows the burst by the comb's delay (n + D while under maxLen) and
        // subtracts the burst delayed by D, so its length and its samples pin the exciter's
        // delay to combDelay: one quantity, one place. n = 90 against a period of 100 keeps the
        // loop length and the string's period apart, as in production.
        val n = 90
        val rate = 10_000
        val freq = 100f
        // position 0 is no comb at all: the raw burst itself.
        val raw = Strings.pluckExciter(n, freq, 4_000f, 0f, seed = 7, rate = rate, maxLen = 10_000)
        assertEquals(n, raw.size)
        // 0.085 is the half-way case: 8.5 rounds up to 9.
        for ((position, d) in listOf(0.123f to 12, 0.127f to 13, 0.085f to 9)) {
            assertEquals(d, Strings.combDelay(position, freq, rate), "position $position")
            val exc = Strings.pluckExciter(n, freq, 4_000f, position, seed = 7, rate = rate, maxLen = 10_000)
            assertEquals(n + d, exc.size, "position $position")
            for (i in 0 until n + d) {
                val want = (if (i < n) raw[i] else 0f) - (if (i - d in 0 until n) raw[i - d] else 0f)
                assertEquals(want, exc[i], "position $position, sample $i")
            }
        }
    }

    // ---------- the pickup ----------

    @Test
    fun `one position is the weighted comb, exactly`() {
        val y = FloatArray(400) { (sin(it * 0.37) * 0.5 + cos(it * 0.011)).toFloat() }
        val d = Strings.combDelay(0.2f, 100f, 10_000) // 20
        val out = Strings.pickup(y, floatArrayOf(0.2f), floatArrayOf(0.75f), 100f, 10_000)
        assertEquals(y.size, out.size)
        for (n in y.indices) {
            val want = 0.75f * (y[n] - (if (n - d >= 0) y[n - d] else 0f))
            assertEquals(want, out[n], "sample $n")
        }
    }

    @Test
    fun `a signal shorter than the delay does not read out of range`() {
        val y = floatArrayOf(1f, 2f, 3f)
        val out = Strings.pickup(y, floatArrayOf(0.5f), floatArrayOf(1f), 100f, 10_000) // D = 50
        assertContentEquals(y, out, "before the first tap the comb passes the string through")
    }

    @Test
    fun `two positions are centred on the longer comb - the impulse response's taps`() {
        // rate 10 kHz, 100 Hz: period 100 samples, so 0.10 -> D 10 and 0.14 -> D 14.
        // lead1 = (14 - 10) / 2 = 2, lead2 = 0 (the longest comb is never shifted):
        // h[n] = w1 (d[n-2] - d[n-12]) + w2 (d[n] - d[n-14]).
        val y = FloatArray(40).also { it[0] = 1f }
        val out = Strings.pickup(y, floatArrayOf(0.10f, 0.14f), floatArrayOf(0.5f, 0.25f), 100f, 10_000)
        val want = FloatArray(40)
        want[2] += 0.5f; want[12] -= 0.5f
        want[0] += 0.25f; want[14] -= 0.25f
        assertContentEquals(want, out)
    }

    @Test
    fun `an odd delay difference rounds the lead down`() {
        // rate 10 kHz, 100 Hz: 0.10 -> D 10 and 0.13 -> D 13.
        // lead1 = (13 - 10) / 2 = 1 (integer division, not rounded up), lead2 = 0:
        // h[n] = w1 (d[n-1] - d[n-11]) + w2 (d[n] - d[n-13]).
        assertEquals(10, Strings.combDelay(0.10f, 100f, 10_000))
        assertEquals(13, Strings.combDelay(0.13f, 100f, 10_000))
        val y = FloatArray(40).also { it[0] = 1f }
        val out = Strings.pickup(y, floatArrayOf(0.10f, 0.13f), floatArrayOf(0.5f, 0.25f), 100f, 10_000)
        val want = FloatArray(40)
        want[1] += 0.5f; want[11] -= 0.5f
        want[0] += 0.25f; want[13] -= 0.25f
        assertContentEquals(want, out)
    }

    @Test
    fun `an empty signal gives an empty result`() {
        val out = Strings.pickup(FloatArray(0), floatArrayOf(0.1f, 0.14f), floatArrayOf(1f, 1f), 100f, 10_000)
        assertContentEquals(FloatArray(0), out)
    }

    @Test
    fun `two taps that round to the same delay are not shifted`() {
        val y = FloatArray(60).also { it[0] = 1f }
        // 0.100 and 0.104 both give D 10 at period 100... check the premise, then the claim.
        assertEquals(Strings.combDelay(0.100f, 100f, 10_000), Strings.combDelay(0.104f, 100f, 10_000))
        val out = Strings.pickup(y, floatArrayOf(0.100f, 0.104f), floatArrayOf(1f, 1f), 100f, 10_000)
        assertEquals(2f, out[0]); assertEquals(-2f, out[10])
        for (n in out.indices) if (n != 0 && n != 10) assertEquals(0f, out[n], "sample $n")
    }

    @Test
    fun `bad arguments are refused`() {
        val y = FloatArray(10)
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(), floatArrayOf(), 100f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0.1f, 0.2f), floatArrayOf(1f), 100f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0.1f), floatArrayOf(1f), 0f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0f), floatArrayOf(1f), 100f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0.1f), floatArrayOf(1f), 100f, 0) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0.1f), floatArrayOf(1f), Float.POSITIVE_INFINITY, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(Float.NaN), floatArrayOf(1f), 100f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1f), 100f, 10_000) }
        assertFailsWith<IllegalArgumentException> { Strings.pickup(y, floatArrayOf(0.1f), floatArrayOf(Float.NaN), 100f, 10_000) }
    }

    // ---------- the humbucker's coil-spacing notch ----------

    /** Amplitude of [y]'s component at [hz], by correlation over whole periods of the fundamental. */
    private fun amplitudeAt(y: FloatArray, hz: Double, rate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in y.indices) {
            val ph = 2 * PI * hz * i / rate
            re += y[i] * cos(ph)
            im += y[i] * sin(ph)
        }
        return 2 * sqrt(re * re + im * im) / y.size
    }

    private fun db(a: Double, b: Double) = 20 * log10(a.coerceAtLeast(1e-12) / b.coerceAtLeast(1e-12))

    @Test
    fun `an aligned pair notches at k = 1 over dp and an unaligned sum does not`() {
        // rate 176.4 kHz, f0 100.8 Hz: period exactly 1750 samples, 40 harmonics at equal level,
        // a whole number of periods (so the correlation is exact). CHUG's geometry: p = 0.12,
        // dp = 1/18, so the first spacing notch is at period / (D2 - D1) = 1750 / 97 = 18.04 on
        // the delays as rounded (210 and 307), close to h18. The difference is odd, so the
        // integer lead leaves the centres half a sample apart (a phase error of pi k f0 / rate,
        // 0.032 rad at h18): the aligned pair reads 32.2 dB under the single coil, where ideal
        // fractional centring would read about 40.7 dB by an independent model, and the unaligned
        // sum reads 3.0 dB over it. The Phase-0 spike read 34.1 dB under the single coil at h18
        // with an even difference of 80 samples (Extra C, "aligned d meas";
        // docs/superpowers/plans/2026-09-29-magnet-phase-0-spike.md).
        val rate = 176_400
        val f0 = 100.8f
        val periods = 40
        val y = FloatArray(1750 * periods) { i ->
            var s = 0.0
            for (k in 1..40) s += sin(2 * PI * k * f0 * i / rate)
            (s / 40).toFloat()
        }
        val p1 = 0.12f
        val p2 = p1 + 1f / 18f
        val w = 0.707f
        val single = Strings.pickup(y, floatArrayOf(p1), floatArrayOf(1f), f0, rate)
        val aligned = Strings.pickup(y, floatArrayOf(p1, p2), floatArrayOf(w, w), f0, rate)
        // The pair as briefed: each comb unshifted, summed. Built by hand here, not by the helper.
        val d1 = Strings.combDelay(p1, f0, rate)
        val d2 = Strings.combDelay(p2, f0, rate)
        val naive = FloatArray(y.size) { n ->
            w * ((y[n] - (if (n - d1 >= 0) y[n - d1] else 0f)) + (y[n] - (if (n - d2 >= 0) y[n - d2] else 0f)))
        }
        // Skip the first 10 periods: the combs' start-up transient.
        fun at(x: FloatArray, k: Int) = amplitudeAt(x.copyOfRange(1750 * 10, x.size), k * f0.toDouble(), rate)
        val gapAligned = db(at(single, 18), at(aligned, 18))
        val gapNaive = db(at(single, 18), at(naive, 18))
        println("R0 humbucker notch at h18: aligned ${"%.1f".format(gapAligned)} dB under the single coil, unaligned ${"%.1f".format(gapNaive)} dB")
        assertTrue(gapAligned >= 20.0, "the aligned pair is only $gapAligned dB under the single coil at h18")
        assertTrue(gapNaive < 20.0, "the unaligned sum notches too ($gapNaive dB): this test cannot tell alignment from none")
    }
}
