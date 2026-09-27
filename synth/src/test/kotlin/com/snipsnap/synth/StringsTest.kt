package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
