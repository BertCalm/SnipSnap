package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /** Mean magnitude weighted by frequency - a cheap onset-brightness measure for a short exciter burst on its own, no string or loop needed. */
    private fun spectralCentroid(samples: FloatArray, rate: Int): Double {
        var n = 256
        while (n < samples.size) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        samples.copyInto(re, 0, 0, samples.size)
        com.snipsnap.audio.Fft.forward(re, im)
        var weighted = 0.0
        var total = 0.0
        for (i in 0 until n / 2) {
            val mag = kotlin.math.hypot(re[i].toDouble(), im[i].toDouble())
            weighted += mag * (i.toDouble() * rate / n)
            total += mag
        }
        return if (total > 0.0) weighted / total else 0.0
    }

    /**
     * [Strings.mallet] (SILK Phase 2, SANTUR): a raised-cosine pulse
     * whose width [Strings.mallet]'s own KDoc ties to [hardness] the same
     * way [Strings.pluckExciter] reads its `pickHz` as a low-pass cutoff -
     * a higher value narrows the pulse, so its onset should read brighter
     * (spec, "SANTUR": "PICK is mallet hardness (pulse width: wide and
     * dark to narrow and bright)").
     */
    @Test
    fun `mallet's onset gets brighter as hardness rises, and is zero-mean`() {
        val narrow = Strings.mallet(n = 400, freq = 147f, hardness = 8000f, position = 0f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        val wide = Strings.mallet(n = 400, freq = 147f, hardness = 500f, position = 0f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        assertTrue(abs(narrow.sum()) < 1e-3f, "narrow sum=${narrow.sum()}")
        assertTrue(abs(wide.sum()) < 1e-3f, "wide sum=${wide.sum()}")
        val narrowCentroid = spectralCentroid(narrow, Dsp.RATE)
        val wideCentroid = spectralCentroid(wide, Dsp.RATE)
        assertTrue(narrowCentroid > wideCentroid, "narrow (hard) pulse should read brighter: $narrowCentroid vs $wideCentroid")
    }

    /**
     * Guards against a real regression a review caught: the mean correction
     * subtracted the pulse's own mean from every sample out to `n`, not just
     * the active `width` it was computed over - since the zero padding past
     * `width` shares in the subtraction, that turns silence into a flat
     * `-mean` shelf, a long reverse-force tail. The *overall* sum still
     * lands at zero either way (`pulseSum - n * (pulseSum / n) = 0`), which
     * is why the sum-based zero-mean test above didn't catch it - only the
     * shape does.
     */
    @Test
    fun `mallet's zero-mean correction stays inside the active pulse, not the padding past it`() {
        val pulse = Strings.mallet(n = 400, freq = 147f, hardness = 500f, position = 0f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        assertEquals(0f, pulse[pulse.size - 1], "the tail well past any pulse width here should be exact silence, not a residual shelf")
    }

    @Test
    fun `mallet is deterministic, and the comb lengthens it same as the pick burst`() {
        val a = Strings.mallet(n = 300, freq = 147f, hardness = 4000f, position = 0f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        val b = Strings.mallet(n = 300, freq = 147f, hardness = 4000f, position = 0f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        assertContentEquals(a, b)
        val combed = Strings.mallet(n = 300, freq = 147f, hardness = 4000f, position = 0.25f, seed = 1, rate = Dsp.RATE, maxLen = 10_000)
        assertTrue(combed.size > a.size, "the comb's delayed copy extends the exciter")
    }

    /** [Strings.pluck]'s own `exciter` seam (SILK Phase 2): swapping in [Strings.mallet] must actually reach the loop, not silently keep the pick burst. */
    @Test
    fun `pluck's exciter parameter actually swaps the excitation`() {
        val damping = Strings.damping(0.3f, 4200f)
        val picked = Strings.pluck(220f, 0.2f, damping, 6000f, seed = 4, rate = Dsp.RATE)
        val malleted = Strings.pluck(220f, 0.2f, damping, 6000f, seed = 4, rate = Dsp.RATE, exciter = Strings::mallet)
        assertFalse(picked.contentEquals(malleted), "pick and mallet excitation should render differently")
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

    /**
     * SANTUR (SILK Phase 2) carries a *fixed* `B = 3.1e-4` (Heydarian,
     * spec "How much B" / "SANTUR" - not a knob, roughly double GUZHENG's
     * own STIFF ceiling of 1.5e-4). The spec's own testing claim: "its
     * partial stretch at default matches B = 3.1e-4 within the fit
     * tolerance the STIFF test uses." No such tolerance exists to point
     * at (see the plan review round that flagged this), so this defines
     * one directly - predicted stretch from the closed-form physics the
     * spec itself cites (`fk = k*f0*sqrt(1+B*k^2)`, ratio `sqrt(1+B*k^2)`)
     * against what `Dispersion.forB` actually renders - and the honest
     * result is that they do **not** agree: `forB`'s own coefficient
     * cascade produces a stretch several orders of magnitude below the
     * physics' own prediction (partial 8's predicted ~0.99% / ~17 cents
     * reads as an unmeasurable ~0.0000% here), the identical underlying
     * weakness GUZHENG's own STIFF found at its own (smaller) B. This is
     * not a bug in the sign or the mechanism - see the probe above with
     * its own strong synthetic coefficient, which proves both - it is
     * `Dispersion.forB`'s per-note-independent simplification of the
     * corrected Rauhala design falling short of what the piano
     * application it comes from does with a per-note re-derivation this
     * phase does not attempt. Documented here rather than forced to pass
     * a tolerance check with no real agreement behind it: closing this
     * gap is the same open follow-on GUZHENG's own `Silk.guzheng` comment
     * already names, not a new one.
     */
    @Test
    fun `SANTUR's own sourced B does not yet produce the stretch its own physics predicts`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val f0 = 164.81f // E3, SANTUR's own root (spec, "SANTUR": "Root E3")
        val b = 3.1e-4
        val damping = Strings.damping(0.3f, 6500f)
        val dispersion = Strings.Dispersion.forB(b.toFloat(), 4)
        assertTrue(dispersion != null, "B=$b at count=4 should not bypass to null")
        val stiff = Strings.pluck(f0, 0.5f, damping, 8000f, seed = 7, rate = rate, dispersion = dispersion)

        val predictedRatio8 = kotlin.math.sqrt(1.0 + b * 8.0 * 8.0)
        val measured8 = PluckSpectra.peakHz(stiff, rate, 8 * f0, spanFraction = 0.05)
        val measuredRatio8 = measured8 / (8.0 * f0)

        assertTrue(predictedRatio8 > 1.009, "sanity: the physics itself should predict a real stretch at partial 8, got $predictedRatio8")
        assertTrue(
            measuredRatio8 < 1.0005,
            "if this fails, Dispersion.forB has started producing a real stretch at SANTUR's own B - " +
                "update this test (and the roadmap row/Silk.guzheng's comment) to reflect the fix rather than loosening the bound: " +
                "measured=$measuredRatio8, predicted=$predictedRatio8",
        )
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

    // ---------- BORE's R0: the three additions ----------
    // docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "The bore".
    // The two frozen grids above are the proof that the defaults change no sample;
    // what follows is what the new parameters do when they are used.

    private val boreRate = Dsp.RATE * Dsp.OVERSAMPLE

    /**
     * One pluck burst rung through a [Strings.Loop] built the way [Strings.pluck]
     * builds it, but at a chosen round trip, feedback sign and DC-blocker corner.
     * [loopDcHz] is the corner the loop *runs*, [dcHz] the one [Strings.tune]
     * *budgets*; they are the same number unless a test is deliberately
     * mismatching them.
     */
    private fun ring(
        freq: Float,
        seconds: Float,
        fb: Float,
        roundTrip: Double,
        dcBlock: Boolean = false,
        dcHz: Float = Strings.DC_BLOCK_HZ,
        loopDcHz: Float = dcHz,
    ): FloatArray {
        val loopHz = 4200f
        val t = Strings.tune(freq, loopHz, boreRate, dcBlock = dcBlock, dcHz = dcHz, roundTrip = roundTrip)
        val total = (seconds * boreRate).toInt()
        val loop = Strings.Loop(t.n, t.a, fb, loopHz, boreRate, dcBlock = dcBlock, dcHz = loopDcHz, roundTrip = roundTrip)
        // A unit impulse, not a noise burst: its spectrum is flat, so what comes out is the loop's own
        // comb and nothing else. A burst's spectrum at any one harmonic is a random draw, and a claim
        // about how far one harmonic sits under another cannot rest on a draw.
        return FloatArray(total) { loop.next(if (it == 0) 1f else 0f) }
    }

    /**
     * The level, in dB, of the loudest Hann-windowed FFT bin within +-[span] of [hz] over
     * [bodySeconds] from [fromSec]. Windowed on purpose: [PluckSpectra.toneEnergy]'s plain
     * Goertzel has no taper, and against a ringing fundamental its window edges leak
     * enough into a harmonic an octave up to hide a real 20 dB difference.
     */
    private fun levelDb(samples: FloatArray, hz: Float, fromSec: Float = 0.05f, bodySeconds: Float = 0.25f, span: Double = 0.03): Double {
        val from = (fromSec * boreRate).toInt()
        val len = minOf(samples.size - from, (bodySeconds * boreRate).toInt())
        var n = 1
        while (n < 65536) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until len) re[i] = samples[from + i] * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (len - 1)).toFloat())
        com.snipsnap.audio.Fft.forward(re, im)
        val binHz = boreRate.toDouble() / n
        var best = 0.0
        for (b in maxOf(1, ((hz * (1 - span)) / binHz).toInt())..minOf(n / 2 - 1, ((hz * (1 + span)) / binHz).toInt())) {
            best = maxOf(best, kotlin.math.hypot(re[b].toDouble(), im[b].toDouble()))
        }
        return 20.0 * kotlin.math.log10(best + 1e-30)
    }

    /**
     * The round-trip factor is the period's, not the filters'. A closed
     * cylinder's loop is half a period long, but the one-pole's phase lag, the
     * blocker's lead and the dispersion cascade's delay are what they are at
     * the fundamental whatever the loop's length - so halving `exact` wholesale
     * would over-correct every one of them. The difference between the two
     * budgets must be exactly half the period, with every stage on.
     */
    @Test
    fun `tune's roundTrip multiplies the period and nothing else`() {
        val dispersion = Strings.Dispersion(count = 2, a = -0.3f)
        for (freq in listOf(110f, 220f, 440f, 880f)) {
            val full = Strings.tune(freq, 4200f, boreRate, stiffness = -0.2f, dispersion = dispersion, dcBlock = true)
            val half = Strings.tune(freq, 4200f, boreRate, stiffness = -0.2f, dispersion = dispersion, dcBlock = true, roundTrip = 0.5)
            val period = (boreRate / freq).toDouble()
            assertEquals(period * 0.5, full.exact - half.exact, 1e-9, "freq=$freq: the two budgets should differ by half a period, not by anything the filters owe")
        }
    }

    /**
     * The round trip is a fraction of a period, so it lives in (0, 1]. Each bad
     * value here would otherwise fail somewhere else under another name: zero and
     * below trip the Karplus-Strong minimum's message about a note too high, a
     * value above 1 builds a loop longer than its own note, and a finite extreme
     * overflows `exact` to infinity, which passes the minimum and returns a Tuning
     * nobody can build a ring from. 1.0 itself is the ordinary loop and must pass.
     */
    @Test
    fun `tune refuses a round trip that is not a fraction of a period, and names it`() {
        for (bad in listOf(0.0, -0.5, 1.0000001, 1.5, 2.0, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val e = assertFailsWith<IllegalArgumentException> { Strings.tune(220f, 4200f, boreRate, roundTrip = bad) }
            assertTrue(e.message!!.contains("roundTrip"), "roundTrip=$bad should be refused by name, not as a too-high note: ${e.message}")
        }
        for (ok in listOf(1.0, 0.5, 0.25)) {
            assertTrue(Strings.tune(220f, 4200f, boreRate, roundTrip = ok).exact > Strings.MIN_LOOP_SAMPLES, "roundTrip=$ok is a fraction of a period and must be accepted")
        }
    }

    /**
     * The blocker used to exist only inside the jawari branch, so its phase
     * lead was charged only when [jawari] was on. A +1 loop under a steady
     * pressure needs the blocker with no limiter anywhere near it, so the flag
     * stands on its own - and still defaults to what `jawari > 0` always meant.
     */
    @Test
    fun `the DC blocker is budgeted on its own, at the corner it is given, and jawari still implies it`() {
        val f = 220f
        val plain = Strings.tune(f, 4200f, boreRate)
        val blocked = Strings.tune(f, 4200f, boreRate, dcBlock = true)
        val blockedFast = Strings.tune(f, 4200f, boreRate, dcBlock = true, dcHz = 20f)
        // A high-pass leads; the loop owes that lead back, so the ring gets longer, and more so at a higher corner.
        assertTrue(blocked.exact > plain.exact, "charging the blocker should lengthen the ring: ${blocked.exact} vs ${plain.exact}")
        assertTrue(blockedFast.exact > blocked.exact + 1.0, "a 20 Hz corner leads by samples more than a 2 Hz one: ${blockedFast.exact} vs ${blocked.exact}")
        assertEquals(blocked.exact, Strings.tune(f, 4200f, boreRate, dcBlock = true, dcHz = Strings.DC_BLOCK_HZ).exact, "the default corner is DC_BLOCK_HZ")
        assertEquals(
            Strings.tune(f, 4200f, boreRate, jawari = 0.3f).exact,
            Strings.tune(f, 4200f, boreRate, jawari = 0.3f, dcBlock = true).exact,
            "jawari alone still charges the blocker, as it always did",
        )
        assertEquals(plain.exact, Strings.tune(f, 4200f, boreRate, dcBlock = false).exact, "no jawari, no blocker: the plain string's budget")
    }

    /**
     * The corner has to be a frequency the loop can have. Infinity is the case that
     * matters: it makes the one-pole coefficient exactly 1, a blocker that subtracts
     * every sample and silently kills the loop, so a refusal by name is the only
     * good outcome. So is any finite value far enough past Nyquist for the same
     * underflow, and Nyquist itself, which is not "under" it. Both entry points
     * refuse: the budget in [Strings.tune] and the loop that runs the corner.
     */
    @Test
    fun `the DC blocker refuses a corner that is not a frequency under Nyquist, in the budget and in the loop`() {
        val nyquist = boreRate / 2f
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, 1e30f, nyquist, nyquist * 2f)) {
            val budget = assertFailsWith<IllegalArgumentException>("tune with dcHz=$bad") { Strings.tune(220f, 4200f, boreRate, dcBlock = true, dcHz = bad) }
            assertTrue(budget.message!!.contains("corner"), "tune, dcHz=$bad: ${budget.message}")
            val running = assertFailsWith<IllegalArgumentException>("Loop with dcHz=$bad") { Strings.Loop(200, 0.5f, 0.9f, 4200f, boreRate, dcBlock = true, dcHz = bad) }
            assertTrue(running.message!!.contains("corner"), "Loop, dcHz=$bad: ${running.message}")
        }
        // Off, the corner is never used, so it is not validated - the default corner with the blocker off is the ordinary string.
        Strings.Loop(200, 0.5f, 0.9f, 4200f, boreRate, dcBlock = false, dcHz = Float.POSITIVE_INFINITY)
        // Just under Nyquist is a frequency: accepted, and a coefficient short of 1.
        assertTrue(Strings.tune(220f, 4200f, boreRate, dcBlock = true, dcHz = nyquist * 0.99f).exact > Strings.MIN_LOOP_SAMPLES)
    }

    /**
     * A steady input into a +0.95 loop settles at 1/(1 - 0.95) = 20 times itself -
     * the offset a blown reed leaves - unless the blocker drains it, in which
     * case the loop settles at the input. Proves the blocker actually runs
     * with [jawari] at 0, which is the whole reason for the flag.
     */
    @Test
    fun `a loop with the blocker on drains a steady offset, and one without it does not`() {
        val rate = Dsp.RATE
        fun settled(dcBlock: Boolean): Float {
            val t = Strings.tune(220f, 4200f, rate, dcBlock = dcBlock)
            val loop = Strings.Loop(t.n, t.a, 0.95f, 4200f, rate, dcBlock = dcBlock)
            var y = 0f
            repeat(rate * 3 / 2) { y = loop.next(1f) }
            return y
        }
        assertEquals(1f, settled(dcBlock = true), 0.05f, "the blocker should drain the loop's offset back to the input")
        assertTrue(settled(dcBlock = false) > 15f, "with no blocker the loop should build to about 1/(1 - fb) = 20 times the input")
    }

    /**
     * [Strings.tune] charges a corner and [Strings.Loop] runs one; they have to be the
     * same number or the note is off. At 20 Hz the mismatch against the 2 Hz default is
     * tens of cents, so the control below proves the measurement can see it - a test
     * that could not fail would prove nothing about the corner reaching the loop.
     */
    @Test
    fun `the blocker's budgeted corner and its running corner are the same number`() {
        for (freq in listOf(110f, 220f)) {
            val right = FineTuning.measuredHz(ring(freq, 0.6f, 0.99f, 1.0, dcBlock = true, dcHz = 20f), boreRate, freq)
            assertTrue(abs(FineTuning.cents(right, freq.toDouble())) <= 5.0, "freq=$freq: a loop running the corner it budgeted measured $right Hz")
            val wrong = FineTuning.measuredHz(ring(freq, 0.6f, 0.99f, 1.0, dcBlock = true, dcHz = 20f, loopDcHz = Strings.DC_BLOCK_HZ), boreRate, freq)
            assertTrue(
                abs(FineTuning.cents(wrong, freq.toDouble())) > 5.0,
                "control: budgeting 20 Hz and running 2 Hz should read audibly off at $freq Hz, measured $wrong Hz - if this passes the test above proves nothing",
            )
        }
    }

    /**
     * The inverting budget, the reason `roundTrip` exists. A closed cylinder's
     * wave needs two trips to return in phase, so a loop half a period long with
     * its sign flipped rings at f and its ODD harmonics only. Its ring-down is
     * half-wave antisymmetric - each half period is the last with its sign flipped -
     * so an even harmonic can be nothing but what the decay envelope and the
     * analysis window leave behind. Measured on an impulse, Hann-windowed from
     * 50 ms in, the 2nd harmonic sat 97.6, 103.8 and 101.4 dB under the
     * fundamental at 110, 220 and 440 Hz, the 3rd at least 72 dB over the 2nd;
     * the bounds below (60 and 40 dB) leave a wide margin under those, so they
     * fail on a wrong sign or a wrong length and not on float noise.
     */
    @Test
    fun `a half-length loop that inverts its wave is a closed cylinder - f, then only the odd harmonics`() {
        for (freq in listOf(110f, 220f, 440f)) {
            val out = ring(freq, 0.6f, -0.95f, 0.5)
            val measured = FineTuning.measuredHz(out, boreRate, freq)
            assertTrue(abs(FineTuning.cents(measured, freq.toDouble())) <= 5.0, "freq=$freq measured $measured Hz")
            val h1 = levelDb(out, freq)
            val h2 = levelDb(out, 2 * freq)
            val h3 = levelDb(out, 3 * freq)
            assertTrue(h1 - h2 > 60.0, "freq=$freq: the 2nd harmonic should be over 60 dB under the fundamental, got ${h1 - h2} dB")
            assertTrue(h3 - h2 > 40.0, "freq=$freq: the 3rd is a resonance and the 2nd is not, got only ${h3 - h2} dB between them")
        }
    }

    /**
     * The mistake the reviewed spec made, in miniature: keep the half length and
     * keep the sign, and the loop is a full-period comb on half a period - it
     * rings an octave up. The length alone does not make a closed cylinder;
     * the length and the inversion together do.
     */
    @Test
    fun `the same half-length loop with its sign kept plays an octave up`() {
        for (freq in listOf(110f, 220f)) {
            val out = ring(freq, 0.6f, 0.95f, 0.5)
            val measured = FineTuning.measuredHz(out, boreRate, 2 * freq)
            assertTrue(abs(FineTuning.cents(measured, 2.0 * freq)) <= 5.0, "freq=$freq: expected the octave, measured $measured Hz")
        }
    }

    /**
     * The retune test above, at a closed cylinder's half-length loop with the
     * sign flipped. A [Strings.Loop.retune] that dropped the round trip would
     * re-solve the budget at a full period, a loop longer than the ring it was
     * built with, and refuse ("built for") - so this fails by name if the factor is
     * not carried through.
     */
    @Test
    fun `retune carries the round trip, so a closed cylinder stays one`() {
        val rate = boreRate
        val lowFreq = 130f
        val targetFreq = 147f
        val loopHz = 4200f
        val t0 = Strings.tune(lowFreq, loopHz, rate, roundTrip = 0.5)
        val seconds = 0.6f
        val total = (seconds * rate).toInt()
        val exc = Strings.pluckExciter(t0.n, lowFreq, 6000f, position = 0f, seed = 9, rate = rate, maxLen = total)
        val loop = Strings.Loop(t0.n, t0.a, fb = -0.995f, loopHz = loopHz, rate = rate, roundTrip = 0.5)
        val out = FloatArray(total)
        val retuneAt = total / 3
        for (i in 0 until total) {
            if (i == retuneAt) loop.retune(targetFreq)
            out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        }
        val retuneAtSec = retuneAt.toFloat() / rate
        val before = PluckSpectra.peakHz(out, rate, lowFreq, spanFraction = 0.05, fromSec = 0.02f, seconds = retuneAtSec - 0.04f)
        val after = PluckSpectra.peakHz(out, rate, targetFreq, spanFraction = 0.05, fromSec = retuneAtSec + 0.05f, seconds = seconds - retuneAtSec - 0.1f)
        assertTrue(abs(FineTuning.cents(before, lowFreq.toDouble())) <= 5.0, "before retuning, expected near $lowFreq Hz, measured $before")
        assertTrue(abs(FineTuning.cents(after, targetFreq.toDouble())) <= 5.0, "after retuning, expected near $targetFreq Hz, measured $after")
    }

    /**
     * The junction hook is a reordering, not a new loop: [Strings.Loop.next] is
     * `inject(x + reflected())` once history exists, and passes the input straight
     * through before it. A blown bore replaces the `x + ...` with a nonlinear
     * function of [Strings.Loop.reflected]; everything else stays this loop.
     * With every stage on (stiffness, jawari, dispersion, the blocker) and both
     * signs, the split must reproduce `next` sample for sample - and asking
     * [Strings.Loop.reflected] twice before [Strings.Loop.inject] must not step
     * the loop's filters twice.
     */
    @Test
    fun `reflected then inject is next, taken apart, with every stage on`() {
        val rate = boreRate
        val freq = 196f
        val dispersion = Strings.Dispersion(count = 2, a = -0.3f)
        for (fb in listOf(0.98f, -0.95f)) {
            val roundTrip = if (fb < 0f) 0.5 else 1.0
            val t = Strings.tune(freq, 4200f, rate, stiffness = -0.2f, jawari = 0.3f, dispersion = dispersion, dcBlock = true, dcHz = 5f, roundTrip = roundTrip)
            val total = 6000
            val exc = Strings.pluckExciter(t.n, freq, 6000f, position = 0f, seed = 3, rate = rate, maxLen = total)
            val p0 = Strings.burstPeak(t.n, 6000f, 3, rate)
            fun make() = Strings.Loop(
                t.n, t.a, fb, 4200f, rate,
                stiffness = -0.2f, jawari = 0.3f, jawariP0 = p0, dispersion = dispersion,
                dcBlock = true, dcHz = 5f, roundTrip = roundTrip,
            )
            val viaNext = make()
            val viaParts = make()
            val viaTwice = make()
            val a = FloatArray(total)
            val b = FloatArray(total)
            val c = FloatArray(total)
            for (i in 0 until total) {
                val x = if (i < exc.size) exc[i] else 0f
                a[i] = viaNext.next(x)
                b[i] = viaParts.inject(x + viaParts.reflected())
                viaTwice.reflected()
                c[i] = viaTwice.inject(x + viaTwice.reflected())
            }
            assertContentEquals(a, b, "fb=$fb: inject(x + reflected()) must be next(x)")
            assertContentEquals(a, c, "fb=$fb: a second reflected() before inject must return the same wave, not step the filters again")
        }
    }
}
