package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Independent checks of the new spring law and its actual simultaneous chain integration. */
class ScannedChainSpringTest {
    @Test
    fun `positive potential differentiates to the spring force at several reference scales`() {
        val k = 2.7
        for (g in listOf(0.0, .3, 3.0)) for (reference in listOf(0.0, 1e-6, .003, .1)) {
            val scale = max(reference, 1e-5)
            for (multiple in listOf(-10.0, -1.732, -.1, -.001, 0.0, .001, .1, 1.732, 10.0)) {
                val r = scale * multiple
                val h = scale * 1e-5
                val potential = ScannedChainHeadroom.potential(k, g, reference, r)
                val gradient = (ScannedChainHeadroom.potential(k, g, reference, r + h)
                    - ScannedChainHeadroom.potential(k, g, reference, r - h)) / (2 * h)
                val force = ScannedChainHeadroom.spring(k, g, reference, r)
                assertTrue(potential >= 0 && potential.isFinite())
                assertTrue(abs(gradient - force) <= 2e-7 * k * scale * max(1.0, abs(multiple)),
                    "potential gradient $gradient versus force $force at g=$g reference=$reference r=$r")
            }
        }
    }

    @Test
    fun `spring tangent is positive and remains below the global stiffness bound`() {
        val k = 2.7
        for (g in listOf(0.0, .3, 3.0)) for (reference in listOf(0.0, 1e-6, .003, .1)) {
            val scale = max(reference, 1e-5)
            for (multiple in listOf(-100.0, -10.0, -1.7320508, -.1, 0.0, .1, 1.7320508, 10.0, 100.0)) {
                val r = scale * multiple
                val h = scale * 1e-5
                val slope = (ScannedChainHeadroom.spring(k, g, reference, r + h)
                    - ScannedChainHeadroom.spring(k, g, reference, r - h)) / (2 * h)
                assertTrue(slope >= k * (1 - 1e-8))
                assertTrue(slope <= k * (1 + 1.125 * g) * (1 + 1e-8),
                    "slope $slope exceeds bound at g=$g reference=$reference r=$r")
            }
        }
    }

    @Test
    fun `actual passive chain conserves energy and accounts for exact damping losses`() {
        val shape = FloatArray(128) { i ->
            val p = 2 * PI * i / 128
            (.12 * sin(p) + .03 * sin(3 * p)).toFloat()
        }
        val base = ScannedChainModelV1.Config("Spring accounting", ScannedChainModelV1.Voice.PLUCK,
            seconds = .15f, velocity = 1f, mechanicalHz = 1.15f, motion = 1f,
            nonlinear = 8f, dampingPerSecond = 0f, drive = 0f)
        val passive = ScannedChainModelV2.render(base, shape).diagnostics
        assertTrue(passive.initialEnergy > 0)
        assertTrue(abs(passive.energyBalanceError) / passive.initialEnergy < .002,
            "undamped relative energy residual ${passive.energyBalanceError / passive.initialEnergy}")
        assertTrue(passive.dampingLoss == 0.0 && passive.driveWork == 0.0)
        val damped = ScannedChainModelV2.render(base.copy(dampingPerSecond = 1.5f), shape).diagnostics
        assertTrue(damped.dampingLoss > 0 && damped.finalEnergy < damped.initialEnergy)
        assertTrue(abs(damped.energyBalanceError) / damped.initialEnergy < .002,
            "damped relative energy residual ${damped.energyBalanceError / damped.initialEnergy}")
        assertTrue(damped.driveWork == 0.0)
    }
}
