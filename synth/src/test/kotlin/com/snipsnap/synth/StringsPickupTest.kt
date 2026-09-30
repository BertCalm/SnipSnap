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
 * The "no audio change" claim is StringsTest's frozen grids, unedited.
 */
class StringsPickupTest {

    // ---------- the shared delay ----------

    @Test
    fun `the comb delay is round(position * rate over freq), at least 1`() {
        assertEquals(100, Strings.combDelay(0.5f, 100f, 20_000))
        assertEquals(10, Strings.combDelay(0.10f, 100f, 10_000))
        assertEquals(14, Strings.combDelay(0.14f, 100f, 10_000))
        assertEquals(1, Strings.combDelay(0.0001f, 100f, 10_000), "a vanishing position still combs by one sample")
        // Rounds half up like roundToInt: 0.125 * 10_000 / 100 = 12.5.
        assertEquals(13, Strings.combDelay(0.125f, 100f, 10_000))
    }

    @Test
    fun `the exciter's comb and the pickup read the same delay`() {
        // pluckExciter grows the burst by the comb's delay (n + D while under maxLen), so its
        // length pins the exciter's delay to combDelay: one quantity, one place.
        val n = 100
        val rate = 10_000
        for (position in listOf(0.085f, 0.12f, 0.3f, 0.5f)) {
            val exc = Strings.pluckExciter(n, 100f, 4_000f, position, seed = 7, rate = rate, maxLen = 10_000)
            assertEquals(n + Strings.combDelay(position, 100f, rate), exc.size, "position $position")
        }
        // position 0 is no comb at all.
        assertEquals(n, Strings.pluckExciter(n, 100f, 4_000f, 0f, seed = 7, rate = rate, maxLen = 10_000).size)
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
        // dp = 1/18, so the first spacing notch is at about h18 (1750 / 97 = 18.04 on the
        // delays as rounded). Spike, Extra C: -49.4 dB at h18 aligned, none as briefed.
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
