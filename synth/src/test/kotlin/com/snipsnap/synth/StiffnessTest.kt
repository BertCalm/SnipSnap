package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertTrue

class StiffnessTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    /**
     * The tenth partial's frequency over ten times the fundamental, at C#4
     * with DOUBLE 0. spanFraction is 0.05, not the brief's 0.08: at 0.08 the
     * ±8 % window reaches down to the 9.2nd partial, and the ninth - which
     * disperses too, at ~0.81x the tenth's shift by the n² law - enters the
     * window before the tenth reaches 3 % and wins the Goertzel argmax
     * outright (measured ~4x louder: mag 88.9 vs 24.2 at stiffness -0.95).
     * The tenth partial's own true position was confirmed by FFT at that
     * coefficient (2855.84 Hz, 2.73 % sharp) - the window, not the physics,
     * was the obstacle. 0.05 narrows to the 9.5th-10.5th partial, which
     * excludes the ninth until the tenth is ~6.9 % sharp - comfortably past
     * both asserted windows below - without touching either bound.
     */
    private fun tenthPartialRatio(stiffness: Float): Double {
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        // jawariOverride = 0f: this probe isolates the stiffness allpass
        // alone. Any jawari nonlinearity active in the same loop risks
        // brightening this test's DAMP 0.2 render enough to let the ninth
        // partial win the tenth-partial search below (measured against an
        // in-loop bridge candidate tried 2026-09-27, since reverted - see
        // the spec's "The jawari"); the combination with the shipped jawari
        // on is guarded separately by `the high stiffness candidate with
        // the bridge on...` below.
        val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "DAMP" to 0.2f), rate, stiffnessOverride = stiffness, jawariOverride = 0f)
        val tenth = PluckSpectra.peakHz(raw, rate, 10f * f0, spanFraction = 0.05)
        return tenth / (10.0 * f0)
    }

    @Test
    fun `the probe - sharper as the coefficient falls, and the two candidates bracket one and three percent`() {
        // Printed for the plan's Step 6, asserted so the test says something:
        // more negative coefficients must stretch the partials more. The
        // brief's list (0 to -0.50) never leaves 0.00 % sharp: at rate
        // 176400 the tenth partial sits at only ~3.2 % of Nyquist, where a
        // first-order allpass's phase curve is still flat unless its pole
        // (at z = -stiffness) sits within ~0.05 of the unit circle - so the
        // list is extended toward -1 to actually bracket the two windows
        // (the window is the rule, the list is not).
        val coefficients = listOf(
            0f, -0.05f, -0.10f, -0.15f, -0.20f, -0.30f, -0.40f, -0.50f,
            -0.70f, -0.80f, -0.85f, -0.88f, -0.90f, -0.92f, -0.94f, -0.95f, -0.952f,
            -0.953f, -0.955f, -0.958f, -0.96f,
        )
        var last = 0.0
        for (c in coefficients) {
            val ratio = tenthPartialRatio(c)
            println("stiffness $c: tenth partial ${"%.4f".format(ratio)} of harmonic (${"%.2f".format((ratio - 1.0) * 100)} % sharp)")
            assertTrue(ratio >= last - 0.002, "stiffness $c is not sharper than the one before it ($ratio < $last)")
            last = ratio
        }
        val low = tenthPartialRatio(Pluck.SITAR_STIFFNESS_LOW)
        val high = tenthPartialRatio(Pluck.SITAR_STIFFNESS_HIGH)
        assertTrue(low in 1.005..1.02, "the low candidate should put the tenth partial about 1 % sharp, got $low")
        assertTrue(high in 1.02..1.045, "the high candidate should put the tenth partial about 3 % sharp, got $high")
    }

    @Test
    fun `stiffness zero leaves the tenth partial on the harmonic`() {
        val ratio = tenthPartialRatio(0f)
        assertTrue(kotlin.math.abs(ratio - 1.0) <= 0.003, "with no stiffness the tenth partial should sit within 0.3 % of harmonic, got $ratio")
    }

    @Test
    fun `stiffness does not move the fundamental`() {
        // The allpass's delay at the fundamental is in the tuning budget;
        // TuningAccuracyTest sweeps every note at the shipped stiffness, and
        // this pins the high candidate at three notes so the gate can choose it.
        // This test isolates the allpass: the shipped combination of the low
        // candidate and the jawari is proven by TuningAccuracyTest's default sweep.
        for (semi in listOf(0, 12, 24)) {
            val f0 = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to semi / 24f, "DOUBLE" to 0f), rate, stiffnessOverride = Pluck.SITAR_STIFFNESS_HIGH, jawariOverride = 0f)
            val measured = PluckSpectra.peakHz(raw, rate, f0, spanFraction = 0.03)
            val cents = 1200.0 * kotlin.math.ln(measured / f0) / kotlin.math.ln(2.0)
            assertTrue(kotlin.math.abs(cents) <= 5.0, "SITAR semitone $semi at the high stiffness is $cents cents off")
        }
    }

    @Test
    fun `the high stiffness candidate with the bridge on stays inside a quarter tone, and names its pull`() {
        // What the gate can choose: HIGH stiffness with the shipped jawari.
        // The isolated test above proves the allpass alone; this measures
        // the combination, so the pull is a number and not a comment.
        for (semi in listOf(0, 6, 12, 18, 24)) {
            val f0 = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to semi / 24f, "DOUBLE" to 0f), rate, stiffnessOverride = Pluck.SITAR_STIFFNESS_HIGH)
            val measured = PluckSpectra.peakHz(raw, rate, f0, spanFraction = 0.03)
            val cents = 1200.0 * kotlin.math.ln(measured / f0) / kotlin.math.ln(2.0)
            if (kotlin.math.abs(cents) > 5.0) println("SITAR semitone $semi at the high stiffness with the jawari on: $cents cents")
            assertTrue(kotlin.math.abs(cents) <= 50.0, "SITAR semitone $semi at the high stiffness with the jawari on is $cents cents off, past the quarter tone")
        }
    }
}
