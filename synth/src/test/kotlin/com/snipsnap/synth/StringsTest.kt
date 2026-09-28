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

    @Test
    fun `course fails loudly below one loop`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Strings.course(220f, 0.3f, Strings.damping(0.3f, 4200f), 6000f, seed = 1, rate = Dsp.RATE, count = 0, spread = 0f)
        }
        assertTrue(e.message!!.contains("at least 1"), e.message)
    }

    /**
     * [Strings.course]'s off-by-default point is "one loop", not "no
     * detune" - a caller reaching it with count=1 must get exactly what
     * calling [Strings.pluck] directly would, whatever spread it also
     * passed (spread is meaningless with nothing to spread against).
     */
    @Test
    fun `course with one loop is exactly pluck, whatever spread is`() {
        val damping = Strings.damping(0.4f, 4200f)
        for (spread in listOf(0f, 0.3f, 1f)) {
            val viaCourse = Strings.course(220f, 0.3f, damping, 6000f, seed = 11, rate = Dsp.RATE, count = 1, spread = spread)
            val direct = Strings.pluck(220f, 0.3f, damping, 6000f, seed = 11, rate = Dsp.RATE)
            assertContentEquals(direct, viaCourse, "spread=$spread")
        }
    }

    /**
     * The spec's COURSE test ("the envelope of a COURSE-1 render shows
     * beating that a COURSE-0 render does not") is a claim about two
     * different-frequency loops summed - ordinary superposition, not
     * something worth re-deriving through a noisy acoustic measurement.
     * What [course] actually adds is the *detuning*, so this tests that
     * directly: two attempts at measuring beating in the rendered audio
     * (an envelope-ripple metric, then a spectral-spread one) both turned
     * out to be dominated by measurement artifacts unrelated to spread -
     * the exciter's own per-loop noise in the first case, the analysis
     * window's own ~30-cent Goertzel resolution (wider than the offsets
     * being compared) in the second. [Strings.courseDetuneCents] is the
     * actual mechanism spread controls, and it needs no audio to check.
     */
    @Test
    fun `course's detune is bounded by spread, zero at spread 0, and reproducible`() {
        val zero = Strings.courseDetuneCents(seed = 5, count = 4, spread = 0f)
        assertTrue(zero.all { it == 0f }, "spread=0 must give exactly zero detune: $zero")

        val full = Strings.courseDetuneCents(seed = 5, count = 4, spread = 1f)
        assertTrue(full.any { abs(it) > 1f }, "spread=1 should produce a real, nonzero detune: $full")
        assertTrue(full.all { abs(it) <= 25f }, "no loop should exceed +-25 cents (half of COURSE_MAX_CENTS): $full")
        assertEquals(full, Strings.courseDetuneCents(seed = 5, count = 4, spread = 1f), "same seed must draw the same detunes")

        val half = Strings.courseDetuneCents(seed = 5, count = 4, spread = 0.5f)
        for (i in half.indices) assertEquals(full[i] * 0.5f, half[i], 1e-5f, "spread scales the same draw linearly, loop $i")
    }

    /**
     * [Strings.course]'s own per-loop feedback step ([Strings.COURSE_FB_STEP])
     * is meant to be inaudible-in-pitch: at COURSE 0 (true unison), summing
     * `count` loops whose *only* difference is that feedback split must
     * still land on [freq]. This isolates that claim from a confound
     * [Strings.course] itself can't avoid: it seeds each loop's own
     * exciter differently even at spread 0 (`Dsp.seedFor(seed, "COURSE",
     * k)`), so a single FFT reading of the real `course()` output can pass
     * or fail on excitation luck as much as on the fb step (SILK Phase 2's
     * own plan review round caught exactly this, first drafted against
     * `course()` directly). Built by hand here - [Strings.tune] once,
     * [Strings.pluckExciter] once, then `count` [Strings.Loop]s fed that
     * *identical* burst - so feedback is the only thing that varies.
     *
     * With excitation controlled out this way, the fb step alone turns
     * out not to move the fundamental measurably even at its original
     * (pre-Phase-2) value of 0.01 - passes at either constant, across
     * OUD's, SANTUR's, and mid-range roots alike. See
     * [Strings.COURSE_FB_STEP]'s own KDoc: the smaller value this test
     * runs against is kept as an inexpensive precaution, not because this
     * test demonstrates it is load-bearing.
     */
    @Test
    fun `course's own feedback step, isolated from excitation, stays in tune`() {
        val freq = 220f
        val damping = Strings.damping(0.3f, 4200f)
        val t = Strings.tune(freq, damping.loopHz, Dsp.RATE)
        val len = (0.5f * Dsp.RATE).toInt().coerceAtLeast(t.n + 2)
        val exc = Strings.pluckExciter(t.n, freq, 6000f, 0f, seed = 3, rate = Dsp.RATE, maxLen = len)
        for (count in 1..4) {
            val out = FloatArray(len)
            for (k in 0 until count) {
                val fbK = (damping.fb * (1f - Strings.COURSE_FB_STEP * k)).coerceIn(0f, 0.999f)
                val loop = Strings.Loop(t.n, t.a, fbK, damping.loopHz, Dsp.RATE)
                for (i in out.indices) out[i] += loop.next(if (i < exc.size) exc[i] else 0f)
            }
            val measured = FineTuning.measuredHz(out, Dsp.RATE, freq)
            val off = FineTuning.cents(measured, freq.toDouble())
            assertTrue(abs(off) <= 5.0, "count=$count: measured $measured Hz, $off cents off $freq Hz")
        }
    }

    /**
     * The real end-to-end path, excitation and all: [Strings.course] at
     * COURSE 0 across several unrelated seeds, requiring the tuning bound
     * to hold on every one. Where the test above isolates the mechanism,
     * this one rules out excitation luck by exhausting it rather than
     * removing it - the actual caller path (SANTUR's own `count = 4`) goes
     * through here, exciter variation included. Also passes at
     * [Strings.COURSE_FB_STEP]'s original 0.01 - `Strings.course` itself,
     * at its own native (non-oversampled) rate, isn't where Phase 1b's
     * OUD miss lived. SANTUR's own voice-level tuning test (Task 5, the
     * real oversampled render path) is what actually closes this out.
     */
    @Test
    fun `course at spread 0 is in tune across several seeds, real excitation included`() {
        val freq = 220f
        val damping = Strings.damping(0.3f, 4200f)
        for (seed in listOf(1, 2, 3, 7, 11, 19, 23)) {
            val out = Strings.course(freq, 0.5f, damping, 6000f, seed = seed, rate = Dsp.RATE, count = 4, spread = 0f)
            val measured = FineTuning.measuredHz(out, Dsp.RATE, freq)
            val off = FineTuning.cents(measured, freq.toDouble())
            assertTrue(abs(off) <= 5.0, "seed=$seed: measured $measured Hz, $off cents off $freq Hz")
        }
    }

    /**
     * OUD's SLIDE (SILK Phase 1b): a [Strings.Loop] built at a low note and
     * later moved, mid-render, to a higher one - the pitch envelope beside
     * the fixed-tuning path. Measured on two separate windows of one
     * continuous render, before and well after the retune point (past the
     * allpass' own settling), against [PluckSpectra.peakHz]'s usual 5-cent
     * bound.
     */
    @Test
    fun `retune moves the loop's resonant frequency, carrying the allpass state through`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val lowFreq = 130f
        val targetFreq = 147f
        val loopHz = 4200f
        val t0 = Strings.tune(lowFreq, loopHz, rate)
        val seconds = 0.6f
        val total = (seconds * rate).toInt()
        val exc = Strings.pluckExciter(t0.n, lowFreq, 6000f, position = 0f, seed = 9, rate = rate, maxLen = total)
        val loop = Strings.Loop(t0.n, t0.a, fb = 0.995f, loopHz = loopHz, rate = rate)
        val out = FloatArray(total)
        val retuneAt = total / 3
        for (i in 0 until total) {
            if (i == retuneAt) loop.retune(targetFreq)
            out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        }
        val retuneAtSec = retuneAt.toFloat() / rate
        val before = PluckSpectra.peakHz(out, rate, lowFreq, spanFraction = 0.05, fromSec = 0.02f, seconds = retuneAtSec - 0.04f)
        val after = PluckSpectra.peakHz(out, rate, targetFreq, spanFraction = 0.05, fromSec = retuneAtSec + 0.05f, seconds = seconds - retuneAtSec - 0.1f)
        val beforeCents = 1200.0 * ln(before / lowFreq) / ln(2.0)
        val afterCents = 1200.0 * ln(after / targetFreq) / ln(2.0)
        assertTrue(abs(beforeCents) <= 5.0, "before retuning, expected near $lowFreq Hz, measured $beforeCents cents off")
        assertTrue(abs(afterCents) <= 5.0, "after retuning, expected near $targetFreq Hz, measured $afterCents cents off")
    }

    @Test
    fun `retune refuses to grow the loop past what it was built for`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val loopHz = 4200f
        val t = Strings.tune(440f, loopHz, rate)
        val loop = Strings.Loop(t.n, t.a, fb = 0.99f, loopHz = loopHz, rate = rate)
        val e = assertFailsWith<IllegalArgumentException> { loop.retune(55f) }
        assertTrue(e.message!!.contains("built for"), e.message)
    }
}
