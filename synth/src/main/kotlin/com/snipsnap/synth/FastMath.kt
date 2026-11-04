package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.withSign

/**
 * Faster stand-ins for `kotlin.math` functions that the JVM computes slowly. This is not an
 * approximation layer: each function returns the same value to within a stated bound far below
 * anything a float sample can carry.
 *
 * Why it exists: on the desktop JVM, `kotlin.math.tanh` runs the pure-Java `StrictMath`
 * routine (about 22 ns a call on the cloud machine), while `Math.exp` is a CPU intrinsic.
 * Engines that saturate every spring or stage every sample at 176.4 kHz spent up to half their
 * render inside `tanh` (COROLLA 48% in the 2026-11-01 profile). Android's ART calls the
 * platform maths library for both, so the gain there is unmeasured and may be small.
 */
internal object FastMath {
    /**
     * Hyperbolic tangent through one `exp`. Measured against `StrictMath.tanh` over 20 million
     * arguments spread across 1e-5 to 30: largest absolute error 2.2e-16 (one double step
     * near 1), about 2.8 times faster on JDK 21. Below 1e-4 the cubic Taylor term is exact to
     * double precision; past 20, tanh is 1 to double precision.
     */
    fun tanh(x: Double): Double {
        val a = abs(x)
        val r = when {
            a < 1e-4 -> a - a * a * a / 3
            a > 20.0 -> 1.0
            else -> 1 - 2 / (exp(2 * a) + 1)
        }
        return r.withSign(x)
    }
}
