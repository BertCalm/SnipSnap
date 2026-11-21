package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BOROUGH's stand-ins for scipy, against numbers scipy 1.18 printed for the same signal
 * (x[i] = sin(0.37 i) + 0.5 cos(0.00011 i^2) + 0.25 sin(2.9 i), 1003 samples): `resample_poly(x, 1, 4)`,
 * `resample(x, num)` shrinking and growing, odd and even, and `abs(hilbert(x))` at odd and even length.
 */
class BoroughDspTest {
    private val x = DoubleArray(1003) { sin(0.37 * it) + 0.5 * cos(0.00011 * it * it) + 0.25 * sin(2.9 * it) }

    private fun near(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) <= 1e-9 * (1 + abs(expected)), "$what: scipy $expected, here $actual")

    @Test fun `the way down is scipy's resample_poly filter itself`() {
        val d = BoroughDsp.down4(x)
        assertEquals(251, d.size)
        for ((i, v) in listOf(0 to 0.4726734759177859, 1 to 1.5135325478051587, 50 to -1.1403287133475744, 250 to -1.0831232213955648)) near(v, d[i], "down4[$i]")
        near(159.92177750495478, d.sumOf { it * it }, "energy")
    }

    @Test fun `the stack resample is scipy's Fourier resample`() {
        val cases = mapOf(
            777 to (listOf(0.48372276902761624, 0.9569177450977059, 0.34282097653309457, -0.53028543026949) to 494.4956683119871),
            1301 to (listOf(0.5000000000000002, 0.9942091536565579, -0.47749441458905273, -0.29295944711868094) to 868.5648448127852),
            500 to (listOf(0.39683358505810484, 1.2021228773436161, 0.27192886908754027, -0.8416179831885058) to 318.1933962559318),
            1004 to (listOf(0.5000000000000006, 0.9218481058095619, 0.7076775617185763, -0.36705730607468284) to 670.2837080645935),
        )
        for ((num, want) in cases) {
            val r = BoroughDsp.resample(x, num)
            assertEquals(num, r.size)
            for ((j, i) in listOf(0, 1, 123, num - 1).withIndex()) near(want.first[j], r[i], "resample $num [$i]")
            near(want.second, r.sumOf { it * it }, "resample $num energy")
        }
    }

    @Test fun `the onset envelope is scipy's hilbert`() {
        val odd = BoroughDsp.hilbertEnvelope(x.copyOf(1001))
        for ((i, v) in listOf(0 to 2.741107446881955, 7 to 1.355732950091326, 500 to 1.2745300239364608, 1000 to 2.3404359564667288)) near(v, odd[i], "odd[$i]")
        near(1088.4846273642365, odd.sum(), "odd sum")
        val even = BoroughDsp.hilbertEnvelope(x.copyOf(1000))
        for ((i, v) in listOf(0 to 2.4755455553214927, 7 to 1.345140582462248, 500 to 1.2751161421307335, 999 to 2.595004069173411)) near(v, even[i], "even[$i]")
        near(1086.7012905174092, even.sum(), "even sum")
    }
}
