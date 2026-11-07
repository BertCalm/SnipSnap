package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastMathTest {
    @Test
    fun `tanh matches StrictMath to within one double step near one`() {
        val random = java.util.Random(7)
        var worst = 0.0
        repeat(2_000_000) {
            val x = (random.nextDouble() * 2 - 1) * Math.pow(10.0, random.nextDouble() * 7 - 6) *
                if (random.nextBoolean()) 1.0 else 30.0
            worst = maxOf(worst, abs(FastMath.tanh(x) - StrictMath.tanh(x)))
        }
        assertTrue(worst <= 4.5e-16, "worst absolute error $worst")
    }

    @Test
    fun `tanh keeps sign, zero, odd symmetry and the limits`() {
        assertEquals(0.0, FastMath.tanh(0.0))
        assertEquals(-0.0, FastMath.tanh(-0.0))
        assertEquals(1.0, FastMath.tanh(1e3))
        assertEquals(-1.0, FastMath.tanh(Double.NEGATIVE_INFINITY))
        assertTrue(FastMath.tanh(Double.NaN).isNaN())
        for (x in listOf(1e-9, 1e-4, .3, 1.0, 5.0, 19.9)) assertEquals(-FastMath.tanh(x), FastMath.tanh(-x))
    }
}
