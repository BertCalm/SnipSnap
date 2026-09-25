package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RESIN, held (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md).
 * The envelope tests run at STACK 0.3, where the detuned square is silent:
 * its beat against the saws would swing a window's RMS and hide what is
 * being measured here. The beat has its own tests, in the loop cut.
 */
class ResinHeldTest {

    private val still = mapOf("STACK" to 0.3f, "CONTOUR" to 0f)

    private fun rms(s: FloatArray, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * RATE).toInt()
        val b = (toSec * RATE).toInt()
        var sum = 0.0
        for (i in a until b) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (b - a))
    }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    @Test
    fun `a held note rises over its ATTACK then stays flat`() {
        val s = Resin.renderHeld(ResinVoice.BRASS, still, Resin.Held(attackSeconds = 1f, seconds = 4f)).samples
        val early = rms(s, 0.20f, 0.30f)
        val full = rms(s, 2.5f, 3.5f)
        assertTrue(db(early / full) < -6.0, "a 1 s attack must still be quiet at a quarter second: ${db(early / full)} dB")
        val landed = rms(s, 1.2f, 1.7f)
        val late = rms(s, 3.0f, 3.5f)
        assertTrue(kotlin.math.abs(db(landed / late)) < 1.0, "past the attack the level must be flat: ${db(landed / late)} dB")
    }

    @Test
    fun `a held note does not decay`() {
        // DECAY 0 is the one-shot's shortest tail (0.15 s): held, it must not matter.
        val s = Resin.renderHeld(ResinVoice.BRASS, still + ("DECAY" to 0f), Resin.Held(0.01f, 6f)).samples
        val a = rms(s, 2.0f, 2.5f)
        val b = rms(s, 5.0f, 5.5f)
        assertTrue(kotlin.math.abs(b / a - 1.0) < 0.01, "held level drifted ${b / a} between 2 s and 5 s")
    }

    @Test
    fun `CREAM tops out at the threshold when held`() {
        assertEquals(4f, Resin.resonanceFor(1f, held = true))
        assertEquals(Dsp.Ladder.MAX_RESONANCE, Resin.resonanceFor(1f, held = false))
        assertEquals(0f, Resin.resonanceFor(0f, held = true))
    }

    @Test
    fun `held renders are deterministic`() {
        val held = Resin.Held(0.3f, 3f, squareRatio = 1.0 + 1.0 / 216.0)
        val a = Resin.renderHeld(ResinVoice.LEAD, emptyMap(), held).samples
        val b = Resin.renderHeld(ResinVoice.LEAD, emptyMap(), held).samples
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `Held refuses an attack outside its range`() {
        assertFailsWith<IllegalArgumentException> { Resin.Held(0f, 4f) }
        assertFailsWith<IllegalArgumentException> { Resin.Held(3f, 4f) }
        assertFailsWith<IllegalArgumentException> { Resin.Held(1f, 0.5f) }
    }
}
