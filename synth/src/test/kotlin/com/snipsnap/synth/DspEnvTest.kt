package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Dsp.Env] — U5 of `docs/SYNTH_UPGRADE.md`: the shared attack/hold/
 * two-stage-decay primitive that replaces the ad-hoc `attack * envAt(t, t60)`
 * every one-shot engine hand-rolled.
 */
class DspEnvTest {

    @Test
    fun `single-stage mode matches the old attack-ramp times envAt shape exactly`() {
        val attackSeconds = 0.004f
        val t60 = 0.5f
        val env = Dsp.Env(attackSeconds = attackSeconds, decay2T60 = t60)
        for (t in listOf(0f, 0.001f, 0.004f, 0.01f, 0.1f, 0.5f, 1f)) {
            val attack = (t / attackSeconds).coerceAtMost(1f)
            val expected = attack * Dsp.envAt(t, t60)
            assertEquals(expected, env.at(t), 1e-6f, "t=$t")
        }
    }

    @Test
    fun `zero attack seconds means full level from t=0`() {
        val env = Dsp.Env(attackSeconds = 0f, decay2T60 = 0.3f)
        assertEquals(Dsp.envAt(0f, 0.3f), env.at(0f), 1e-6f)
        assertEquals(Dsp.envAt(0.2f, 0.3f), env.at(0.2f), 1e-6f)
    }

    @Test
    fun `hold keeps the decay curve from starting until holdSeconds have passed`() {
        // Hold, like the decay stages, is measured from t=0 - same as the
        // attack ramp and decay running concurrently rather than in
        // sequence (see the class doc). With holdSeconds > attackSeconds,
        // that still reads as a normal attack-hold-decay shape overall.
        val env = Dsp.Env(attackSeconds = 0.01f, decay2T60 = 0.3f, holdSeconds = 0.05f)
        assertEquals(1f, env.at(0.01f), 1e-6f, "past the attack ramp, still inside hold")
        assertEquals(1f, env.at(0.05f), 1e-6f, "hold's own boundary is inclusive")
        assertTrue(env.at(0.06f) < 1f, "decay should have started once hold elapsed")
    }

    @Test
    fun `two-stage decay hits decay1Level exactly at the stage boundary`() {
        val env = Dsp.Env(
            attackSeconds = 0f,
            decay2T60 = 0.4f,
            decay1Seconds = 0.02f,
            decay1Level = 0.6f,
        )
        assertEquals(1f, env.at(0f), 1e-6f)
        assertEquals(0.6f, env.at(0.02f), 1e-5f, "stage 1 must land exactly on decay1Level")
        assertEquals(0.6f * Dsp.envAt(0.001f, 0.4f), env.at(0.021f), 1e-6f, "stage 2 continues the envAt tail from decay1Level, not from 1")
        assertTrue(env.at(0.5f) < 0.6f, "stage 2's envAt tail keeps falling")
    }

    @Test
    fun `never produces a negative, infinite or NaN level`() {
        val env = Dsp.Env(attackSeconds = 0.005f, decay2T60 = 0.2f, holdSeconds = 0.02f, decay1Seconds = 0.03f, decay1Level = 0.4f)
        for (i in 0..2000) {
            val t = i * 0.001f
            val v = env.at(t)
            assertTrue(v.isFinite() && v >= 0f, "t=$t produced $v")
        }
    }
}
