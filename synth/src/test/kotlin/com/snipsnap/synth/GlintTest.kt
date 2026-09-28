package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlintTest {

    /**
     * The all-ones corner's peak floor is lower than the other two cases'
     * because `Dsp.levelTo` normalizes to loudness (RMS of the loudest
     * 200 ms), not peak, and a denser window lands a lower peak at equal
     * loudness. KAZOO's trapezoid holds flat for KAZOO_FLAT of the cycle,
     * so its mean-square energy at a given peak is ~0.8 against REED's and
     * BOTTLE's 1/3 - about 2.4x - which alone predicts a ~1.55x lower peak
     * at matched loudness.
     *
     * Measured 2026-09-26, all macros = 1 (k fixed at 8, Task 1):
     *   REED   peak=0.6518
     *   BOTTLE peak=0.6334
     *   KAZOO  peak=0.4430
     *
     * Sweeping KAZOO's DECAY alone (TUNE at default) isolates DECAY, not
     * TUNE, as the driver - a long decay keeps the loudest-RMS window near
     * full level, raising measured loudness and so lowering the normalized
     * peak further:
     *   DECAY=0.0 -> peak=0.99
     *   DECAY=0.3 -> peak=0.936
     *   DECAY=0.5 -> peak=0.742
     *   DECAY=0.7 -> peak=0.590
     *   DECAY=0.8 -> peak=0.530
     *   DECAY=0.9 -> peak=0.480  (crosses below 0.5)
     *   DECAY=1.0 -> peak=0.439
     *
     * 0.35, not a tighter floor: Task 5's BLOOM changes KAZOO's crest
     * factor again, and 0.443 against 0.40 is only 10% margin. 0.35 still
     * catches a genuinely broken render (near zero), which is all this
     * assertion is for.
     *
     * Note (2026-09-26): the figures above — both the corner-case peaks and
     * the DECAY sweep — were measured when `k` was fixed at 8 and BLOOM did
     * not exist yet (Task 1). They describe that fixed-`k` engine, not the
     * current one; read them as historical motivation for the 0.35 floor,
     * not as current numbers.
     */
    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in GlintVoice.entries) {
            val cases = listOf(
                emptyMap<String, Float>() to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 0f } to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 1f } to 0.35f,
            )
            for ((macros, peakFloor) in cases) {
                val snip = Glint.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > peakFloor, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six macros`() {
        for (voice in GlintVoice.entries) {
            assertEquals(
                listOf("TUNE", "PEAK", "FOLLOW", "BODY", "BLOOM", "DECAY"),
                Glint.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every window ends at exactly zero - the whole engine rests on this`() {
        for (voice in GlintVoice.entries) {
            assertEquals(0f, Glint.windowAt(voice, 1f), 1e-6f, "$voice window must close the cycle")
            assertTrue(Glint.windowAt(voice, 0.5f) > 0f, "$voice window must be open mid-cycle")
        }
    }

    @Test
    fun `each voice's window is the shape the spec pairs it with, not just any window`() {
        // Replaces two tests inherited from the six-voice expansion
        // (commit ad923519) that a review found could not discriminate a
        // correct window pairing from a wrong one:
        //   - the zero-at-phase-1 check duplicated the pre-existing test
        //     just above (`every window ends at exactly zero`), which
        //     already loops the enum at the same tolerance;
        //   - the open-at-0.05 check (> 0.01f) was cleared by all three
        //     window shapes regardless of pairing (saw 0.95, triangle 0.1,
        //     trapezoid 1.0), so it would pass even if CICADA had been
        //     given the trapezoid;
        //   - `snip.peak() > 0.1f` is true by construction: `Dsp.levelTo`
        //     normalises every render to MELODIC_LOUDNESS_TARGET (0.1834)
        //     and only ever clamps down, and RMS <= peak, so any non-silent
        //     render clears it. The only way to trip it is literal digital
        //     silence, which a missing `when` branch raises as a compile
        //     error, not a quiet render.
        //
        // Phase 0.6 is where the three shapes actually diverge: saw (REED,
        // PLATE) is 0.4, triangle (BOTTLE, CICADA) is 0.8, trapezoid
        // (KAZOO, RATCHET) is 1.0 (still flat - KAZOO_FLAT is 0.7). Getting
        // CICADA's own reading here requires it to actually be the
        // triangle - the trapezoid or the saw would each read a different
        // number - which is what makes this a real discriminator rather
        // than a floor all three shapes clear regardless of pairing.
        // Verified against `windowAt` directly before writing these in:
        // REED/PLATE 0.39999998, BOTTLE/CICADA 0.79999995, KAZOO/RATCHET
        // 1.0 - the 1e-6f tolerance below absorbs that float rounding
        // against the exact literals.
        for (voice in GlintVoice.entries) {
            val expected = when (voice) {
                GlintVoice.REED, GlintVoice.PLATE -> 0.4f
                GlintVoice.BOTTLE, GlintVoice.CICADA -> 0.8f
                GlintVoice.KAZOO, GlintVoice.RATCHET -> 1.0f
            }
            assertEquals(expected, Glint.windowAt(voice, 0.6f), 1e-6f, "$voice's window shape at phase 0.6")
            // Kept from the pair of tests this replaced. It is a weak bound,
            // but unlike the peak and frame-count checks alongside it, it is
            // not implied by anything: rootHz is a hand-written per-voice
            // literal, and a typo there (2200 for 220) would sail past every
            // other test in this file, which all measure ratios rather than
            // absolute pitch.
            val root = Glint.rootHz(voice)
            assertTrue(root > 20f && root < 2000f, "$voice's root is $root Hz, outside the audible fundamental range")
        }
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Glint.frequencyFor(GlintVoice.REED, i / 100f))
        assertEquals(Glint.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(Glint.rootHz(GlintVoice.REED), Glint.frequencyFor(GlintVoice.REED, 0f), 1e-3f)
        assertEquals(Glint.rootHz(GlintVoice.REED) * 4f, Glint.frequencyFor(GlintVoice.REED, 1f), 1e-2f)
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in GlintVoice.entries) {
            val short = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(long.decayMs > short.decayMs * 1.5f, "$voice DECAY should stretch the note: ${short.decayMs} -> ${long.decayMs}")
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.render(voice, mapOf("PEAK" to 0.7f))
            val b = Glint.render(voice, mapOf("PEAK" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.scramble(voice, Random(11))
            val b = Glint.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Glint.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `PEAK opens`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("BLOOM" to 0f, "BODY" to 0.2f)
            val dark = FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to 0.05f)))
            val open = FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to 0.95f)))
            assertTrue(
                open.centroidHz > dark.centroidHz * 1.5f,
                "$voice PEAK up should brighten: ${dark.centroidHz} -> ${open.centroidHz}",
            )
        }
    }

    /**
     * [x] resampled at the fractional sample position [pos] with a 64-tap
     * Blackman-windowed sinc kernel, not two-tap linear interpolation.
     *
     * Linear interpolation is not accurate enough for what this file uses
     * it for: a mathematically exact 15,840 Hz sinusoid, no engine
     * involved, scores 0.97132 under [periodCorrelation]'s own measure
     * with linear interpolation — within 0.003 of a real CICADA reading
     * this file used to fail on at that same carrier. The two-tap method
     * was measuring its own phase error, not periodicity; see
     * `periodCorrelation`'s callers for the reading this replaced.
     *
     * Taps are clamped to the array's bounds at the edges rather than
     * zero-padded, and the kernel is normalized to unit DC gain (divided
     * by the sum of its own weights) so a clamped, truncated kernel near
     * a buffer edge does not also scale the result.
     */
    private fun sampleAt(x: FloatArray, pos: Float): Float {
        val taps = 64
        val half = taps / 2
        val base = kotlin.math.floor(pos).toInt()
        val frac = pos - base
        var sum = 0.0
        var weight = 0.0
        for (j in 0 until taps) {
            val idx = (base - half + 1 + j).coerceIn(0, x.size - 1)
            val d = (frac + half - 1 - j).toDouble()
            val sinc = if (kotlin.math.abs(d) < 1e-7) 1.0 else kotlin.math.sin(kotlin.math.PI * d) / (kotlin.math.PI * d)
            val blackman = 0.42 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * j / (taps - 1)) +
                0.08 * kotlin.math.cos(4.0 * kotlin.math.PI * j / (taps - 1))
            val w = sinc * blackman
            sum += x[idx] * w
            weight += w
        }
        return (sum / weight).toFloat()
    }

    /**
     * Correlation of [snip] against itself exactly one period later.
     *
     * The lag is fractional on purpose. 44100/220 is 200.45 samples, so an
     * integer lag sits ~0.45 samples out, and at high k that misalignment
     * alone pulls the correlation to 0.91 - it would measure sample
     * quantization rather than the engine.
     */
    private fun periodCorrelation(snip: Snip, f0: Float, fromSec: Float = 0.05f, winSec: Float = 0.2f): Float {
        val lag = snip.sampleRate / f0
        val a0 = (fromSec * snip.sampleRate).toInt()
        val n = (winSec * snip.sampleRate).toInt()
            .coerceAtMost(snip.samples.size - a0 - lag.toInt() - 2)
        var sa = 0.0; var sb = 0.0
        for (i in 0 until n) { sa += snip.samples[a0 + i]; sb += sampleAt(snip.samples, a0 + i + lag) }
        val ma = sa / n; val mb = sb / n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val x = snip.samples[a0 + i] - ma
            val y = sampleAt(snip.samples, a0 + i + lag) - mb
            num += x * y; da += x * x; db += y * y
        }
        return (num / kotlin.math.sqrt(da * db)).toFloat()
    }

    @Test
    fun `the formant sweeps and the pitch does not move - the line between GLINT and TINES`() {
        // FM drags perceived pitch as its index climbs, because sidebands
        // crowd the fundamental. A windowed burst cannot: the window wraps at
        // f0 whatever k is doing, so the period is untouched by construction.
        // This asserts that directly rather than through a pitch detector -
        // Pitch.detect's smallest-lag heuristic mis-reads some of these
        // renders by ~9% (REED at PEAK 0.6 reads 239.67 Hz against 220), a
        // detector artifact that a periodicity measure sidesteps entirely.
        //
        // Task 2 fixed BLOOM's sweep at a single 0.45s t60 (previously it
        // varied 0.30s -> 0.06s with depth). In Phase 1 the sweep was fast
        // enough that this window's default 0.05s start sat mostly *after*
        // it, reading 0.98643. Slowing the sweep sevenfold - the change that
        // made BLOOM audible at all - moved that same window into the
        // middle of the sweep, and this test now runs only from a settled
        // window (0.35s onward, chosen so envAt(0.35, 0.45) = 0.0045) at the
        // original 0.98 bar. That 0.35 is written below as
        // `Glint.BLOOM_T60 * (7f / 9f)`, not the literal, so it stays at the
        // same envAt fraction if BLOOM_T60 ever changes - D2 is expected to
        // touch it, and a hardcoded 0.35 would silently slide back into the
        // sweep and fail a correct engine (see the inversion documented
        // below) while pointing at a pitch bug that does not exist. This
        // trades one failure mode for another: at DECAY 0.7 the render is
        // ~0.90s, so this only has room while BLOOM_T60 stays below ~0.9s
        // (fromSec + the 0.2s correlation window must fit before the end);
        // a BLOOM_T60 much larger than that needs `still`'s DECAY raised
        // here too, not just this derivation.
        //
        // Measured 2026-09-27 at fromSec=0.35 across all SIX voices x 4 PEAK
        // settings x both BLOOM extremes, with `sampleAt` now the 64-tap
        // Blackman-sinc kernel documented above it - not the two-tap linear
        // interpolation that used to make this test read CICADA as
        // aperiodic (correlation 0.9683 at a 15,840 Hz carrier, within
        // 0.003 of a mathematically exact sinusoid's own 0.97132 under
        // linear interpolation - see `sampleAt`'s doc): worst case
        // 0.99940187 (REED and PLATE, PEAK 0.9, BLOOM 1) - comfortably
        // above the bar. This replaces a three-voice figure measured before
        // CICADA existed and before the interpolator changed. The paragraph
        // below was measured under the old linear interpolator and is not
        // re-verified here, but the property it documents - the metric
        // inverting mid-sweep - is a property of one-period correlation
        // during a sweep, independent of interpolation method.
        //
        // STALE for PLATE as of Task 4 (D2): PLATE no longer shares REED's
        // formula, so it no longer shares REED's number either. Its own k(t)
        // is tied to amp.at(t) (t60 derived from this fixture's DECAY 0.7,
        // ~0.67s) rather than the fixed BLOOM_T60 (0.45s) the shared sweep
        // used - slower to settle, so more of it is still moving at this
        // fixed fromSec=0.35 probe. Measured 2026-09-27, PLATE only, BLOOM 1,
        // at this test's own 4 PEAK settings: 0.9999127 (PEAK 0.1), 0.9995182
        // (0.35), 0.99806124 (0.6), 0.9883967 (0.9) - still comfortably above
        // the 0.98 bar. REED's own 0.99940187 is unaffected (its code did not
        // change). BLOOM 0 is unaffected for PLATE too (bloomAmount 0 makes
        // its branch identical to the shared sweep - see `PLATE at BLOOM zero
        // does not move its formant`'s own KDoc), so those four readings are
        // still the pre-Task-4 ones.
        //
        // 0.9883967 (PEAK 0.9) is NOT PLATE's true worst reading, though -
        // only the worst among the four PEAK values this test happens to
        // sample. A supplementary sweep (PEAK 0.85 to 1.0 in finer steps,
        // same still, same fromSec, 2026-09-27) found the real minimum
        // off-grid: correlation keeps falling past PEAK 0.9, bottoms out at
        // 0.9824176 (PEAK 0.97, kBase 36.56193) - only 0.0024 above the 0.98
        // floor, about 3.5x less margin than the in-grid figure suggests -
        // then recovers sharply (0.9934069 at PEAK 0.98, 0.99998647 at PEAK
        // 1.0). The recovery is not a coincidence: kCeilingFor(PLATE) is 40,
        // and `kBase * (1 + bloomAmount * x) >= kBase` for any `x >= 0`, so
        // once `kBase` reaches 40 the per-sample `coerceIn(K_MIN, kCeiling)`
        // clamp binds for the ENTIRE render regardless of `amp.at(t)` - the
        // coupling goes fully inert at PEAK 1 (see `every voice renders
        // clean audio`'s all-BLOOM-1-macros-at-1 case, which is
        // byte-identical whether PLATE's branch reads `amp.at(t)` or the old
        // `envAt(t, BLOOM_T60)`). The minimum's position - PEAK 0.97 rather
        // than closer to the PEAK-1 boundary - traces to the clamp itself.
        // At BLOOM 1, bloomAmount = BLOOM_MAX = 3, so k(t) = kBase *
        // (1 + 3 * amp(t)), clamped at kCeilingFor(PLATE) = 40. For a kBase
        // near that ceiling the clamp is BOUND early in the note - flat,
        // therefore perfectly periodic - and RELEASES at the instant t*
        // solving kBase * (1 + 3 * amp(t*)) = 40. Solving for the kBase
        // whose release lands exactly at the probe window's own start
        // (0.35s) gives a critical kBase ~36.99, i.e. PEAK ~0.974 - which
        // is why the measured minimum sits at the PEAK 0.97 grid point
        // (kBase 36.56193, the nearest sample below that true continuous
        // minimum). Below that kBase the release happens well before the
        // window, so the drift inside it is smaller; above it the release
        // happens inside the window itself and part of the window is
        // shielded by the flat clamp - the dip-then-recovery shape the
        // four grid readings above trace out. This is an artifact of three
        // fixed constants - kCeilingFor's 40, BLOOM_MAX's 3, and the probe
        // window's 0.35s start - so changing any of them moves the
        // minimum.
        // This test still passes at every PEAK it actually samples, but the
        // true margin near PLATE's own ceiling is thin enough that a future
        // change to this fixture (or to BLOOM_MAX, kCeilingFor, or the probe
        // window) could cross it; a lag-domain measure (see this test's own
        // KDoc above) would settle the question instead of sampling around
        // it.
        //
        // The during-sweep regime (fromSec=0.05, the old default) is
        // deliberately NOT asserted here. Measured worst case there is
        // 0.4580 (BOTTLE, PEAK 0.6, BLOOM 1) - *below* the 0.73149 drift
        // control, not above it, so no bar can separate correct from broken
        // in that window. The reason is structural, not a tuning problem: a
        // one-period Pearson correlation measures whether consecutive
        // cycles have the same *shape*. A strong, genuine sweep changes
        // shape rapidly cycle to cycle while leaving the period exact - that
        // scores 0.458. A 9% pitch drift changes both shape and period -
        // that scores 0.73. The correct engine scores worse than the broken
        // control, so the metric inverts mid-sweep; it is only a valid
        // period test where the spectrum is quasi-static. This is a
        // property of the measure, not of GLINT: the period remains exact
        // by construction regardless of window - `phase` advances by
        // `f0 / rate` and wraps at 1.0 independently of `k`, so BLOOM can
        // never drag pitch even while this test is silent about the sweep
        // itself.
        //
        // D2 needs a lag-domain measure here - where the autocorrelation
        // peak sits, rather than how similar two cycles look - since that
        // is shape-insensitive and stays valid during a sweep. It has to be
        // built there regardless: PLATE and RATCHET both move k in new
        // ways that this test's settled-window-only approach won't cover.
        // == 0.35 at the current BLOOM_T60 (0.45); expressed as a fraction
        // of BLOOM_T60 rather than that literal so it tracks BLOOM_T60 if
        // D2 changes it (see comment above).
        val settledFromSec = Glint.BLOOM_T60 * (7f / 9f)
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0f, 1f)) {
                val still = mapOf("TUNE" to 0.5f, "BLOOM" to bloom, "BODY" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.7f)
                val f0 = Glint.frequencyFor(voice, 0.5f)
                for (peak in listOf(0.1f, 0.35f, 0.6f, 0.9f)) {
                    val snip = Glint.render(voice, still + ("PEAK" to peak))
                    val corr = periodCorrelation(snip, f0, fromSec = settledFromSec)
                    assertTrue(corr > 0.98f, "$voice at PEAK $peak BLOOM $bloom: period broke, correlation $corr")
                }
            }
        }
    }

    @Test
    fun `the ratio snaps to harmonics inside the floor-to-ceiling band and runs free outside it`() {
        // Between K_MIN (2) and SNAP_FLOOR (3) there is only one integer, so
        // snapping that range quantises nothing - it flattens everything
        // onto 2, which is what collapsed velocity's floor-scaled layer onto
        // the full-strength one below about PEAK 0.06. Below the floor runs
        // free, same as above the ceiling.
        for (k in listOf(2.1f, 2.4f, 2.9f)) {
            assertEquals(k, Glint.snapRatio(k), 1e-6f, "k=$k should run free (below SNAP_FLOOR)")
        }
        for (k in listOf(3.2f, 3.7f, 11.6f)) {
            assertEquals(Math.round(k).toFloat(), Glint.snapRatio(k), 1e-6f, "k=$k should snap")
        }
        for (k in listOf(12.7f, 23.4f, 39.1f)) {
            assertEquals(k, Glint.snapRatio(k), 1e-6f, "k=$k should run free (above SNAP_CEILING)")
        }
    }

    @Test
    fun `PEAK values inside one snap zone render identically`() {
        // Snapping is real, not cosmetic: two PEAK settings that land on the
        // same harmonic must produce the same bytes.
        val voice = GlintVoice.BOTTLE
        val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "FOLLOW" to 1f)
        fun ratioAt(peak: Float) = Glint.snapRatio(Glint.ratioAtReference(peak, voice).coerceIn(Glint.K_MIN, Glint.kCeilingFor(voice)))
        val pairs = (0..100).map { it / 100f }.groupBy { ratioAt(it) }.values.firstOrNull { it.size >= 2 }
        assertTrue(pairs != null, "expected at least one snap zone with two PEAK values in it")
        val a = Glint.render(voice, still + ("PEAK" to pairs!!.first()))
        val b = Glint.render(voice, still + ("PEAK" to pairs.last()))
        assertTrue(a.samples.contentEquals(b.samples), "same snapped harmonic must render the same bytes")
    }

    /** Energy at exactly [hz] in [samples], by Goertzel. Measures a named frequency — nothing to tune. */
    private fun energyAt(samples: FloatArray, hz: Float, sampleRate: Int, from: Int = 0, n: Int = 16384): Float {
        val count = minOf(n, samples.size - from)
        val w = 2.0 * kotlin.math.PI * hz / sampleRate
        val coeff = 2.0 * kotlin.math.cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until count) {
            val s = samples[from + i] + coeff * s1 - s2
            s2 = s1; s1 = s
        }
        return ((s1 * s1 + s2 * s2 - coeff * s1 * s2) / count).toFloat()
    }

    @Test
    fun `PEAK pins the formant on the named harmonic, not just relatively`() {
        // Every other spectral assertion in this suite is relative -
        // centroids and band shares compared against each other. A
        // constant-factor error in the burst (say `sin(2*pi*2k*phase)`
        // instead of `sin(2*pi*k*phase)`) would pass all of them. This pins
        // the spec's central claim directly: integers land the peak exactly
        // on a harmonic (k=3 is the octave-and-a-fifth) - by measuring
        // energy at named frequencies with a Goertzel filter, which has
        // nothing to tune and cannot be fooled by a doubled or halved
        // formant the way a centroid could.
        //
        // BLOOM held at 0 so k is constant through the render; BODY at 0 so
        // the body's own harmonics don't muddy the comparison; k=3 chosen
        // because it is below SNAP_CEILING (so it snaps to an exact
        // integer) and is the spec's own worked example.
        //
        // Measured 2026-09-26 (energy ratio, peak-to-decoy):
        //   REED   atK/at2k = 204x   atK/at(k/2) = 23810x
        //   BOTTLE atK/at2k = 625x   atK/at(k/2) = 384935x
        //   KAZOO  atK/at2k = 456x   atK/at(k/2) = 561187x
        // The doubled-formant decoy is the tight one (a real harmonic of f0,
        // just the "wrong" one) at 204x worst case; the halved decoy isn't
        // a harmonic of f0 at all here (1.5*f0) so it reads near noise
        // floor. 20x is comfortably inside the 204x floor - roughly an
        // order of magnitude of margin - while still being a bar a doubled
        // or halved formant could not pass.
        // CICADA (Task 2, D2) re-clocks the carrier CICADA_SUBCYCLES times a
        // cycle, so its real formant sits at k * CICADA_SUBCYCLES * f0, not
        // k * f0 - probing the plain k * f0 location measured near-noise
        // energy for both "atK" and "atDoubled" and failed (6.02E-8 vs
        // 5.73E-7 - the wrong location can't even keep atK the larger of the
        // two). carrierMul folds that in for CICADA only; it is 1 for every
        // other voice, so their probes are unchanged.
        val stillTune = 0.5f
        for (voice in GlintVoice.entries) {
            val peak = (0..2000).map { it / 2000f }.first { Glint.ratioFor(voice, stillTune, it, 1f) == 3f }
            val k = Glint.ratioFor(voice, stillTune, peak, 1f)
            assertEquals(3f, k, 1e-6f, "$voice: test setup expected k=3")
            val carrierMul = if (voice == GlintVoice.CICADA) Glint.CICADA_SUBCYCLES.toFloat() else 1f
            val f0 = Glint.frequencyFor(voice, stillTune)
            val still = mapOf("TUNE" to stillTune, "BLOOM" to 0f, "BODY" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            val snip = Glint.render(voice, still + ("PEAK" to peak))
            val atK = energyAt(snip.samples, k * f0 * carrierMul, snip.sampleRate)
            val atDoubled = energyAt(snip.samples, 2f * k * f0 * carrierMul, snip.sampleRate)
            val atHalved = energyAt(snip.samples, k * f0 * carrierMul / 2f, snip.sampleRate)
            assertTrue(
                atK > atDoubled * 20f,
                "$voice: energy at k*f0 ($atK) should dwarf energy at 2k*f0 ($atDoubled) - formant may be doubled",
            )
            assertTrue(
                atK > atHalved * 20f,
                "$voice: energy at k*f0 ($atK) should dwarf energy at k*f0/2 ($atHalved) - formant may be halved",
            )
        }
    }

    @Test
    fun `render dispatches through the oversampled path, not directly at RATE`() {
        // The mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX/RESIN carry —
        // but it only means anything at the TOP of the sweep. Simulated
        // 2026-09-25 before this plan was written: at k=8 and mid TUNE the
        // diff is 0.00007 (REED), 0.00000 (BOTTLE), 0.00004 (KAZOO) — the
        // burst is nowhere near Nyquist and oversampling changes nothing.
        // At PEAK 1 and TUNE 1, k is 40 and k*f0 reaches 17.6 kHz — measured
        // (not simulated) 2026-09-26: REED 0.0027, BOTTLE 0.0408, KAZOO
        // 0.0459. REED is the tight one, hence the 0.001 threshold rather
        // than the 0.002 other engines use.
        val corner = mapOf("PEAK" to 1f, "TUNE" to 1f, "BLOOM" to 0f, "BODY" to 0f, "FOLLOW" to 1f)
        for (voice in GlintVoice.entries) {
            val actual = Glint.render(voice, corner)
            val direct = Glint.synthesize(voice, corner, Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.001, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }

    @Test
    fun `a non-integer ratio clicks no more than an integer one - the window's promise`() {
        // Above SNAP_CEILING k is continuous, so this is where a click would
        // show. Compare the largest sample-to-sample step at a deliberately
        // non-integer k against an integer one; a discontinuity at the wrap
        // would spike the non-integer case.
        //
        // Measured on the RAW oversampled buffer, not on render()'s output:
        // Dsp.decimate low-passes to the output Nyquist, which is precisely
        // the filter that would smooth a wrap discontinuity away before it
        // could be seen. The window's promise lives before that filter.
        //
        // Off-harmonic target is 0.25, not 0.5, as of 2026-09-26: the
        // discontinuity this test exists to catch is w(1-)*|sin(2*pi*k)|, and
        // |sin(2*pi*k)| is 0 at BOTH a fractional part of 0.0 (integer k) and
        // 0.5 (half-integer k) - sin(pi*n) is 0 either way. A window broken
        // to end at 0.5 instead of exactly 0 (a total violation of the
        // engine's founding property) therefore lands on silence at the wrap
        // regardless of what the window does, and the test was blind to
        // exactly the defect it names. Measured with REED's window forced to
        // `1f - 0.5f*p` (linear, ending at 0.5 instead of 0): at the old 0.5
        // target, onHarmonic=0.2856 offHarmonic=0.2260 - the broken window
        // passes clean, ratio 0.79, not even close to failing. At 0.25,
        // where |sin(2*pi*k)| = 1, the same break measures
        // onHarmonic=0.2856 offHarmonic=0.4380, ratio 1.53 > the 1.5x bar -
        // FAILS, as it must. With the real window restored and the 0.25
        // target, REED measures onHarmonic=0.2839 offHarmonic=0.1244,
        // BOTTLE onHarmonic=0.5785 offHarmonic=0.2510, KAZOO
        // onHarmonic=0.5802 offHarmonic=0.2591 - all comfortably inside the
        // 1.5x bar. 1.5f stays the right comparison factor: it did not need
        // to move, only the target that decides which k's get compared.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "BODY" to 0f, "FOLLOW" to 1f)
            fun maxStep(peak: Float): Float {
                val s = Glint.synthesize(voice, still + ("PEAK" to peak), rate)
                var worst = 0f
                for (i in 1 until s.size) {
                    val d = kotlin.math.abs(s[i] - s[i - 1])
                    if (d > worst) worst = d
                }
                return worst
            }
            // Find a PEAK landing closest to an integer ratio in a free
            // (unsnapped) region, and one landing closest to a fractional
            // part of 0.25, where |sin(2*pi*k)| = 1 and a broken window has
            // nowhere to hide. minByOrNull rather than first{tolerance}: the
            // ratio map is exponential, so step size near the top is coarse
            // and a fixed tolerance can miss entirely and throw instead of
            // failing.
            //
            // The free region above SNAP_CEILING only exists while the
            // voice's own ceiling clears it. CICADA's kCeilingFor (10) sits
            // below SNAP_CEILING (12) - see kCeilingFor's doc - so above the
            // ceiling is not a free region for CICADA at all; every PEAK
            // there would snap to CICADA's own ceiling. The region below
            // SNAP_FLOOR is free for every voice regardless (there is only
            // one integer there to snap to), so CICADA uses that one
            // instead - the same region the dedicated CICADA click test
            // uses for the same reason.
            val candidates = if (Glint.kCeilingFor(voice) > Glint.SNAP_CEILING + 1f) {
                (0..4000).map { it / 4000f }.filter { Glint.ratioAtReference(it, voice) > Glint.SNAP_CEILING + 1f }
            } else {
                (0..4000).map { it / 4000f }.filter { Glint.ratioAtReference(it, voice) < Glint.SNAP_FLOOR }
            }
            // CICADA's below-floor region is only [K_MIN, SNAP_FLOOR) = [2, 3),
            // narrow enough to check the two searches don't collapse onto the
            // same point (which would make the comparison vacuous even though
            // it passes). Measured: onHarmonic lands at PEAK 0.0 (k=2.0 exactly,
            // fractional part 0 - the region's own lower bound), offHarmonic at
            // PEAK 0.07325 (k=2.2502437, fractional part ~0.25) - distinct.
            val onHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it, voice) % 1f - 0f) }!!
            val offHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it, voice) % 1f - 0.25f) }!!
            assertTrue(
                maxStep(offHarmonic) < maxStep(onHarmonic) * 1.5f,
                "$voice: a fractional ratio must not click — ${maxStep(offHarmonic)} vs ${maxStep(onHarmonic)}",
            )
        }
    }

    @Test
    fun `FOLLOW 1 rides the note and FOLLOW 0 stands still`() {
        for (voice in GlintVoice.entries) {
            val lowTune = 0.1f
            val highTune = 0.9f
            // Tracking: the ratio is the same at both notes, so the peak's Hz
            // scales with the note.
            val rideLow = Glint.ratioFor(voice, lowTune, 0.5f, 1f)
            val rideHigh = Glint.ratioFor(voice, highTune, 0.5f, 1f)
            assertEquals(rideLow, rideHigh, 1e-3f, "$voice at FOLLOW 1: the ratio must not change with the note")

            // Parked: the peak's Hz is the same at both notes, so the ratio
            // falls as the note rises.
            val parkLowHz = Glint.ratioFor(voice, lowTune, 0.5f, 0f) * Glint.frequencyFor(voice, lowTune)
            val parkHighHz = Glint.ratioFor(voice, highTune, 0.5f, 0f) * Glint.frequencyFor(voice, highTune)
            assertEquals(parkLowHz, parkHighHz, parkLowHz * 0.02f, "$voice at FOLLOW 0: the peak's Hz must not move")
        }
    }

    @Test
    fun `FOLLOW changes what the render measures, not only what the math says`() {
        // The centroid climbs with the note when the peak tracks it, and
        // stays put when the peak is parked. Measured on BOTTLE, whose
        // triangle window leaves the burst most exposed in the spectrum.
        //
        // Measured 2026-09-26 at PEAK 0.5 (ratio 8.94, well below
        // SNAP_CEILING). Re-checked after the velocity-floor fix added
        // SNAP_FLOOR: unchanged here, since 8.94's neighbourhood is nowhere
        // near the floor's dead zone. At FOLLOW=1, keyTrack cancels the note
        // dependence outright, so both notes land on the same snapped
        // harmonic (k=9) regardless of TUNE — the effect below is visible
        // without raising PEAK because it's the absolute Hz that climbs with
        // the note, not the ratio: ridesLow=2222.7 Hz, ridesHigh=7056.7 Hz
        // (3.17x, clears the >1.8x bar). At FOLLOW=0 the two notes do NOT
        // land on the same harmonic — the peak's Hz is fixed instead, so the
        // ratio falls as the note rises (k=15.9 at TUNE 0.1, above
        // SNAP_CEILING and running free; k=5 at TUNE 0.9, snapped) — but the
        // point of the parked case is that the absolute Hz holds flat
        // despite that: parkedLow=3935.5 Hz, parkedHigh=3922.6 Hz (0.997x,
        // clears the <1.35x bar) — the snap did not hide the effect here.
        val voice = GlintVoice.BOTTLE
        val still = mapOf("PEAK" to 0.5f, "BLOOM" to 0f, "BODY" to 0.2f, "DECAY" to 0.6f)
        fun centroid(tune: Float, follow: Float) = FeatureExtractor.extract(
            Glint.render(voice, still + ("TUNE" to tune) + ("FOLLOW" to follow)),
        ).centroidHz

        val ridesLow = centroid(0.1f, 1f)
        val ridesHigh = centroid(0.9f, 1f)
        assertTrue(ridesHigh > ridesLow * 1.8f, "FOLLOW 1 should carry the peak up with the note: $ridesLow -> $ridesHigh")

        val parkedLow = centroid(0.1f, 0f)
        val parkedHigh = centroid(0.9f, 0f)
        assertTrue(
            parkedHigh < parkedLow * 1.35f,
            "FOLLOW 0 should leave the peak where it was: $parkedLow -> $parkedHigh",
        )
    }

    @Test
    fun `the ratio floor holds at every note and every FOLLOW`() {
        // k below 2 is fewer than two burst cycles in the window: no peak,
        // just a dull fragment. The clamp must be unconditional.
        for (voice in GlintVoice.entries) {
            for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (follow in listOf(0f, 0.5f, 1f)) {
                    for (peak in listOf(0f, 0.5f, 1f)) {
                        val k = Glint.ratioFor(voice, tune, peak, follow)
                        assertTrue(
                            k >= Glint.K_MIN - 1e-4f && k <= Glint.kCeilingFor(voice) + 1e-4f,
                            "$voice tune=$tune follow=$follow peak=$peak gave k=$k",
                        )
                    }
                }
            }
        }
    }

    /** [snip] between two times, mono, for a windowed measurement. */
    private fun slice(snip: Snip, fromSec: Float, toSec: Float): Snip {
        val a = (fromSec * snip.sampleRate).toInt().coerceIn(0, snip.samples.size)
        val b = (toSec * snip.sampleRate).toInt().coerceIn(a, snip.samples.size)
        return Snip(snip.samples.copyOfRange(a, b), 1, snip.sampleRate)
    }

    /** Share of energy below 2 kHz — where BODY's fundamental lands, whatever the voice's root. */
    private fun lowMid(snip: Snip): Float =
        FeatureExtractor.extract(snip).let { it.lowRatio + it.midRatio }

    @Test
    fun `BODY adds a second formant, not a copy of the first`() {
        // The old BODY mixed the mean-removed window back in — the same
        // harmonic series the burst already carries, spread down from k*f0.
        // Four reviewers independently found it inaudible for that reason,
        // and Josh's audition agreed: "Body 1 doesn't really seem to have an
        // impact." The replacement is a genuine second burst at k/5.6, so
        // there is energy near k2*f0 that BODY 0 does not have at all.
        //
        // CICADA's body rides the re-clocked carrier the same way its main
        // burst does (see `synthesize`'s comment on why BODY shares `carrier`
        // rather than `phase`), so its real second formant sits at
        // k2*CICADA_SUBCYCLES*f0, not k2*f0 — the same carrierMul correction
        // `PEAK pins the formant on the named harmonic` already applies.
        // Probing the plain k2*f0 = 880 Hz location for CICADA missed the
        // real 3,520 Hz formant: reviewer-measured 17.0x there (leakage,
        // barely over the 3x bar) against 1855x at the corrected location.
        // Identity for every other voice.
        //
        // Measured 2026-09-27 (bare -> full, energy at k2*f0*carrierMul), all
        // six voices — REED/BOTTLE/KAZOO were previously recorded 2026-09-26
        // for three voices only, and REED's had already gone stale
        // (0.12219116 recorded, 0.12315088 measured) from engine changes
        // since:
        //   REED    3.951246E-4 -> 0.12315088   (312x)
        //   BOTTLE  7.350461E-9 -> 0.038122714  (5186438x)
        //   KAZOO   5.071703E-5 -> 0.047637813  (939x)
        //   CICADA  0.008937839 -> 16.5795      (1855x)
        //   RATCHET 5.071703E-5 -> 0.047637813  (939x)
        //   PLATE   3.951246E-4 -> 0.12315088   (312x)
        // All clear the 3x bar by a wide margin.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.8f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            val f0 = Glint.frequencyFor(voice, 0.5f)
            val k = Glint.ratioFor(voice, 0.5f, 0.8f, 1f)
            val k2 = Glint.bodyRatio(k)
            val carrierMul = if (voice == GlintVoice.CICADA) Glint.CICADA_SUBCYCLES.toFloat() else 1f
            val bareSnip = Glint.render(voice, still + ("BODY" to 0f))
            val fullSnip = Glint.render(voice, still + ("BODY" to 1f))
            val probeHz = k2 * f0 * carrierMul
            val bare = energyAt(bareSnip.samples, probeHz, bareSnip.sampleRate)
            val full = energyAt(fullSnip.samples, probeHz, fullSnip.sampleRate)
            assertTrue(full > bare * 3f, "$voice: BODY should put real energy at $probeHz Hz ($bare -> $full)")
        }
    }

    @Test
    fun `BODY carries a small, bounded DC that decays with the envelope`() {
        // A windowed sine is NOT DC-free in general — only a window
        // symmetric about phase 0.5 nulls integral(w(phi) * sin(2*pi*k*phi), phi, 0, 1),
        // and of GLINT's three windows only BOTTLE's triangle is symmetric
        // that way. REED's ramp (w = 1-phi) integrates to 1/(2*pi*k) for
        // every integer k, and KAZOO's trapezoid is asymmetric the same
        // way. Mean-removing BODY to cancel that residual would stop it
        // reaching exactly zero at the cycle wrap — the property the class
        // doc calls "the whole engine" — so it is left in deliberately, and
        // this test's job is to show the residual is small and decaying,
        // not to claim it is absent.
        //
        // Averaging the whole buffer (the old form of this test) can't see
        // that: the decayed tail is far longer and far quieter than the
        // head, so it dominates the mean and reads ~0.0044 regardless of
        // BODY — a bound that never moves is not testing anything. This
        // version measures the head window instead, where the DC is
        // actually largest.
        //
        // Measured 2026-09-26: DC over the first 50ms, as a fraction of
        // that window's own peak, at BLOOM 0, BODY 0 -> 1:
        //   REED   0.0136 -> 0.0340
        //   KAZOO  0.0129 -> 0.0321
        //   BOTTLE 1.4E-6 -> 1.2E-5  (triangle window, nulls as expected)
        // 0.05 sits above the worst measured ratio (0.0340) with real
        // margin, but is still tight enough to bite: mutation-verified by
        // temporarily adding a constant to the `body` assignment in
        // Glint.synthesize (`... .toFloat() + <offset>`, a raw per-sample
        // value added before it's scaled by bodyEnv - not the same unit as
        // the head-window DC ratio above, though the render's peak
        // normalization to ~0.99 puts them in the same ballpark) and
        // bisecting the offset. +0.04f fails, +0.03f passes - so the
        // smallest offset this bound catches lies between 0.03 and 0.04.
        // Reverted after each run.
        //
        // PEAK is deliberately left at its default and NOT swept, and the
        // 0.05 bound is only valid there. The burst carries this same
        // window asymmetry and carries it worse at low k: swept to PEAK 0,
        // KAZOO reads 0.0619 with BODY at 0 — i.e. over the bound with the
        // body term switched off entirely. That is the burst's own DC, not
        // a defect and not BODY's doing, but a PEAK sweep added here would
        // fail this test and point at the wrong component. Widening the
        // sweep means re-deriving the bound per PEAK first.
        for (voice in GlintVoice.entries) {
            for (body in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val snip = Glint.render(voice, mapOf("BODY" to body, "BLOOM" to 0f))
                val head = slice(snip, 0f, 0.05f)
                val dc = head.samples.average().toFloat()
                val peak = head.peak()
                val ratio = if (peak > 0f) dc / peak else 0f
                assertTrue(
                    kotlin.math.abs(ratio) < 0.05f,
                    "$voice at BODY $body has head-window DC ratio $ratio (dc=$dc, peak=$peak)",
                )
            }
        }
    }

    @Test
    fun `BODY is audible as a second source`() {
        // Measured as low+mid share, not centroid: centroidHz is dominated by
        // the burst, so the body evaporating moves it only 2-8% (1.068 /
        // 1.081 / 1.022) — and lowering BODY_DECAY_RATIO makes that WORSE,
        // not better. The share below 2 kHz is what actually changes.
        //
        // A head-vs-tail-within-each-condition version of this test (with a
        // one-sided control bounding only growth, `flatTail <= flatHead *
        // 1.5f`) does not discriminate for BOTTLE: its BODY-0.02 control
        // itself declines 3.04x from head to tail, already past the 1.4x
        // bar with no body term doing anything. A dead body term would still
        // pass. REED and KAZOO's controls sit at parity, so their old
        // signal ratios were real, but BOTTLE's was not — do not restore
        // that shape. This version compares WITH-body against WITHOUT-body
        // at the *same* time offset instead, which cancels out whatever
        // intrinsic decline a voice has, so the head assertion below cannot
        // be satisfied by a body term that does nothing.
        //
        // This test used to also assert the body burns off by the tail
        // (`withTail < withoutTail * 1.5f`). That assertion is deliberately
        // REMOVED, not tuned to pass: the spec bundled two incompatible
        // claims — BODY_DECAY_RATIO 0.8, single-enveloped (Tomita's slower
        // resonance ringing after the strike) and "the body burns off"
        // (the glass tail) — and the old body's double envelope composed
        // to an effective ~0.31x t60, so fixing the envelope (Ruling A) made
        // the body 2.6x slower while the spec still expected it to vanish.
        // Those cannot both be true, and which one is correct is a design
        // question for the D1 audition to settle by ear, not a number this
        // test should assert.
        //
        // Measured 2026-09-26 (same-offset comparison, after Ruling A's
        // single-envelope body fix), all four figures per voice — head
        // proves the source, tail is recorded for the audition, not asserted:
        //   REED   withHead=0.3323 withoutHead=0.1441 (head 2.31x)
        //          withTail=0.1793 withoutTail=0.1435 (tail 1.25x - burns off)
        //   BOTTLE withHead=0.3581 withoutHead=0.01257 (head 28.5x)
        //          withTail=0.1216 withoutTail=0.00414 (tail 29.4x - rings the whole note)
        //   KAZOO  withHead=0.3337 withoutHead=0.05831 (head 5.72x)
        //          withTail=0.1215 withoutTail=0.05713 (tail 2.13x - only partly burns off)
        // The head ratio clears the 1.5x bar for all three, so BODY is a
        // real second source everywhere. The tail ratio is a genuine,
        // per-voice split, not test noise: REED's second formant burns off
        // (1.25x, near parity with no-body), KAZOO's only partly does
        // (2.13x), and BOTTLE's does not burn off at all across the note
        // (29.4x, same order as its own head ratio) — it rings the whole
        // note through, closer to Tomita's resonance than to a glass tail.
        // Verified this test actually bites: with `bodyMix` temporarily
        // forced to 0f in `synthesize`, the head assertion failed
        // immediately for REED (0.1432154 -> 0.1432154, identical) -
        // confirming a dead body term cannot pass. Reverted before
        // committing; this is a test-only file.
        //
        // BODY_DECAY_RATIO was swept 0.15 to 5.0 while chasing the old
        // head-vs-tail bar before it was known to be the wrong instrument:
        // lowering it (the pre-authorised direction) makes the differential
        // WORSE, not better, because a faster-decaying body has less energy
        // left in the head window. Do not retry that; the fix was the
        // metric, not the constant — and per the ruling above, the tail
        // behaviour itself is now an open design question, not a bug to
        // chase with this constant.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.75f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            fun shares(body: Float): Pair<Float, Float> {
                val snip = Glint.render(voice, still + ("BODY" to body))
                return lowMid(slice(snip, 0f, 0.1f)) to
                    lowMid(slice(snip, snip.durationSeconds * 0.6f, snip.durationSeconds))
            }
            val (withHead, _) = shares(0.9f)
            val (withoutHead, _) = shares(0.02f)
            // The body is plainly there at the strike - a real second source.
            // Whether it burns off or rings on by the tail is a per-voice
            // design question for the D1 audition (see the comment above),
            // not asserted here.
            assertTrue(withHead > withoutHead * 1.5f, "$voice: BODY should be audible at the head ($withoutHead -> $withHead)")
        }
    }

    /**
     * Voices whose BLOOM sweeps the formant open at the attack and lets it
     * fall back and settle near its base ratio, per the fixed [BLOOM_T60]
     * rate — the shared assertion both BLOOM tests below apply to this
     * group. RATCHET's ladder climbs instead of sweeping down and never
     * settles inside a fixed t60 (see each test's own RATCHET branch for
     * its measured numbers), so it is deliberately left out of this set
     * rather than folded in under a weakened bar.
     *
     * PLATE (Task 4, D2) has its OWN formant motion now — `k(t) = kBase *
     * (1 + BLOOM * amp.at(t))`, no [BLOOM_T60], no clock of its own — and it
     * stays in this set on measured numbers, not by assumption. At the
     * `BLOOM opens the peak at the attack and lets it settle` fixture (TUNE
     * 0.3 PEAK 0.4 BODY 0.2 FOLLOW 1 DECAY 0.7), PLATE's own head/tail
     * ratios are 1.0013 at BLOOM 0 and 3.6967943 at BLOOM 1 — both clear
     * this set's bars, and BLOOM 1's is even wider than the shared-formula
     * figure it replaced (3.5664227), not narrower.
     *
     * The reason is structural, not coincidence: `Dsp.Env.at`'s decay
     * always reaches roughly -60 dB (0.1% of peak) by one t60, and
     * `synthesize`'s own `frames = t60 * 1.35 * rate` guarantees the
     * rendered note is 1.35x that t60 long — so PLATE's coupling is
     * mathematically certain to have collapsed back to `kBase` well before
     * this set's 0.7x-to-1.0x-duration tail window, for ANY DECAY. That is
     * exactly what `BLOOM lands before the note ends, whatever it did on the
     * way`'s own KDoc measures below, and exactly what RATCHET's ladder does
     * NOT get for free — its reach is set by BLOOM alone, independent of
     * DECAY, which is why IT needed its own branch instead of joining this
     * set.
     */
    private val BLOOM_SWEEPS_AND_SETTLES = setOf(
        GlintVoice.REED, GlintVoice.BOTTLE, GlintVoice.KAZOO, GlintVoice.CICADA, GlintVoice.PLATE,
    )

    /**
     * Measured 2026-09-26 (Task 2, fixed BLOOM_T60 = 0.45s), still = TUNE 0.3
     * PEAK 0.4 BODY 0.2 FOLLOW 1 DECAY 0.7 (duration 0.9044s for all three
     * voices), head/tail centroid ratio:
     *   REED   BLOOM 0 -> 1.0013   BLOOM 1 -> 3.5664
     *   BOTTLE BLOOM 0 -> 0.9899   BLOOM 1 -> 3.4596
     *   KAZOO  BLOOM 0 -> 0.9929   BLOOM 1 -> 3.4975
     * BLOOM 1's ratio nearly doubled from the old coupling's 1.79-1.89
     * (BLOOM_FAST_T60 0.06s) to 3.46-3.57: the slower fixed rate leaves k
     * much closer to its peak at the 0-12ms head window, since envAt(0.012,
     * 0.45) is still ~0.94 against the old envAt(0.012, 0.06) of ~0.15.
     * Every BLOOM 1 case clears the 1.4x bar by well over 2x margin; every
     * BLOOM 0 case sits at parity (0.99-1.00), well inside the 1.2x bar.
     * CICADA renders through this same formula (see [BLOOM_SWEEPS_AND_SETTLES]'s
     * doc) and is not separately re-measured here; it already passed this
     * bar before RATCHET existed to break it.
     *
     * PLATE (Task 4, D2) no longer shares this formula — its own `k(t) =
     * kBase * (1 + BLOOM * amp.at(t))` is measured separately. At this same
     * still, 2026-09-27: BLOOM 0 -> 1.0013 (identical to the figure above:
     * BLOOM 0 makes `bloomAmount` 0, which zeroes out either curve the same
     * way — see `PLATE at BLOOM zero does not move its formant`'s own KDoc),
     * BLOOM 1 -> 3.6967943 - slightly WIDER than the shared-formula figure
     * it replaces (3.5664227), not narrower, because DECAY 0.7 gives
     * `amp.at`'s own t60 (~0.67s) a slower fall than the fixed BLOOM_T60
     * (0.45s) the old sweep used, so PLATE's head window sits closer to its
     * own opened peak. Clears both the 1.4x and 1.2x bars with room, so
     * PLATE stays in [BLOOM_SWEEPS_AND_SETTLES].
     *
     * RATCHET climbs instead of sweeping, so its bar is inverted — the tail
     * must end up BRIGHTER than the attack, not darker. Measured 2026-09-27
     * at this same still (RATCHET's kBase here is 7.0, the same reference
     * ratio KAZOO gets at these macros, since the two share rootHz and
     * kCeilingFor): BLOOM 1 head/tail = 0.6299082 (head 2269.1367 Hz, tail
     * 3602.329 Hz — still climbing at the tail; see the next test's RATCHET
     * branch for why); BLOOM 0 head/tail = 0.9929 (head 2269.1353 Hz, tail
     * 2285.2646 Hz) — a one-rung ladder never climbs, so head and tail
     * agree the same way every sweeping voice's own BLOOM-0 case does.
     */
    @Test
    fun `BLOOM opens the peak at the attack and lets it settle`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun headToTail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                val head = FeatureExtractor.extract(slice(snip, 0f, 0.012f)).centroidHz
                val tail = FeatureExtractor.extract(
                    slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds),
                ).centroidHz
                return head / tail
            }
            when {
                voice in BLOOM_SWEEPS_AND_SETTLES -> {
                    assertTrue(headToTail(1f) > 1.4f, "$voice BLOOM 1 should open the head well above the tail: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) < 1.2f, "$voice BLOOM 0 should leave head and tail alike: ${headToTail(0f)}")
                }
                voice == GlintVoice.RATCHET -> {
                    // "Ends brighter than it began" is the climbing ladder's
                    // version of "opens at the attack and settles" — the
                    // direction is reversed, not merely a weaker bar. See
                    // this test's own KDoc for the measured figures behind
                    // the two thresholds below.
                    assertTrue(headToTail(1f) < 0.8f, "RATCHET BLOOM 1 should end brighter than it began: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) in 0.9f..1.1f, "RATCHET BLOOM 0 should leave head and tail alike: ${headToTail(0f)}")
                }
                else -> error(
                    "$voice has no BLOOM assertion in this test — add it to BLOOM_SWEEPS_AND_SETTLES " +
                        "or give it its own branch, per measured numbers, not by assumption",
                )
            }
        }
    }

    /**
     * Measured 2026-09-26 (Task 2, fixed BLOOM_T60 = 0.45s), same still as
     * above, tail centroid (0.75 * duration to the end) at BLOOM 0 vs BLOOM 1:
     *   REED   1126.3811 Hz -> 1126.4362 Hz (diff 0.0551 Hz)
     *   BOTTLE 2307.8090 Hz -> 2307.9167 Hz (diff 0.1077 Hz)
     *   KAZOO  2285.5515 Hz -> 2285.6560 Hz (diff 0.1045 Hz)
     * At 0.75 * 0.9044s = 0.678s, envAt(0.678, 0.45) is ~3e-5 - the sweep is
     * fully settled by five-plus t60s regardless of which BLOOM value chose
     * the (now fixed) rate, so the two tails still land within the 20% bar.
     * This guard is for [BLOOM_SWEEPS_AND_SETTLES]'s voices (CICADA included,
     * unmeasured here for the same reason as the test above; PLATE's own
     * numbers are below); it does not apply to RATCHET, below.
     *
     * PLATE (Task 4, D2) no longer shares the formula above, but lands here
     * for a structural reason rather than a coincidence — see
     * [BLOOM_SWEEPS_AND_SETTLES]'s own doc. Measured 2026-09-27 at this same
     * still: tail(0) = 1126.3811 Hz, tail(1) = 1128.4126 Hz (diff 2.0315 Hz,
     * ratio 0.18%) - larger than the shared-formula voices' sub-0.1 Hz drift
     * (its own `amp.at` t60, ~0.67s, settles slower than the fixed 0.45s
     * BLOOM_T60 those voices ride), but still two orders of magnitude inside
     * the 20% bar, because 0.75 * duration (0.678s) is itself ~1.01 t60 of
     * PLATE's own DECAY-derived envelope - past the point where any DECAY's
     * amp envelope has collapsed to background level.
     *
     * This comment used to predict "a future voice-specific sweep (D2's
     * PLATE, RATCHET) that runs so slow it never lands before the note's
     * DECAY ends" — anticipating the shape of the problem, if not quite its
     * mechanism. What actually happens is not a slow sweep for either voice:
     * PLATE's coupling is pinned to the note's OWN t60 (so it always lands,
     * per the structural argument above, regardless of how slow or fast
     * DECAY makes the note), and RATCHET's ladder is a staircase that has no
     * reason to return to its starting value at all, and at this fixture
     * climbs far more rungs than a ~0.9s note has time to step through.
     * Measured 2026-09-27, kBase=7, BLOOM 1 -> bloomAmount 3 -> top 28 (a
     * 22-rung ladder): reaching rung 28 takes 21 * RATCHET_STEP_SECONDS =
     * 3.15s, so at this note's own 0.75x-to-1.0x-duration tail window the
     * rung index is still moving, 4 -> 6 of 21 (k 11 -> 13), not holding.
     * RATCHET's own tail(1) = 3605.4092 Hz vs tail(0) = 2285.5515 Hz, ratio
     * 1.578 — nothing like either sweeping mechanism's own tiny drift.
     */
    @Test
    fun `BLOOM lands before the note ends, whatever it did on the way`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun tail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                return FeatureExtractor.extract(
                    slice(snip, snip.durationSeconds * 0.75f, snip.durationSeconds),
                ).centroidHz
            }
            when {
                voice in BLOOM_SWEEPS_AND_SETTLES -> {
                    assertTrue(
                        kotlin.math.abs(tail(1f) - tail(0f)) < tail(0f) * 0.2f,
                        "the sweep must have landed by the tail: ${tail(1f)} vs ${tail(0f)}",
                    )
                }
                voice == GlintVoice.RATCHET -> {
                    // Not "landed": see this test's own KDoc for why a
                    // ladder whose reach outruns the note's DECAY keeps
                    // climbing through the tail window instead of settling
                    // near BLOOM 0's baseline. RATCHET_STEP_SECONDS's own
                    // doc already names the flip side of this — "short
                    // notes render a single rung and the ladder only reads
                    // on longer ones" — a ladder can just as easily be
                    // *longer* than the note, which is this case. The
                    // assertion is direction and margin instead of
                    // "landed near baseline."
                    assertTrue(
                        tail(1f) > tail(0f) * 1.3f,
                        "RATCHET's climbing ladder should still read brighter at the tail than BLOOM 0's flat baseline: ${tail(1f)} vs ${tail(0f)}",
                    )
                }
                else -> error(
                    "$voice has no BLOOM assertion in this test — add it to BLOOM_SWEEPS_AND_SETTLES " +
                        "or give it its own branch, per measured numbers, not by assumption",
                )
            }
        }
    }

    @Test
    fun `BLOOM sweeps slowly enough to hear`() {
        // The rate used to run 0.30 s down to 0.06 s as BLOOM rose — depth
        // and rate on one knob, so a big sweep was always a fast one. At the
        // shipped coupling the centroid fell to 0.92x of its opening value
        // and then sat flat: a control that measured as nearly static and
        // was heard as "I don't get a sense of movement". At a fixed 0.45 s
        // it travels to 0.35x over 300 ms, the one change the 2026-09-26
        // audition marked KEEP.
        //
        // The assertion is the rate itself, at the moment the two constants
        // differ most. At 0.05 s the old 0.06 s sweep was finished
        // (envAt(0.05, 0.06) = 0.003); the new one is still well open.
        // A test that the rate is independent of depth would be tautological
        // now that the rate is a constant — this tests the constant's value.
        // Measured 2026-09-26 at BLOOM_T60 = 0.45s: early=3861.21 Hz,
        // settled=1943.65 Hz (ratio 1.987), well clear of the 1.25x bar.
        // Confirmed this fails hard on the old BLOOM_FAST_T60 = 0.06s value:
        // early=1925.94 Hz vs settled=1943.65 Hz (ratio 0.991 - the old
        // sweep was already fully landed by 50ms, not still open) - checked
        // by temporarily setting bloomT60 to 0.06f, running this test alone,
        // confirming the AssertionFailedError, then reverting.
        val voice = GlintVoice.REED
        val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.5f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.85f)
        val snip = Glint.render(voice, still + ("BLOOM" to 1f))
        val early = FeatureExtractor.extract(slice(snip, 0.04f, 0.08f)).centroidHz
        val settled = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.8f, snip.durationSeconds)).centroidHz
        assertTrue(early > settled * 1.25f, "the sweep should still be open at 50 ms: $early vs settled $settled")
    }

    @Test
    fun `BLOOM at its ugliest still renders legal audio`() {
        // GLINT has no feedback path, so BLOOM is allowed to be ugly at the
        // top — but ugly must still be finite, in range and in tune.
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0f, 0.5f, 1f)) {
                val snip = Glint.render(voice, mapOf("BLOOM" to 1f, "PEAK" to peak, "BODY" to 1f))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice BLOOM 1 PEAK $peak broke range")
                assertTrue(snip.peak() > 0.5f, "$voice BLOOM 1 PEAK $peak too quiet")
            }
        }
    }

    @Test
    fun `a GLINT patch round-trips through JSON`() {
        val patch = GlintPatch("Glass Test", GlintVoice.BOTTLE, mapOf("PEAK" to 0.7f, "FOLLOW" to 0.2f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `a GLINT patch rejects a macro the voice does not have`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.REED, mapOf("CUTOFF" to 0.5f))
        }
    }

    @Test
    fun `a GLINT patch rejects a macro out of range`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.REED, mapOf("PEAK" to 1.4f))
        }
    }

    @Test
    fun `a GLINT patch will not load from another engine's JSON`() {
        val tines = TinesPatch("Bell", TinesVoice.BELL, mapOf("RATIO" to 0.5f))
        kotlin.test.assertFailsWith<com.snipsnap.json.JsonException> {
            GlintPatch.fromJsonText(tines.toJsonText())
        }
    }

    @Test
    fun `PEAK sweep is monotonic - the gate for joining BRIGHTNESS_MACROS`() {
        // Velocity.BRIGHTNESS_MACROS' own KDoc records what happens when a
        // macro joins this list without being measured: THUMP SNARE on TONE
        // read 1650.29 Hz soft against 1648.09 Hz hard — backwards. A macro
        // earns its place with a sweep that never falls, per voice.
        //
        // Strictly-rising was the original bar here, and CICADA breaks it:
        // two adjacent PEAK steps both snap to k=4 and render
        // byte-identical. That is not a CICADA defect - it is what
        // `PEAK values inside one snap zone render identically` REQUIRES
        // for any two PEAK values landing on the same snapped k. The suite
        // was contradicting itself: one test demanded byte-identity inside
        // a snap zone, this one forbade it. Both are satisfiable together
        // only when no two of the 9 grid points share a snapped k, which is
        // an accident of this grid's spacing, not a property PEAK has.
        // Measured at 0.01 PEAK spacing (100 adjacent pairs), ties are
        // normal snap behaviour on EVERY voice, not a CICADA-only thing:
        // REED 36/100, BOTTLE 36/100, KAZOO 36/100, CICADA 67/100, RATCHET
        // 36/100, PLATE 36/100 - the 9-point grid only avoids them because
        // it happens to place just three points (k = 4, 6, 9) inside the
        // snap band.
        //
        // So the bar is non-decreasing at every step, plus a 3x end-to-end
        // rise so a macro that merely plateaus the whole way (dead, not
        // snapped) still fails - not `PluckTest.kt`'s "PICK is dead between"
        // per-step `abs(diff) > 1f` clause, which would fail here for
        // exactly the same reason a strict rise does: a snapped macro has
        // legitimate zero-diff steps. Falls: zero out of 100 at 0.01
        // spacing for every voice, so non-decreasing is not vacuous - ties
        // happen, drops never do. End/start centroid ratio at the 9-point
        // grid: REED 21.1x, BOTTLE 19.3x, KAZOO 20.1x, RATCHET 20.1x, PLATE
        // 21.1x, and CICADA 4.8x - the binding case, leaving the 3x bar
        // about 60% margin. CICADA's 4.8x is not a weaker sweep, it falls
        // out of the ceiling equalisation arithmetically: at PEAK 0 both
        // CICADA and BOTTLE sit at k=K_MIN=2, but CICADA's real carrier is
        // k*CICADA_SUBCYCLES*f0 - 4x BOTTLE's at that same k and f0 (both
        // root at 220 Hz) - while `kCeilingFor` equalises their PEAK-1
        // ceilings, so CICADA's whole travel compresses to exactly 1/4 of
        // BOTTLE's: 19.264683 / 4 = 4.816171, measured 4.8161697.
        //
        // Measured 2026-09-27, the gate this test locks down:
        //   PEAK sweep REED:    359.3, 517.6, 726.9, 1107.8, 1677.9, 2440.4, 3568.1, 5207.0, 7589.7
        //   PEAK sweep BOTTLE:  792.1, 1180.9, 1529.5, 2297.0, 3445.9, 4962.1, 7215.2, 10497.0, 15259.6
        //   PEAK sweep KAZOO:   758.1, 1102.9, 1515.6, 2265.4, 3402.6, 4929.6, 7179.2, 10456.0, 15212.1
        //   PEAK sweep CICADA:  3168.4, 3792.9, 4855.8, 6117.8, 6117.8, 7741.9, 10754.6, 12234.2, 15259.4
        //   PEAK sweep RATCHET: 758.1, 1102.9, 1515.6, 2265.4, 3402.6, 4929.6, 7179.2, 10456.0, 15212.1
        //   PEAK sweep PLATE:   359.3, 517.6, 726.9, 1107.8, 1677.9, 2440.4, 3568.1, 5207.0, 7589.7
        // CICADA ties once (step 4 -> 5, both 6117.8); every other voice
        // rises at every step. All six clear non-decreasing + 3x, so PEAK
        // joins BRIGHTNESS_MACROS below.
        //
        // RATCHET's row is re-measured a second time, after `ratchetLadder`
        // stopped rounding its bottom rung with a bare `Math.round(kBase)`
        // and started using `snapRatio(kBase)` instead (see that function's
        // own doc). BLOOM=0 here, so RATCHET's ladder never climbs past its
        // bottom rung, and that bottom rung is now exactly `kBase` — the
        // same value KAZOO's own (unmodulated, BLOOM=0) `k` already is, and
        // RATCHET shares KAZOO's window and root — so the two rows are now
        // identical at every point, not just close. That identity is
        // specific to BLOOM=0: raise BLOOM and the ladder climbs past its
        // bottom rung while KAZOO's own sweep moves the other way, and the
        // two diverge. The previous version of this row (758.1, 1138.6,
        // 1515.6, 2265.4, 3402.6, 4926.9, 7211.5, 10645.0, 15212.1) was
        // `Math.round(kBase)`'s rounding showing up as drift of up to half
        // an integer's worth of ratio wherever `kBase` didn't already land
        // on an integer — the same rounding that made RATCHET the one
        // voice with no velocity response at PEAK 0.02-0.06 (see
        // `velocity always changes the render, at every PEAK` and
        // `ratchetLadder`'s doc). End-to-end ratio is unaffected either way
        // (20.1x): PEAK 0 and PEAK 1 both pin `kBase` to K_MIN and
        // kCeilingFor exactly, already integers for both voices.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.4f, "BLOOM" to 0f, "BODY" to 0.3f, "FOLLOW" to 1f, "DECAY" to 0.6f)
            val readings = (0..8).map { i ->
                FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to i / 8f))).centroidHz
            }
            println("PEAK sweep $voice: ${readings.joinToString(", ") { "%.1f".format(it) }}")
            for (i in 1 until readings.size) {
                assertTrue(
                    readings[i] >= readings[i - 1],
                    "$voice PEAK fell at step $i: ${readings[i - 1]} -> ${readings[i]}",
                )
            }
            assertTrue(
                readings.last() > readings.first() * 3f,
                "$voice PEAK should rise at least 3x end to end: ${readings.first()} -> ${readings.last()}",
            )
        }
    }

    @Test
    fun `GLINT uses PEAK for velocity, not the soften fallback`() {
        val patch = GlintPatch("Vel Test", GlintVoice.REED, Glint.defaults(GlintVoice.REED))
        assertEquals("PEAK", Velocity.brightnessSpec(patch)?.name, "GLINT should render velocity through PEAK")
    }

    @Test
    fun `atVelocity is genuinely darker at low velocity`() {
        // Does NOT discriminate PEAK from the soften() fallback: Velocity.soften
        // also darkens a soft hit, so this assertion would pass even if PEAK were
        // never wired up. The test directly above this one (`GLINT uses PEAK for
        // velocity, not the soften fallback`) is what proves the real routing, but
        // it only checks REED. This one earns its place by covering all three
        // voices - a coarse "velocity is directionally correct everywhere" guard
        // that the routing test alone doesn't give.
        for (voice in GlintVoice.entries) {
            val patch = GlintPatch("Vel $voice", voice, Glint.defaults(voice))
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: a soft hit must be darker, got soft=$soft hard=$hard")
        }
    }

    @Test
    fun `velocity always changes the render, at every PEAK`() {
        // The snap used to run inside ratioFor, so velocity's floor-scaled
        // macro quantised onto the same integer as the full one and the two
        // layers came out byte-identical below about PEAK 0.06 — a preset
        // there would have had no velocity response at all. Verified before
        // the fix: hard k=2, soft k=2 at PEAK 0.03 through 0.06.
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0.0f, 0.02f, 0.04f, 0.06f, 0.1f, 0.3f, 0.6f, 1.0f)) {
                val patch = GlintPatch("Vel", voice, Glint.defaults(voice) + ("PEAK" to peak))
                val soft = Velocity.atVelocity(patch, 0.2f)
                val hard = Velocity.atVelocity(patch, 1.0f)
                assertTrue(
                    !soft.samples.contentEquals(hard.samples),
                    "$voice at PEAK $peak: soft and hard renders are identical — no velocity response",
                )
            }
        }
    }

    /** The largest absolute difference between adjacent samples in a time window. */
    private fun worstAdjacentJump(snip: Snip, from: Float, to: Float): Float {
        val a = (from * snip.sampleRate).toInt().coerceAtLeast(1)
        val b = (to * snip.sampleRate).toInt().coerceAtMost(snip.samples.size)
        var worst = 0f
        for (i in a until b) worst = maxOf(worst, kotlin.math.abs(snip.samples[i] - snip.samples[i - 1]))
        return worst
    }

    @Test
    fun `CICADA stays periodic at f0 despite re-clocking inside the cycle`() {
        // The sub-cycles divide the cycle an integer number of times, so the
        // whole pattern still repeats at f0 and the pitch does not move. This
        // is the test the spec names as CICADA's risk.
        for (bloom in listOf(0f, 1f)) {
            val snip = Glint.render(GlintVoice.CICADA, mapOf("BLOOM" to bloom))
            val f0 = Glint.frequencyFor(GlintVoice.CICADA, 0.5f)
            val corr = periodCorrelation(snip, f0, fromSec = Glint.BLOOM_T60 * (7f / 9f))
            assertTrue(corr > 0.98f, "CICADA at BLOOM $bloom: period correlation $corr")
        }
    }

    @Test
    fun `CICADA does not click at its inner restarts`() {
        // Two things had to be measured, not assumed, before this test could
        // guard anything - both are the same lesson the sibling test below
        // (`a non-integer ratio clicks no more than an integer one`) already
        // learned for the OUTER wrap, recurring here at the INNER one.
        //
        // (1) REED is not a valid control. CICADA's carrier is k * N * f0;
        // at any shared nominal k, that makes CICADA's carrier
        // (220/110)*CICADA_SUBCYCLES = 8x REED's, from root Hz and the
        // re-clock multiplier alone - nothing to do with clicking. A pure
        // sine's sample-to-sample step scales with frequency, so this
        // confound holds at every k, not just high ones. Measured at the
        // default integer k=8: worst=1.4358492, reed=0.21170102 (6.78x) -
        // OVER the 3x bar on a *correct* mechanism. BOTTLE at a matched
        // carrier removes it: it shares CICADA's triangle window, and at
        // k=9 (which sits inside BOTTLE's own snap zone and rounds to it
        // exactly) the same 440 Hz f0 gives it the identical 9*440 = 3,960 Hz
        // carrier CICADA has at kBase=2.25 (2.25*4*440 = 3,960) - same
        // frequency, same window shape, differing only in whether the window
        // re-clocks every sub-cycle or once a cycle.
        //
        // (2) Integer k hides the click regardless of the control. For
        // integer k, sin(2*pi*k*x) is zero on both sides of a sub-boundary,
        // so a window computed on the wrong phase still multiplies a
        // near-zero sine there and the break is invisible. Measured at k=8
        // with the BOTTLE control and the mechanism deliberately broken
        // (`windowAt(voice, phase)` for CICADA): worst=1.388665 against the
        // correct 1.4358492 - barely different (1.10x vs 1.14x against
        // bottle=1.2594743) - BLIND. kBase=2.25 fixes this: fractional part
        // exactly 0.25, so |sin(2*pi*2.25)| = 1, the discontinuity's maximum.
        // It sits in [K_MIN, SNAP_FLOOR) so it runs free instead of snapping,
        // and it is well under CICADA's own ceiling (K_MAX/CICADA_SUBCYCLES
        // = 10, see `kCeiling` in Glint.synthesize) so that clamp never
        // engages either - this test isolates window timing from the
        // ceiling question.
        //
        // Measured on the raw oversampled buffer (`synthesize`, not
        // `render`): Dsp.decimate low-passes to the output Nyquist, which is
        // precisely the filter that would smooth a wrap discontinuity away
        // before it could be seen - the window's promise lives before that
        // filter, per the sibling test's own comment.
        //
        // Measured 2026-09-27, kBase=2.2498858 (CICADA) / k=9.0 (BOTTLE),
        // on the raw oversampled buffer: mechanism correct (window on
        // `carrier`) gives worst=0.1328646, bottle=0.1108724 (1.20x, clears
        // the 3x bar). Mechanism broken (`windowAt(voice, phase)` for
        // CICADA only, reverted immediately after): worst=0.7709526,
        // bottle unchanged at 0.1108724 (6.95x, fails, as it must - a clean
        // separation from the passing 1.20x).
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val cicadaPeak = (0..20000).map { it / 20000f }
            .minByOrNull { kotlin.math.abs(Glint.ratioFor(GlintVoice.CICADA, 0.5f, it, 1f) - 2.25f) }!!
        val cicadaK = Glint.ratioFor(GlintVoice.CICADA, 0.5f, cicadaPeak, 1f)
        assertTrue(
            kotlin.math.abs(cicadaK - 2.25f) < 0.01f,
            "test setup expected CICADA kBase near 2.25 (unsnapped), got $cicadaK",
        )
        // Derived from the actual measured cicadaK, not the literal 2.25, so
        // the two carriers match exactly regardless of search granularity.
        val bottleTargetK = cicadaK * Glint.CICADA_SUBCYCLES
        val bottlePeak = (0..20000).map { it / 20000f }
            .minByOrNull { kotlin.math.abs(Glint.ratioFor(GlintVoice.BOTTLE, 0.5f, it, 1f) - bottleTargetK) }!!
        val bottleK = Glint.ratioFor(GlintVoice.BOTTLE, 0.5f, bottlePeak, 1f)
        assertEquals(9f, bottleK, 1e-6f, "test setup expected BOTTLE k=9 (snapped, matches CICADA's carrier)")

        val cicadaRaw = Glint.synthesize(
            GlintVoice.CICADA, mapOf("TUNE" to 0.5f, "FOLLOW" to 1f, "BLOOM" to 0f, "PEAK" to cicadaPeak), rate,
        )
        val bottleRaw = Glint.synthesize(
            GlintVoice.BOTTLE, mapOf("TUNE" to 0.5f, "FOLLOW" to 1f, "BLOOM" to 0f, "PEAK" to bottlePeak), rate,
        )
        val worst = worstAdjacentJump(Snip(cicadaRaw, 1, rate), from = 0.01f, to = 0.20f)
        val bottle = worstAdjacentJump(Snip(bottleRaw, 1, rate), from = 0.01f, to = 0.20f)
        assertTrue(
            worst < bottle * 3f,
            "CICADA's worst sample-to-sample jump $worst is more than 3x BOTTLE's $bottle at the same 3,960 Hz carrier — the inner restarts are clicking",
        )
    }

    @Test
    fun `CICADA puts energy at its sub-cycle rate that BOTTLE does not`() {
        // The lattice is audible as energy at N * f0. This is what makes CICADA
        // a different voice rather than a differently-windowed one.
        //
        // The control used to be BOTTLE at its own default PEAK, which was
        // correct when this test was written: CICADA's default kBase was then
        // 8, the same as BOTTLE's, and neither voice's own peak sat near the
        // N*f0 probe. A later fix moved CICADA's default kBase to 4 - which is
        // CICADA_SUBCYCLES itself - so a mechanism-removed CICADA (carrier =
        // phase, no re-clock) now puts its OWN formant at kBase*f0 =
        // 4*440 = 1,760 Hz: exactly the N*f0 this test probes. Nobody
        // re-derived the control when the default moved, so the default-PEAK
        // comparison stopped isolating the mechanism - it was testing which
        // voice's own peak happened to land nearest 1,760 Hz, not the
        // re-clock.
        //
        // Fixed with the same matched-carrier control the no-click test above
        // (`CICADA does not click at its inner restarts`) already built for
        // the same confound at the inner wrap: CICADA at kBase ~= 2.25 and
        // BOTTLE at k = 9 share the identical 3,960 Hz carrier and triangle
        // window, differing only in whether the window re-clocks
        // CICADA_SUBCYCLES times a cycle. Neither voice's own peak sits near
        // the 1,760 Hz probe, so any energy CICADA shows there over BOTTLE is
        // attributable to the re-clocking alone.
        //
        // The old control moved the wrong way when the mechanism it exists to
        // catch was deleted: reviewer-measured, deleting
        // `carrier = frac(N*phase)` against the old default-PEAK comparison
        // still passed - MORE comfortably (ratio ~8.1e8) than the correct
        // engine did (~6.6e5) - because that comparison was never measuring
        // the re-clock, only which voice's own default peak sat nearer
        // 1,760 Hz.
        //
        // Mutation-verified 2026-09-27 against THIS (matched-carrier)
        // control (`carrier = phase` for CICADA only, reverted immediately
        // after): mechanism correct gives
        // cicada=1.6518465 bottle=0.005919548 (ratio 279.0x, clears the 3x
        // bar); mechanism removed gives cicada=0.013001844 against the same
        // bottle=0.005919548 (ratio 2.2x, FAILS, as it must - a clean
        // separation from the passing 279.0x, and the correct direction this
        // time: broken drops below the bar instead of clearing it wider).
        val f0 = Glint.frequencyFor(GlintVoice.CICADA, 0.5f)
        val lattice = Glint.CICADA_SUBCYCLES * f0
        val cicadaPeak = (0..20000).map { it / 20000f }
            .minByOrNull { kotlin.math.abs(Glint.ratioFor(GlintVoice.CICADA, 0.5f, it, 1f) - 2.25f) }!!
        val cicadaK = Glint.ratioFor(GlintVoice.CICADA, 0.5f, cicadaPeak, 1f)
        assertTrue(
            kotlin.math.abs(cicadaK - 2.25f) < 0.01f,
            "test setup expected CICADA kBase near 2.25 (unsnapped), got $cicadaK",
        )
        val bottleTargetK = cicadaK * Glint.CICADA_SUBCYCLES
        val bottlePeak = (0..20000).map { it / 20000f }
            .minByOrNull { kotlin.math.abs(Glint.ratioFor(GlintVoice.BOTTLE, 0.5f, it, 1f) - bottleTargetK) }!!
        val bottleK = Glint.ratioFor(GlintVoice.BOTTLE, 0.5f, bottlePeak, 1f)
        assertEquals(9f, bottleK, 1e-6f, "test setup expected BOTTLE k=9 (snapped, matches CICADA's carrier)")

        val still = mapOf("TUNE" to 0.5f, "FOLLOW" to 1f, "BLOOM" to 0f, "BODY" to 0f)
        val cicada = Glint.render(GlintVoice.CICADA, still + ("PEAK" to cicadaPeak))
        val bottle = Glint.render(GlintVoice.BOTTLE, still + ("PEAK" to bottlePeak))
        val c = energyAt(cicada.samples, lattice, cicada.sampleRate)
        val b = energyAt(bottle.samples, lattice, bottle.sampleRate)
        assertTrue(
            c > b * 3f,
            "CICADA has $c at the lattice rate ($lattice Hz), BOTTLE (same 3,960 Hz carrier) has $b",
        )
    }

    /**
     * The spec's requirement: measure the centroid in windows and assert the
     * plateaus. A glide would show a different centroid in every window;
     * steps show runs of equal ones with jumps between.
     *
     * The 0.25x bar was checked against the mechanism-absent case before it
     * was trusted, per this branch's own rule that a threshold is only as
     * good as the range it was measured against. Before RATCHET had its own
     * `when` branch (i.e. running the plain continuous BLOOM sweep every
     * other non-CICADA voice uses, at the same macros this test renders
     * with, step = 0.15f literal since the constant did not exist yet):
     * early=7549.2656, late=5515.9536, next=3901.208 -
     * |late-early|=2033.312, |next-early|=3648.0576, ratio=0.5574. That
     * clears (i.e. fails to clear) the 0.25 bar by more than 2x, so a
     * continuous ramp cannot pass this test by accident - the bar is a real
     * discriminator, not a vacuous one.
     *
     * With the mechanism in place, same render: early=3486.6492,
     * late=3490.6194, next=3957.623 - |late-early|=3.9702148 (early and
     * late's probe windows are ~19.8 cycles apart center-to-center at this
     * 440 Hz note, both squarely inside kBase=8's bottom rung),
     * |next-early|=470.97388 (early and next are a full
     * RATCHET_STEP_SECONDS apart center-to-center - exactly 66 cycles at
     * 440 Hz - the jump to rung two, k=9), ratio=0.008430 - about 30x under
     * the 0.25 bar, not just clearing it (0.5574 / 0.008430 is the ~66x
     * figure - this ratio's distance from the mechanism-absent control
     * measured above, a different comparison from the bar).
     */
    @Test
    fun `RATCHET's formant is piecewise constant, not a ramp`() {
        val snip = Glint.render(GlintVoice.RATCHET, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
        val step = Glint.RATCHET_STEP_SECONDS
        // Two probes inside one step must agree; probes either side of a step
        // boundary must not.
        val early = FeatureExtractor.extract(slice(snip, step * 0.25f, step * 0.45f)).centroidHz
        val late = FeatureExtractor.extract(slice(snip, step * 0.55f, step * 0.75f)).centroidHz
        val next = FeatureExtractor.extract(slice(snip, step * 1.25f, step * 1.45f)).centroidHz
        assertTrue(
            kotlin.math.abs(late - early) < kotlin.math.abs(next - early) * 0.25f,
            "RATCHET: within-step centroid moved $early -> $late, across-step moved $early -> $next — that is a ramp, not a staircase",
        )
    }

    @Test
    fun `RATCHET's steps land on integer harmonics`() {
        // "Steps between fixed harmonics, never glides." Every rung ABOVE
        // THE BOTTOM must be a whole number, or the ladder is not a ladder.
        // The bottom rung is the one exception: below SNAP_FLOOR it is
        // snapRatio(kBase), left unrounded — see ratchetLadder's own KDoc
        // for why (the velocity collision Fix 1 exists to undo). This
        // fixture's kBase=8 is already in the snap band, so its own bottom
        // rung is already a whole number and never exercises that
        // exception; the second loop below adds a below-SNAP_FLOOR case
        // that does.
        //
        // Measured 2026-09-27 at kBase=8.0 (TUNE 0.5, PEAK 0.45, FOLLOW 0.8 -
        // this test's own inputs): BLOOM 0.25 -> [8..14] (7 rungs), BLOOM 0.5
        // -> [8..20] (13 rungs), BLOOM 1 -> [8..32] (25 rungs).
        for (bloom in listOf(0.25f, 0.5f, 1f)) {
            val ks = Glint.ratchetLadder(
                kBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.45f, 0.8f),
                bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX),
            )
            for (k in ks) {
                assertTrue(k == Math.round(k).toFloat(), "RATCHET ladder rung $k is not an integer")
            }
            assertTrue(ks.toSet().size == ks.size, "RATCHET ladder repeats a rung: ${ks.toList()}")
        }

        // Below SNAP_FLOOR: the bottom rung is free, not rounded. Measured
        // 2026-09-27 at kBase=2.1234918 (PEAK 0.02, TUNE 0.5, FOLLOW 0.8 -
        // the exact hard-velocity case `velocity always changes the
        // render, at every PEAK` locks down for RATCHET): BLOOM 0.25 ->
        // [2.1234918, 3] (2 rungs), BLOOM 0.5 -> [2.1234918, 3, 4, 5]
        // (4 rungs), BLOOM 1 -> [2.1234918, 3, 4, 5, 6, 7, 8] (7 rungs) -
        // the bottom rung stays at the unrounded kBase every time, and
        // every rung after it is still a whole number.
        val belowFloorKBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.02f, 0.8f)
        for (bloom in listOf(0.25f, 0.5f, 1f)) {
            val ks = Glint.ratchetLadder(belowFloorKBase, Dsp.lin(bloom, 0f, Glint.BLOOM_MAX))
            assertEquals(
                belowFloorKBase,
                ks.first(),
                "RATCHET's bottom rung must stay at the unrounded kBase below SNAP_FLOOR",
            )
            for (k in ks.drop(1)) {
                assertTrue(k == Math.round(k).toFloat(), "RATCHET ladder rung $k above the bottom is not an integer")
            }
            assertTrue(ks.toSet().size == ks.size, "RATCHET ladder repeats a rung: ${ks.toList()}")
        }

        // Above SNAP_CEILING: the bottom rung is free there too, for the
        // identical reason (snapRatio is identity above the ceiling, same
        // as below the floor). Measured 2026-09-27 at kBase=16.28362 (PEAK
        // 0.7, TUNE 0.5, FOLLOW 0.8): BLOOM 0.25 -> [16.28362..28] (13
        // rungs), BLOOM 0.5 -> [16.28362..40] (25 rungs, clamped at
        // kCeilingFor), BLOOM 1 -> the same 25 rungs as BLOOM 0.5 (BLOOM's
        // reach already exceeds the ceiling at 0.5, so 1 has nowhere further
        // to open) - the old, unconditional `Math.round` would have given a
        // bottom rung of 16 in every case. This is the case that closes the
        // three points where RATCHET's PEAK-sweep row still differed from
        // KAZOO's before this fix (see `PEAK sweep is monotonic`'s own
        // comment) - all three sit above SNAP_CEILING, not below SNAP_FLOOR.
        val aboveCeilingKBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.7f, 0.8f)
        for (bloom in listOf(0.25f, 0.5f, 1f)) {
            val ks = Glint.ratchetLadder(aboveCeilingKBase, Dsp.lin(bloom, 0f, Glint.BLOOM_MAX))
            assertEquals(
                aboveCeilingKBase,
                ks.first(),
                "RATCHET's bottom rung must stay at the unrounded kBase above SNAP_CEILING",
            )
            for (k in ks.drop(1)) {
                assertTrue(k == Math.round(k).toFloat(), "RATCHET ladder rung $k above the bottom is not an integer")
            }
            assertTrue(ks.toSet().size == ks.size, "RATCHET ladder repeats a rung: ${ks.toList()}")
        }
    }

    @Test
    fun `RATCHET climbs further as BLOOM opens`() {
        val kBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.45f, 0.8f)
        val small = Glint.ratchetLadder(kBase, Dsp.lin(0.25f, 0f, Glint.BLOOM_MAX)).last()
        val large = Glint.ratchetLadder(kBase, Dsp.lin(1f, 0f, Glint.BLOOM_MAX)).last()
        assertTrue(large > small, "RATCHET's ladder top did not rise with BLOOM: $small -> $large")
    }

    /**
     * The step is quantised to the phase wrap, where the window has just
     * reached zero - the one instant any `k` starts a cycle from silence
     * instead of interrupting a wide-open window mid-sine.
     *
     * The plan's own sketch for this test compared RATCHET against KAZOO
     * over a broad early window (0.01-0.60 s). That control is confounded
     * the same way `CICADA does not click at its inner restarts` found REED
     * to be confounded for CICADA: KAZOO's BLOOM sweep runs downward from
     * `kBase*(1+bloomAmount)` (its loudest, highest-carrier moment is at
     * t=0), while RATCHET's ladder climbs upward from `kBase` (its lowest
     * carrier is at t=0) - same starting `kBase` (RATCHET and KAZOO share
     * `rootHz` and `kCeilingFor`, so `ratioFor` returns byte-identical values
     * for both at any shared macros), opposite direction. Over a broad early
     * window KAZOO sits at a much higher instantaneous carrier than RATCHET,
     * and a sine's sample-to-sample step scales with frequency - so KAZOO's
     * own "worst jump" baseline is inflated by nothing to do with clicking,
     * and a bar built on it could clear for the wrong reason.
     *
     * The fix is the one the CICADA test already used for the equivalent
     * problem: hold everything but the mechanism fixed. Here that needs no
     * second voice at all - compare RATCHET against itself, a window
     * straddling a step boundary against an equal-width window fully inside
     * one rung, close enough in time that the envelope has barely moved
     * between them. Same voice, same render, same render call, differing
     * only in whether a step boundary falls inside the window.
     *
     * Measured on `synthesize`'s raw oversampled buffer, not `render`: as the
     * CICADA test's own comment notes, `Dsp.decimate` low-passes to the
     * output Nyquist, which is exactly the filter that would smooth a wrap
     * discontinuity away before it could be seen.
     *
     * TUNE must be off RATCHET's default (0.5, i.e. 440 Hz): the root
     * (220 Hz) times [Glint.RATCHET_STEP_SECONDS] (0.15 s) is 33, an
     * integer, so every octave of the root (TUNE 0, 0.5, 1 -> 220/440/880
     * Hz) lands the nominal step boundary on an exact whole number of
     * cycles - the step and the phase wrap coincide by construction there,
     * regardless of whether the mutation below is present. First round of
     * mutation-verification used the default TUNE and the mutation did NOT
     * fail (straddle and inside both stayed at the ordinary per-cycle
     * transient's size) for exactly this reason - not because the design
     * was wrong, but because 440*0.15=66 exactly gave the mutated code
     * nowhere mid-cycle to land. TUNE=0.3 -> f0=329.6 Hz -> 329.6*0.15=49.4,
     * comfortably off-integer, exposes it.
     *
     * Mutation-verified 2026-09-27 at TUNE=0.3: moved the `rung` update out
     * of the `if (phase >= 1f)` block so it runs every sample (the exact
     * mistake this design guards against - a rung picked at whatever phase
     * elapsed time happens to cross the 150 ms mark, instead of only at the
     * wrap). Correct code (both measured against `inside=0.03507772`, which
     * the mutation leaves untouched since that window never contains a step
     * boundary either way): straddle=0.042295076 (ratio 1.206x, clears the
     * 3x bar). Mutated code: straddle=0.1769346 (ratio 5.045x, fails, as it
     * must - a clean separation from the passing 1.206x). Reverted
     * immediately after recording it.
     */
    @Test
    fun `RATCHET does not click when it steps`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        // TUNE=0.3, not left at its 0.5 default - see this test's own KDoc
        // for why the default's exact-integer step/cycle ratio would hide
        // the very bug this test exists to catch.
        val raw = Glint.synthesize(GlintVoice.RATCHET, mapOf("TUNE" to 0.3f, "BLOOM" to 1f, "DECAY" to 0.9f), rate)
        val snip = Snip(raw, 1, rate)
        val step = Glint.RATCHET_STEP_SECONDS
        // Straddles the first step boundary (nominally at t=step; the actual
        // wrap lands within one cycle period after it) - wide enough (0.2 *
        // step = 30 ms, ~10 cycles at this 329.6 Hz note) to contain it
        // regardless of exactly where in that cycle it falls.
        val straddle = worstAdjacentJump(snip, from = step * 0.9f, to = step * 1.1f)
        // Same width, fully inside the second rung - no boundary within it,
        // starting only 15 ms after the first window ends so the amplitude
        // envelope has barely moved between the two.
        val inside = worstAdjacentJump(snip, from = step * 1.2f, to = step * 1.4f)
        assertTrue(
            straddle < inside * 3f,
            "RATCHET's worst jump straddling a step boundary ($straddle) vs fully inside one rung ($inside) — the step is clicking",
        )
    }

    /**
     * The spec's central claim for PLATE: `k(t) = kBase * (1 + BLOOM *
     * amp_env(t))`, no separate clock — a struck plate is brightest at the
     * strike and its formant falls as the note does.
     *
     * Fixture: `Glint.defaults(PLATE)` (TUNE 0.5, PEAK 0.45, FOLLOW 0.8) +
     * BLOOM 1, DECAY 0.9, BODY 0 (silences the second formant so only the
     * main burst's ratio drives the centroid).
     *
     * This bar does NOT by itself discriminate the new coupling from the
     * old BLOOM_T60 sweep PLATE fell through to before this task (Task 1-3's
     * `else` branch, the one every other bloom-sweeping voice still takes):
     * that sweep also falls from an opened head to a settled tail, and its
     * fixed 0.45s t60 happens to be almost fully settled by this fixture's
     * 0.45-0.60s tail window regardless of DECAY. Measured with the
     * mechanism ABSENT (PLATE still on the plain sweep), 2026-09-27:
     * head=3700.7407 tail=1728.2657, ratio=0.46700537 - already clears
     * `tail < head * 0.8`. Measured WITH the mechanism: head=5232.801
     * tail=1958.1921, ratio=0.37421492 - also clears it, and is a genuine
     * fall either way. `PLATE's fall tracks the amplitude envelope, not a
     * separate curve`, immediately below, is the test that actually tells
     * the two mechanisms apart - the discriminating power lives there, not
     * here.
     */
    @Test
    fun `PLATE's formant falls as the note decays`() {
        val snip = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
        val head = FeatureExtractor.extract(slice(snip, 0.02f, 0.10f)).centroidHz
        val tail = FeatureExtractor.extract(slice(snip, 0.45f, 0.60f)).centroidHz
        assertTrue(tail < head * 0.8f, "PLATE's centroid went $head -> $tail — it did not fall")
    }

    /**
     * The spec's requirement, and the one test in this trio that actually
     * separates `k(t) = kBase * (1 + BLOOM * amp_env(t))` from a fixed-rate
     * sweep: `amp_env` runs on the note's own DECAY, so a longer note must
     * still be bright at a fixed wall-clock instant where a shorter note has
     * already gone dark. A sweep on a constant BLOOM_T60 has no idea how
     * long the note is and reads the same at that instant either way.
     *
     * Fixture: BLOOM 1, BODY 0, everything else at `Glint.defaults(PLATE)`;
     * DECAY 0.3 (t60 ~0.251s, duration ~0.339s) for the short note, DECAY
     * 0.95 (t60 ~1.238s, duration ~1.672s) for the long one; both probed at
     * 0.25-0.30s. The short note's probe sits deep in its own tail (its amp
     * envelope is ~0.001 of peak there) - checked this is a real reading and
     * not `FeatureExtractor`'s silent-buffer fallback (which would return a
     * centroid of exactly 0 and pass this bar vacuously): the probed slice
     * holds 2205 frames and peaks at 0.0011 (the long note's same-width
     * slice peaks at 0.173) - both comfortably above the `total <= EPSILON`
     * (1e-10) floor that triggers the fallback.
     *
     * Measured with the mechanism ABSENT (PLATE on the plain BLOOM_T60
     * sweep, which ignores DECAY entirely), 2026-09-27: shortC=1794.223
     * longC=1791.5879, ratio=0.99853134 - both notes read the same centroid
     * at this instant, as a clock blind to DECAY must. Fails `> 1.15`
     * cleanly, confirming the bar separates the two mechanisms instead of
     * passing by default. Measured WITH the mechanism: shortC=1729.6843
     * longC=2789.7527, ratio=1.6128681 - clears 1.15 with ~40% margin.
     *
     * Mutation-verified 2026-09-27: temporarily replaced `amp.at(t)` with
     * `Dsp.envAt(t, BLOOM_T60)` in PLATE's own branch of `synthesize` (a
     * fixed-rate fall — the exact regression this test exists to catch) and
     * reran. Result was byte-identical to the mechanism-ABSENT numbers
     * above - shortC=1794.223 longC=1791.5879, ratio=0.99853134 - which is
     * expected, since that substitution makes PLATE's branch arithmetically
     * identical to the `else` branch every other bloom-sweeping voice
     * already takes. Fails the bar, as it must; reverted immediately after
     * recording it.
     */
    @Test
    fun `PLATE's fall tracks the amplitude envelope, not a separate curve`() {
        // The spec's requirement. k(t) = kBase * (1 + BLOOM * amp_env(t)), so a
        // LONGER note must hold its brightness longer in absolute time: the
        // coupling has no clock of its own. A fixed-rate sweep would fall at
        // the same wall-clock rate regardless of DECAY.
        val short = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.3f, "BODY" to 0f))
        val long = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.95f, "BODY" to 0f))
        val at = 0.25f
        val shortC = FeatureExtractor.extract(slice(short, at, at + 0.05f)).centroidHz
        val longC = FeatureExtractor.extract(slice(long, at, at + 0.05f)).centroidHz
        assertTrue(
            longC > shortC * 1.15f,
            "at ${at}s the long note's centroid is $longC and the short note's is $shortC — the fall is not tied to the envelope",
        )
    }

    /**
     * The control: at BLOOM 0, `bloomAmount` is 0 and `k(t) = kBase *
     * (1 + 0 * amp.at(t)) = kBase` for every `t` - the coupling is gated off
     * entirely and PLATE is an ordinary, fixed-ratio saw-window voice. If
     * this fails, BLOOM is not actually gating it.
     *
     * Measured 2026-09-27, mechanism ABSENT and WITH the mechanism alike:
     * head=1725.7195 tail=1725.5787, ratio=8.1558486E-5, in both cases. That
     * identity is not a coincidence: `0f * x` is exactly `0f` for any finite
     * `x` in IEEE754, so at BLOOM 0 the old `envAt(t, BLOOM_T60)` curve and
     * the new `amp.at(t)` curve are each multiplied by zero and vanish from
     * the expression the same way — PLATE at BLOOM 0 renders byte-identical
     * regardless of which curve the `when` branch names.
     */
    @Test
    fun `PLATE at BLOOM zero does not move its formant`() {
        // The control: with the coupling depth at zero, k(t) = kBase and PLATE
        // is an ordinary saw-window voice. If this fails, the coupling is not
        // actually gated on BLOOM.
        val snip = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 0f, "DECAY" to 0.9f, "BODY" to 0f))
        val head = FeatureExtractor.extract(slice(snip, 0.02f, 0.10f)).centroidHz
        val tail = FeatureExtractor.extract(slice(snip, 0.45f, 0.60f)).centroidHz
        assertTrue(
            kotlin.math.abs(tail - head) < head * 0.15f,
            "PLATE at BLOOM 0 moved its centroid $head -> $tail",
        )
    }
}
