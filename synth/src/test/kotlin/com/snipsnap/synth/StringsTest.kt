package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StringsTest {

    /**
     * The extraction's whole claim: the shared loop IS PLUCK's loop. Wider
     * than any voice reaches - roots from 55 Hz to 12 kHz, both render
     * rates, the pick at the bridge, a quarter and the centre, both ends of
     * DAMP and of the pick filter - because SILK will reach them. Every case
     * clears the loop-length floor (checked when the plan was written).
     */
    @Test
    fun `Strings pluck is the frozen PLUCK loop, sample for sample`() {
        var cases = 0
        for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
            for (freq in listOf(55f, 110f, 147f, 196f, 440f, 1000f, 3000f, 12_000f)) {
                for (damp in listOf(0f, 0.45f, 1f)) {
                    for (position in listOf(0f, 0.03f, 0.25f, 0.5f)) {
                        for (pickHz in listOf(1200f, 14_000f)) {
                            val seed = Dsp.seedFor("STRINGS", freq, damp, position)
                            val legacy = LegacyPluckLoop.ks(freq, 0.25f, damp, 4200f, pickHz, seed, rate, position)
                            val shared = Strings.pluck(freq, 0.25f, Strings.damping(damp, 4200f), pickHz, seed, rate, position)
                            assertContentEquals(legacy, shared, "rate=$rate freq=$freq damp=$damp position=$position pickHz=$pickHz")
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(384, cases)
    }

    /**
     * PLUCK Phase 3a's SITAR voice built its stiffness allpass and jawari
     * bridge limiter directly into the old `Pluck.ks`, in parallel with and
     * unaware of this Phase's own extraction; merging the two folded SITAR's
     * two stages into [Strings] as well (docs/superpowers/plans/2026-09-27-silk-phase-1a.md,
     * the merge note). This is that grid's SITAR-shaped sibling: [LegacyPluckLoop.ksWithSitar]
     * is Phase 3a's loop frozen at the merge, and [Strings.pluck] must
     * reproduce it - both of SITAR's shipped candidates
     * (`Pluck.SITAR_STIFFNESS_LOW`/`_HIGH`), its shipped jawari drive
     * (`Pluck.SITAR_JAWARI`), the root note and a spread around it, both
     * render rates, and the pick positions SITAR's own STRIKE macro reaches.
     */
    @Test
    fun `Strings pluck reproduces PLUCK Phase 3a's stiffness and jawari, sample for sample`() {
        var cases = 0
        for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
            for (freq in listOf(139f, 220f, 440f)) {
                for (stiffness in listOf(0f, Pluck.SITAR_STIFFNESS_LOW, Pluck.SITAR_STIFFNESS_HIGH)) {
                    for (jawari in listOf(0f, Pluck.SITAR_JAWARI, 0.6f)) {
                        for (position in listOf(0f, 0.3f)) {
                            val seed = Dsp.seedFor("STRINGS-SITAR", freq, stiffness, jawari, position)
                            val legacy = LegacyPluckLoop.ksWithSitar(freq, 0.5f, 0.5f, 7000f, 6000f, seed, rate, position, stiffness, jawari)
                            val shared = Strings.pluck(freq, 0.5f, Strings.damping(0.5f, 7000f), 6000f, seed, rate, position, stiffness, jawari)
                            assertContentEquals(legacy, shared, "rate=$rate freq=$freq stiffness=$stiffness jawari=$jawari position=$position")
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(108, cases)
    }

    @Test
    fun `tune fails loudly below the KS minimum, and names the cause`() {
        val e = assertFailsWith<IllegalArgumentException> { Strings.tune(70_000f, 4200f, 176_400) }
        assertTrue(e.message!!.contains("Karplus-Strong minimum"), e.message)
    }

    @Test
    fun `tune keeps the allpass stable at every fraction`() {
        for (freq in listOf(55f, 147f, 440f, 3000f, 12_000f)) {
            val t = Strings.tune(freq, 4200f, Dsp.RATE * Dsp.OVERSAMPLE)
            assertTrue(t.n >= Strings.MIN_LOOP_SAMPLES, "n=${t.n} at $freq Hz")
            assertTrue(t.a > 0f && t.a <= 1f, "a=${t.a} at $freq Hz - |a| < 1 is what keeps the allpass stable")
        }
    }

    @Test
    fun `the loop passes its input straight through until it has history`() {
        val loop = Strings.Loop(n = 8, a = 0.5f, fb = 0.99f, loopHz = 4000f, rate = Dsp.RATE)
        val input = FloatArray(9) { (it + 1).toFloat() }
        for (x in input) assertEquals(x, loop.next(x))
    }

    @Test
    fun `the exciter is zero-mean with the pick off, and the comb lengthens it`() {
        val plain = Strings.pluckExciter(n = 300, freq = 147f, pickHz = 6000f, position = 0f, seed = 7, rate = Dsp.RATE, maxLen = 10_000)
        assertEquals(300, plain.size)
        assertTrue(abs(plain.sum()) < 1e-3f, "sum=${plain.sum()}")
        val combed = Strings.pluckExciter(n = 300, freq = 147f, pickHz = 6000f, position = 0.25f, seed = 7, rate = Dsp.RATE, maxLen = 10_000)
        assertTrue(combed.size > plain.size, "the comb's delayed copy extends the exciter")
    }

    /**
     * Guards the erratum fix itself (docs/superpowers/plans/2026-09-27-silk-research.md
     * §3, Z.7 point 4): DAFx-06's own Eq. 7 prints "ln M" a second time where
     * "ln B" belongs, so a naive reading has no B in it at all. -0.4853 is
     * this test's own independently hand-computed value from the corrected
     * form (Rauhala's dissertation Eq. 3.8) at B=1e-5, M=4 - if a future
     * edit reintroduces the erratum (swaps `ln(b)` for a second `ln(count)`),
     * the coefficient stops depending on B in the right way and this drifts
     * off by far more than the tolerance below.
     */
    @Test
    fun `Dispersion forB reproduces the corrected Rauhala design, not the printed erratum`() {
        val d = Strings.Dispersion.forB(1e-5f, 4)
        assertTrue(d != null)
        assertEquals(-0.4853f, d!!.a, 0.001f, "a drift here likely means the erratum crept back in")
        assertEquals(4, d.count)
        // Doubling B must make the coefficient more negative (more stretch),
        // never the other way - the sign DAFx-06's own prototype got backwards.
        val stronger = Strings.Dispersion.forB(2e-5f, 4)!!
        assertTrue(stronger.a < d.a, "doubling B should deepen the coefficient: ${stronger.a} vs ${d.a}")
    }

    @Test
    fun `Dispersion is null when B is silent, and the D under 1 bypass rule is reachable`() {
        assertNull(Strings.Dispersion.forB(0f, 4))
        assertNull(Strings.Dispersion.forB(-1e-5f, 4))
        // Far below any sourced string's B (research §3's guzheng range
        // tops out at 1.5e-4) - a mechanism check that the D <= 1 bypass
        // (DAFx-06's own rule) actually fires, not a claim that any voice
        // reaches a B this small.
        assertNull(Strings.Dispersion.forB(1e-14f, 4))
    }

    @Test
    fun `every non-null Dispersion coefficient is negative, in (-1, 0)`() {
        for (b in listOf(1e-6f, 1e-5f, 3.5e-5f, 9e-5f, 1.5e-4f, 1e-3f)) {
            val d = Strings.Dispersion.forB(b, 4)!!
            assertTrue(d.a < 0f && d.a > -1f, "B=$b gave a=${d.a}, expected (-1, 0)")
        }
    }

    /**
     * The test that catches a sign error in [Strings.Dispersion] outright
     * (spec, "Testing", item 2): with dispersion on, partial n's measured
     * frequency over n*f0 must rise with n; with it off, every partial
     * stays within 5 cents of harmonic - the budget holds the fundamental
     * exact (partial 1) either way, since [Strings.tune] charges the
     * cascade's own delay before splitting the loop length.
     *
     * -0.9 over 4 sections is a deliberately strong probe, not GUZHENG's own
     * shipped range: [Strings.Dispersion.forB] at the sourced B ceiling
     * (1.5e-4) moves the loop length by hundredths of a sample by the 8th
     * partial (computed: -0.024 samples against a ~1200-sample loop at
     * 147 Hz, rate 176400) - StiffnessTest's own SITAR probe hit the
     * identical wall at weak coefficients ("the brief's list (0 to -0.50)
     * never leaves 0.00% sharp") and had to extend its own list toward -1
     * to see anything measurable at all. This test proves the mechanism's
     * direction and monotonicity the same way that probe does; how much of
     * it GUZHENG actually ships is Task 7's own audition question, not
     * this one's.
     */
    @Test
    fun `dispersion makes partials progressively sharper, and the fundamental stays put`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val f0 = 147f
        val damping = Strings.damping(0.5f, 4200f)
        val dispersion = Strings.Dispersion(count = 4, a = -0.9f)
        val stiff = Strings.pluck(f0, 0.5f, damping, 6000f, seed = 7, rate = rate, dispersion = dispersion)
        val plain = Strings.pluck(f0, 0.5f, damping, 6000f, seed = 7, rate = rate)

        var last = 0.0
        for (n in 1..8) {
            val measured = PluckSpectra.peakHz(stiff, rate, n * f0, spanFraction = 0.05)
            val ratio = measured / (n * f0.toDouble())
            assertTrue(ratio >= last - 0.0005, "partial $n ratio $ratio is not sharper than partial ${n - 1}'s $last")
            last = ratio
        }
        assertTrue(last > 1.001, "the top partial measured should be measurably sharp of harmonic, got $last")

        for (n in 1..8) {
            val measured = PluckSpectra.peakHz(plain, rate, n * f0, spanFraction = 0.05)
            val cents = 1200.0 * ln(measured / (n * f0.toDouble())) / ln(2.0)
            assertTrue(abs(cents) <= 5.0, "partial $n with no dispersion is $cents cents off, expected harmonic")
        }
    }
}
