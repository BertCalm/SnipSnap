package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlintTest {

    /**
     * The voices whose formant follows a ratio path into PEAK: what most of
     * this file loops over. VOWEL is left out because its formants are Hz on
     * a vowel line, not ratios: it has no FOLLOW and no kBase, so the fixtures
     * that read either do not apply. It joins the loops over
     * `GlintVoice.entries` (audio legality, DECAY, determinism, PEAK opens,
     * velocity) except the two ruled exemptions marked in place, and has its
     * own tests in `GlintVowelTest`, including the gates that stand in for
     * what it is exempt from.
     */
    private val PATH_VOICES = GlintVoice.entries - GlintVoice.VOWEL

    // BLOOM is bipolar: 0.5 is a still formant, above it the path starts
    // above PEAK and falls in, below it the path starts below PEAK and rises
    // in. A fixture that wants a static formant therefore pins "BLOOM" to
    // 0.5f - 0f is the widest RISING path, no longer "no sweep". Fixtures that
    // loop both 0f and 1f are checking that both extremes are legal.

    /**
     * The all-ones corner's peak floor is lower than the other two cases'
     * because `Dsp.levelTo` normalizes to loudness (RMS of the loudest
     * 200 ms), not peak: a long DECAY keeps the loudest-RMS window near full
     * level, which raises the measured loudness and so lowers the normalized
     * peak, and that corner is DECAY 1. The floor was set when a trapezoid
     * window held that corner to a peak of 0.443; with one window the corner
     * measures SWEEP 0.8965, BRASS 0.8965, STEP 0.6992 (2026-09-29), and the
     * defaults and all-zeros cases sit at the 0.99 clamp.
     *
     * 0.35 stays where it is, not tightened to the new figures: it exists to
     * catch a genuinely broken render (near zero), not to pin a level. The
     * corners also cover both extremes of the bipolar BLOOM - all-zeros is
     * the widest rising path, all-ones the widest falling one.
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

    // VOWEL declares five, without FOLLOW: `GlintVowelTest` holds its list.
    @Test
    fun `every path voice declares exactly the six macros`() {
        for (voice in PATH_VOICES) {
            val macros = Glint.macrosFor(voice)
            assertEquals(
                listOf("TUNE", "PEAK", "FOLLOW", "BODY", "BLOOM", "DECAY"),
                macros.map { it.name },
                "$voice's macro contract",
            )
            assertEquals(listOf(0.5f, 0.45f, 0.8f, 0.4f, 0.675f, 0.5f), macros.map { it.default }, "$voice's defaults")
            assertEquals(0.5f, macros.first { it.name == "BLOOM" }.neutral, "$voice's BLOOM is bipolar, neutral at its centre")
        }
    }

    @Test
    fun `one window - the saw ramp - and it ends at exactly zero`() {
        assertEquals(1f, Glint.windowAt(0f))
        assertEquals(0.4f, Glint.windowAt(0.6f), 1e-6f)
        assertEquals(0f, Glint.windowAt(1f))
    }

    @Test
    fun `BLOOM is signed around its centre`() {
        assertEquals(-1f, Glint.bloomSign(0f))
        assertEquals(0f, Glint.bloomSign(0.5f))
        assertEquals(1f, Glint.bloomSign(1f))
        // Up and down are mirror images in log-ratio.
        assertEquals(8f * 4f, Glint.startRatio(8f, 1f), 1e-4f)
        assertEquals(8f / 4f, Glint.startRatio(8f, -1f), 1e-4f)
        assertEquals(8f, Glint.startRatio(8f, 0f))
        // Clamped both ways.
        assertEquals(Glint.K_MAX, Glint.startRatio(30f, 1f))
        assertEquals(Glint.K_MIN, Glint.startRatio(3f, -1f))
    }

    @Test
    fun `STEP's ladder runs from the start to PEAK in whole harmonics, either way`() {
        assertTrue(Glint.stepLadder(8f, 12.6f).contentEquals(floatArrayOf(12f, 11f, 10f, 9f, 8f)))
        assertTrue(Glint.stepLadder(8f, 4.2f).contentEquals(floatArrayOf(5f, 6f, 7f, 8f)))
        assertTrue(Glint.stepLadder(8f, 8f).contentEquals(floatArrayOf(8f)))
        // Outside the snap band the landing stays unrounded (ratchetLadder's old rule).
        val free = Glint.stepLadder(16.28f, 20.5f)
        assertEquals(16.28f, free.last(), 1e-4f)
        assertTrue(free.dropLast(1).all { it == Math.round(it).toFloat() })
        // STEP reads its ladder by rung: the held breath goes through breathRatios, not through a rung of -1.
        assertFailsWith<IllegalArgumentException> {
            GlintPath.of(GlintVoice.STEP, Glint.defaults(GlintVoice.STEP), Glint.frequencyFor(GlintVoice.STEP, 0.5f))
                .ratios(0.5f, -1, FloatArray(2))
        }
    }

    @Test
    fun `STEP's landing stays unrounded below the snap floor too`() {
        // Below SNAP_FLOOR snapRatio is the identity, so the landing is kBase
        // itself: 2.12 must not collapse onto the rung 2 (SNAP_FLOOR's doc
        // gives the reason).
        assertEquals(2.12f, Glint.stepLadder(2.12f, 8.49f).last(), 1e-6f)
    }

    @Test
    fun `every path lands on PEAK - x at zero is kBase for every voice`() {
        val k = FloatArray(2)
        for (voice in PATH_VOICES) {
            for (bloom in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val m = Glint.defaults(voice) + ("BLOOM" to bloom)
                val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
                val path = GlintPath.of(voice, m, f0)
                val landed = if (path.ladder != null) path.ladder!!.lastIndex else -1
                path.ratios(0f, landed, k)
                val kBase = Glint.ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
                assertEquals(kBase, k[0], 1e-4f, "$voice BLOOM $bloom does not land on PEAK")
            }
        }
    }

    @Test
    fun `every path's first cycle plays at its start, not at PEAK`() {
        // BRASS reads x off the note's own level, which is 0 at t = 0 (the
        // attack has not begun). Reading x there would play the first cycle
        // on PEAK and put the "brightest at the strike" on the second cycle
        // instead of the first, so `synthesize` reads x = 1 for the first
        // cycle whatever a voice's clock says. A burst of k sine cycles
        // crosses zero 2k - 1 times inside its window, so counting sign
        // changes in the first period reads the ratio it was rendered at.
        //
        // Mutation-verified 2026-09-29: reading `x(0f)` in place of `1f` on
        // the first `path.ratios` call left every other test in this file
        // green and failed this one (BRASS: 15 crossings against 63).
        // Reverted immediately after.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PATH_VOICES) {
            val defaults = Glint.defaults(voice)
            val kBase = Glint.ratioFor(voice, 0.5f, defaults.getValue("PEAK"), defaults.getValue("FOLLOW"))
            val kStart = Glint.startRatio(kBase, 1f)
            val raw = Glint.synthesize(voice, mapOf("TUNE" to 0.5f, "BODY" to 0f, "BLOOM" to 1f), rate)
            val firstCycle = (rate / Glint.frequencyFor(voice, 0.5f)).toInt()
            var crossings = 0
            var prev = 0f
            for (i in 0 until firstCycle) {
                val s = raw[i]
                if (s == 0f) continue
                if (prev != 0f && (s > 0f) != (prev > 0f)) crossings++
                prev = s
            }
            assertEquals(
                2 * kStart - 1, crossings.toFloat(), 2f,
                "$voice's first cycle should play at its start ($kStart), not at PEAK ($kBase): $crossings zero crossings",
            )
        }
    }

    @Test
    fun `BLOOM below centre starts darker, above centre starts brighter`() {
        val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.BRASS, GlintVoice.STEP)) {
            fun head(bloom: Float): Float =
                FeatureExtractor.extract(slice(Glint.render(voice, still + ("BLOOM" to bloom)), 0.01f, 0.03f)).centroidHz
            val down = head(1f)
            val flat = head(0.5f)
            val up = head(0f)
            assertTrue(down > flat * 1.3f, "$voice BLOOM 1 should open above PEAK: $down vs $flat")
            assertTrue(up < flat / 1.2f, "$voice BLOOM 0 should start below PEAK: $up vs $flat")
        }
    }

    @Test
    fun `every voice's root is inside its own TUNE range`() {
        // rootHz is a hand-written per-voice literal, and a typo there (2200
        // for 220) would sail past every other test in this file, which all
        // measure ratios rather than absolute pitch. rootMidi is the same
        // note as a MIDI number, for the held pad's zones: two literals for
        // one fact, so they are checked against each other.
        for (voice in GlintVoice.entries) {
            val root = Glint.rootHz(voice)
            assertTrue(root > 20f && root < 2000f, "$voice's root is $root Hz, outside the audible fundamental range")
            assertEquals(Keys.midiHz(Glint.rootMidi(voice)), root, 0.01f, "$voice's rootMidi and rootHz name different notes")
        }
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Glint.frequencyFor(GlintVoice.SWEEP, i / 100f))
        assertEquals(Glint.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(Glint.rootHz(GlintVoice.SWEEP), Glint.frequencyFor(GlintVoice.SWEEP, 0f), 1e-3f)
        assertEquals(Glint.rootHz(GlintVoice.SWEEP) * 4f, Glint.frequencyFor(GlintVoice.SWEEP, 1f), 1e-2f)
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
            val still = mapOf("BLOOM" to 0.5f, "BODY" to 0.2f)
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
     * with linear interpolation, under the 0.98 bar this file holds
     * periodicity to. The two-tap method was measuring its own phase error,
     * not periodicity.
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
        // renders by several percent, a detector artifact that a periodicity
        // measure sidesteps entirely.
        //
        // The correlation runs only from a settled window. BLOOM's sweep runs
        // on a fixed 0.45 s t60, and a one-period Pearson correlation
        // measures whether consecutive cycles have the same *shape*: a
        // strong, genuine sweep changes shape rapidly cycle to cycle while
        // leaving the period exact, so the metric INVERTS mid-sweep - the
        // correct engine scores worse there than a pitch-drifted control
        // would. It is only a valid period test where the spectrum is
        // quasi-static. That is a property of the measure, not of GLINT: the
        // period is exact by construction whatever k does, because `phase`
        // advances by `f0 / rate` and wraps at 1.0 independently of it. (A
        // lag-domain measure - where the autocorrelation peak sits - would
        // stay valid during a sweep; this test does not need one.)
        //
        // `settledFromSec` is written as `Glint.BLOOM_T60 * (7f / 9f)`, not
        // the literal 0.35 (where envAt(0.35, 0.45) = 0.0045), so it stays at
        // the same envAt fraction if BLOOM_T60 ever changes: a hardcoded
        // value would silently slide back into the sweep and fail a correct
        // engine while pointing at a pitch bug that does not exist. The cost
        // is that at DECAY 0.7 the render is ~0.90 s, so this only has room
        // while BLOOM_T60 stays below ~0.9 s (fromSec plus the 0.2 s
        // correlation window must fit before the end).
        //
        // BRASS and STEP are not "settled" at that instant in the same sense.
        // BRASS's path is the note's own level (t60 ~0.67 s at this DECAY),
        // and STEP's ladder crosses rung boundaries inside the [0.35, 0.55]
        // window at BLOOM 0 and 1 wherever it has more than one rung.
        // Harmless: a k step does not move the period, for the reason above.
        //
        // Measured 2026-09-29 at that fromSec, worst reading over this test's
        // four PEAKs x both BLOOM extremes: SWEEP 0.999856 (PEAK 0.9, BLOOM 0),
        // STEP 0.994900 (PEAK 0.6, BLOOM 1), BRASS 0.997521 (PEAK 0.9,
        // BLOOM 0) - all above the 0.98 bar. Four PEAKs can miss an off-grid
        // minimum, so the same measurement was repeated over 41 PEAKs x both
        // BLOOM extremes: SWEEP 0.999738, STEP 0.994899, BRASS 0.995492. No
        // thin margin hides between the sampled points.
        val settledFromSec = Glint.BLOOM_T60 * (7f / 9f)
        for (voice in PATH_VOICES) {
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
        val voice = GlintVoice.SWEEP
        val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0.5f, "FOLLOW" to 1f)
        fun ratioAt(peak: Float) = Glint.snapRatio(Glint.ratioAtReference(peak).coerceIn(Glint.K_MIN, Glint.K_MAX))
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
        // BLOOM held at 0.5 (still) so k is constant through the render; BODY
        // at 0 so the body's own harmonics don't muddy the comparison; k=3
        // chosen because it is below SNAP_CEILING (so it snaps to an exact
        // integer) and is the spec's own worked example.
        //
        // The doubled-formant decoy is the tight one (a real harmonic of f0,
        // just the "wrong" one); the halved decoy isn't a harmonic of f0 at
        // all here (1.5*f0) so it reads near noise floor. 20x is a bar a
        // doubled or halved formant could not pass.
        //
        // Measured 2026-09-29 (energy ratio, peak-to-decoy): SWEEP and BRASS
        // atK/at2k = 203.6x, atK/at(k/2) = 23811x; STEP 200.5x and 137511x.
        // The doubled decoy is the tight one at 200x worst case, so 20x has
        // roughly an order of magnitude of margin.
        val stillTune = 0.5f
        for (voice in PATH_VOICES) {
            val peak = (0..2000).map { it / 2000f }.first { Glint.ratioFor(voice, stillTune, it, 1f) == 3f }
            val k = Glint.ratioFor(voice, stillTune, peak, 1f)
            assertEquals(3f, k, 1e-6f, "$voice: test setup expected k=3")
            val f0 = Glint.frequencyFor(voice, stillTune)
            val still = mapOf("TUNE" to stillTune, "BLOOM" to 0.5f, "BODY" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            val snip = Glint.render(voice, still + ("PEAK" to peak))
            val atK = energyAt(snip.samples, k * f0, snip.sampleRate)
            val atDoubled = energyAt(snip.samples, 2f * k * f0, snip.sampleRate)
            val atHalved = energyAt(snip.samples, k * f0 / 2f, snip.sampleRate)
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
        // The mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX/RESIN carry -
        // but it only means anything at the TOP of the sweep. At k=8 and mid
        // TUNE the burst is nowhere near Nyquist and oversampling changes
        // nothing. At PEAK 1 and TUNE 1, k is 40 and k*f0 reaches 17.6 kHz on
        // SWEEP and BRASS (STEP's root sits an octave higher), which is where
        // a native-rate render starts to fold. SWEEP and BRASS are the tight
        // ones, hence the 0.001 threshold rather than the 0.002 other engines
        // use. The corner is held still (BLOOM 0.5): a path would only move
        // k, and this test asks about the carrier's height.
        //
        // Measured 2026-09-29 at that corner: SWEEP and BRASS 0.00271, STEP
        // 0.03873.
        val corner = mapOf("PEAK" to 1f, "TUNE" to 1f, "BLOOM" to 0.5f, "BODY" to 0f, "FOLLOW" to 1f)
        for (voice in GlintVoice.entries) {
            // Ruled exemption, 2026-09-29: VOWEL's formants top out near 2.7
            // kHz (EE's F2 at BODY 1), so there is no fold to measure (avgDiff
            // 2.2e-5 against 1e-3); `render` is one function, so SWEEP, STEP
            // and BRASS prove the dispatch. At the four corners tried (PEAK 1
            // or 0.75, TUNE 0 or 1, BODY 0 or 1) it reads 7.3e-6 to 2.9e-5.
            // The bar is untouched.
            if (voice == GlintVoice.VOWEL) continue
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
        // Off-harmonic target is 0.25, not 0.5: the discontinuity this test
        // exists to catch is w(1-)*|sin(2*pi*k)|, and |sin(2*pi*k)| is 0 at
        // BOTH a fractional part of 0.0 (integer k) and 0.5 (half-integer k)
        // - sin(pi*n) is 0 either way. A window broken to end at 0.5 instead
        // of exactly 0 (a total violation of the engine's founding property)
        // therefore lands on silence at the wrap regardless of what the
        // window does, and a half-integer target is blind to exactly the
        // defect this test names. At 0.25, where |sin(2*pi*k)| = 1, the same
        // break spikes the off-harmonic step. 1.5f is the comparison factor.
        //
        // Measured 2026-09-29 (largest sample-to-sample step, on-harmonic
        // k=40, off-harmonic k=17.25): SWEEP and BRASS 0.2839 / 0.1244 (0.44x),
        // STEP 0.5668 / 0.2553 (0.45x) - inside the bar with room. Mutation-
        // verified the same day: forcing the window to `1f - 0.5f * p` (ending
        // at 0.5 instead of 0) gives SWEEP and BRASS 0.2856 / 0.4381, ratio
        // 1.534 - FAILS, as it must. The bite comes from those two voices;
        // STEP's ratio stays 0.78 under the same break, because its carrier,
        // an octave higher, already takes larger natural steps. Reverted
        // immediately after.
        //
        // BLOOM is held still (0.5), so k is constant and the only thing a
        // click could come from is the wrap itself. The path voices move k
        // only at a wrap, where the window is zero (see `synthesize`), and
        // `STEP does not click when it steps` covers a k that does move.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PATH_VOICES) {
            val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0.5f, "BODY" to 0f, "FOLLOW" to 1f)
            fun maxStep(peak: Float): Float {
                val s = Glint.synthesize(voice, still + ("PEAK" to peak), rate)
                var worst = 0f
                for (i in 1 until s.size) {
                    val d = kotlin.math.abs(s[i] - s[i - 1])
                    if (d > worst) worst = d
                }
                return worst
            }
            // Find a PEAK landing closest to an integer ratio in the free
            // (unsnapped) region above SNAP_CEILING, and one landing closest
            // to a fractional part of 0.25, where |sin(2*pi*k)| = 1 and a
            // broken window has nowhere to hide. minByOrNull rather than
            // first{tolerance}: the ratio map is exponential, so step size
            // near the top is coarse and a fixed tolerance can miss entirely
            // and throw instead of failing.
            val candidates = (0..4000).map { it / 4000f }.filter { Glint.ratioAtReference(it) > Glint.SNAP_CEILING + 1f }
            val onHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0f) }!!
            val offHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0.25f) }!!
            assertTrue(
                maxStep(offHarmonic) < maxStep(onHarmonic) * 1.5f,
                "$voice: a fractional ratio must not click — ${maxStep(offHarmonic)} vs ${maxStep(onHarmonic)}",
            )
        }
    }

    @Test
    fun `FOLLOW 1 rides the note and FOLLOW 0 stands still`() {
        for (voice in PATH_VOICES) {
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
        // stays put when the peak is parked. Measured on SWEEP, held still
        // (BLOOM 0.5) so a path does not blur the comparison.
        //
        // At PEAK 0.5 (ratio 8.94, well below SNAP_CEILING) and FOLLOW=1,
        // keyTrack cancels the note dependence outright, so both notes land
        // on the same snapped harmonic (k=9) regardless of TUNE - the effect
        // below is visible without raising PEAK because it's the absolute Hz
        // that climbs with the note, not the ratio. At FOLLOW=0 the two notes
        // do NOT land on the same harmonic - the peak's Hz is fixed instead,
        // so the ratio falls as the note rises (free above SNAP_CEILING at
        // TUNE 0.1, snapped at TUNE 0.9) - but the point of the parked case
        // is that the absolute Hz holds flat despite that: the snap does not
        // hide the effect.
        //
        // Measured 2026-09-29: k = 15.94 at TUNE 0.1 and 5 at TUNE 0.9 with
        // FOLLOW 0; ridesLow 1074.8 Hz, ridesHigh 3410.9 Hz (3.17x, clears the
        // >1.8x bar); parkedLow 1920.8 Hz, parkedHigh 1863.5 Hz (0.970x,
        // clears the <1.35x bar).
        val voice = GlintVoice.SWEEP
        val still = mapOf("PEAK" to 0.5f, "BLOOM" to 0.5f, "BODY" to 0.2f, "DECAY" to 0.6f)
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
        for (voice in PATH_VOICES) {
            for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (follow in listOf(0f, 0.5f, 1f)) {
                    for (peak in listOf(0f, 0.5f, 1f)) {
                        val k = Glint.ratioFor(voice, tune, peak, follow)
                        assertTrue(
                            k >= Glint.K_MIN - 1e-4f && k <= Glint.K_MAX + 1e-4f,
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
        // The old BODY mixed the mean-removed window back in - the same
        // harmonic series the burst already carries, spread down from k*f0.
        // Four reviewers independently found it inaudible for that reason,
        // and Josh's audition agreed: "Body 1 doesn't really seem to have an
        // impact." The replacement is a genuine second burst at k/5.6, so
        // there is energy near k2*f0 that BODY 0 does not have at all.
        //
        // The probe is energy at k2*f0 by Goertzel, bare (BODY 0) against
        // full (BODY 1), held still (BLOOM 0.5) so the main burst does not
        // sweep through the probe. It must clear a 3x bar.
        //
        // Measured 2026-09-29 (bare -> full, energy at k2*f0): SWEEP and BRASS
        // 3.9505E-4 -> 0.12315 (312x), STEP 1.2023E-4 -> 0.031590 (263x).
        for (voice in PATH_VOICES) {
            val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.8f, "BLOOM" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            val f0 = Glint.frequencyFor(voice, 0.5f)
            val k = Glint.ratioFor(voice, 0.5f, 0.8f, 1f)
            val k2 = Glint.bodyRatio(k)
            val bareSnip = Glint.render(voice, still + ("BODY" to 0f))
            val fullSnip = Glint.render(voice, still + ("BODY" to 1f))
            val probeHz = k2 * f0
            val bare = energyAt(bareSnip.samples, probeHz, bareSnip.sampleRate)
            val full = energyAt(fullSnip.samples, probeHz, fullSnip.sampleRate)
            assertTrue(full > bare * 3f, "$voice: BODY should put real energy at $probeHz Hz ($bare -> $full)")
        }
    }

    @Test
    fun `BODY carries a small, bounded DC that decays with the envelope`() {
        // A windowed sine is NOT DC-free in general - only a window
        // symmetric about phase 0.5 nulls integral(w(phi) * sin(2*pi*k*phi), phi, 0, 1),
        // and GLINT's one window, the saw ramp (w = 1-phi), is not: it
        // integrates to 1/(2*pi*k) for every integer k. Mean-removing BODY to
        // cancel that residual would stop it reaching exactly zero at the
        // cycle wrap - the property the class doc calls "the whole engine" -
        // so it is left in deliberately, and this test's job is to show the
        // residual is small and decaying, not to claim it is absent.
        //
        // Averaging the whole buffer (the old form of this test) can't see
        // that: the decayed tail is far longer and far quieter than the
        // head, so it dominates the mean and reads the same regardless of
        // BODY - a bound that never moves is not testing anything. This
        // version measures the head window instead, where the DC is actually
        // largest: DC over the first 50 ms, as a fraction of that window's
        // own peak, held still (BLOOM 0.5), BODY 0 -> 1.
        //
        // PEAK is deliberately left at its default and NOT swept, and the
        // 0.05 bound is only valid there. The burst carries this same window
        // asymmetry and carries it worse at low k (the integral above goes as
        // 1/k), so a PEAK sweep added here would fail this test with BODY at
        // 0 - the burst's own DC, not a defect and not BODY's doing - and
        // point at the wrong component. Widening the sweep means re-deriving
        // the bound per PEAK first.
        //
        // Measured 2026-09-29 (head-window DC as a fraction of that window's
        // peak, BODY 0 -> 1): SWEEP and BRASS 0.0136 -> 0.0340, STEP 0.0140 ->
        // 0.0346. At PEAK 0 with BODY 0 the same ratio is 0.0598 (SWEEP,
        // BRASS) and 0.0608 (STEP): over the 0.05 bound with the body term
        // off, which is the burst's own DC, as the paragraph above says.
        for (voice in PATH_VOICES) {
            for (body in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val snip = Glint.render(voice, mapOf("BODY" to body, "BLOOM" to 0.5f))
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
        // the burst, so the body evaporating moves it only a few percent -
        // and lowering BODY_DECAY_RATIO makes that WORSE, not better. The
        // share below 2 kHz is what actually changes.
        //
        // A head-vs-tail-within-each-condition version of this test (with a
        // one-sided control bounding only growth) does not discriminate when
        // a voice's own BODY-0.02 control already declines from head to tail
        // with no body term doing anything: a dead body term would still
        // pass. This version compares WITH-body against WITHOUT-body at the
        // *same* time offset instead, which cancels out whatever intrinsic
        // decline a voice has, so the head assertion below cannot be
        // satisfied by a body term that does nothing: with the second burst's
        // level forced to zero the two shares are identical and it fails
        // immediately.
        //
        // This test used to also assert the body burns off by the tail
        // (`withTail < withoutTail * 1.5f`). That assertion is deliberately
        // REMOVED, not tuned to pass: the spec bundled two incompatible
        // claims - BODY_DECAY_RATIO 0.8, single-enveloped (Tomita's slower
        // resonance ringing after the strike) and "the body burns off" (the
        // glass tail) - and the old body's double envelope composed to an
        // effective ~0.31x t60, so fixing the envelope (Ruling A) made the
        // body 2.6x slower while the spec still expected it to vanish. Those
        // cannot both be true, and which one is correct is a design question
        // for the D1 audition to settle by ear, not a number this test
        // should assert.
        //
        // BODY_DECAY_RATIO was swept 0.15 to 5.0 while chasing the old
        // head-vs-tail bar before it was known to be the wrong instrument:
        // lowering it (the pre-authorised direction) makes the differential
        // WORSE, not better, because a faster-decaying body has less energy
        // left in the head window. Do not retry that; the fix was the
        // metric, not the constant.
        //
        // Measured 2026-09-29 (same-offset comparison, low+mid share), head
        // proves the source and the tail is recorded, not asserted: SWEEP and
        // BRASS withHead 0.3323 / withoutHead 0.1441 (2.31x), withTail 0.1793 /
        // withoutTail 0.1435 (1.25x, near parity: it burns off); STEP head
        // 0.3035 / 0.0689 (4.40x), tail 0.1156 / 0.0676 (1.71x). The head ratio
        // clears the 1.5x bar on all three.
        for (voice in PATH_VOICES) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.75f, "BLOOM" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            fun shares(body: Float): Pair<Float, Float> {
                val snip = Glint.render(voice, still + ("BODY" to body))
                return lowMid(slice(snip, 0f, 0.1f)) to
                    lowMid(slice(snip, snip.durationSeconds * 0.6f, snip.durationSeconds))
            }
            val (withHead, _) = shares(0.9f)
            val (withoutHead, _) = shares(0.02f)
            // The body is plainly there at the strike - a real second source.
            // Whether it burns off or rings on by the tail is a design
            // question for the D1 audition (see the comment above), not
            // asserted here.
            assertTrue(withHead > withoutHead * 1.5f, "$voice: BODY should be audible at the head ($withoutHead -> $withHead)")
        }
    }

    /**
     * Voices whose BLOOM path settles on PEAK inside the note: SWEEP runs it
     * on the fixed [Glint.BLOOM_T60] clock, BRASS on the note's own level.
     * The shared assertion both BLOOM tests below apply to this group.
     * STEP's ladder takes [Glint.STEP_SECONDS] per rung and can outlast the
     * note (a full-BLOOM ladder at the default PEAK is over twenty rungs,
     * several seconds, against a note that renders under 2 s at any DECAY),
     * so it is deliberately left out of this set and given its own,
     * direction-only branch rather than folded in under a weakened bar.
     *
     * BRASS stays in this set on structure, not coincidence: `Dsp.Env.at`'s
     * decay always reaches roughly -60 dB (0.1% of peak) by one t60, and
     * `synthesize`'s own `frames = t60 * 1.35 * rate` guarantees the rendered
     * note is 1.35x that t60 long - so BRASS's coupling (x = the note's own
     * level) has collapsed back to PEAK well before this set's
     * 0.7x-to-1.0x-duration tail window, for ANY DECAY. STEP's reach is set
     * by BLOOM alone, independent of DECAY, which is exactly what it does
     * NOT get for free.
     */
    private val BLOOM_SWEEPS_AND_SETTLES = setOf(GlintVoice.SWEEP, GlintVoice.BRASS)

    /**
     * Fixture: TUNE 0.3 PEAK 0.4 BODY 0.2 FOLLOW 1 DECAY 0.7 - head is the
     * first 12 ms, tail the last 30% of the note, and the assertion is the
     * head/tail centroid ratio.
     *
     * BLOOM is signed, so the test asserts all three positions. 0.5 (still)
     * leaves head and tail alike (0.8..1.2). Above centre the head opens above
     * the tail - a fall into PEAK. Below centre the head starts under the tail
     * - a rise into PEAK.
     *
     * The bars for [BLOOM_SWEEPS_AND_SETTLES] are the strict ones (1.4x
     * falling, inside 1/1.2 rising) because those voices land on PEAK before
     * the tail window. STEP is direction-only (1.1x either way): its ladder
     * can outlast the note ([Glint.STEP_SECONDS] per rung), so its tail is
     * still on the way when the note ends and its ratio is smaller than a
     * settled voice's.
     *
     * Measured 2026-09-29 (head/tail centroid ratio at BLOOM 0 / 0.5 / 1):
     *   SWEEP 0.3146 / 1.0013 / 3.5565
     *   BRASS 0.3045 / 1.0013 / 3.6921
     *   STEP  0.3274 / 0.9931 / 1.1502   (BLOOM 0.75: 1.2939)
     * STEP's BLOOM 1 is the thin one, 4.6% over its 1.1x bar: kBase is 7 here,
     * so the full ladder is 22 rungs (k 28 -> 7) and 3.3 s long against a note
     * of 0.90 s. By the tail window (0.63 s on) it has descended only to rungs
     * 4..6 (k 24 -> 22), so the ratio sits near 1. A longer ladder makes that
     * ratio smaller, not larger, which is why the bar is direction-only.
     */
    @Test
    fun `BLOOM opens the peak at the attack and lets it settle`() {
        for (voice in PATH_VOICES) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun headToTail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                val head = FeatureExtractor.extract(slice(snip, 0f, 0.012f)).centroidHz
                val tail = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds)).centroidHz
                return head / tail
            }
            assertTrue(headToTail(0.5f) in 0.8f..1.2f, "$voice BLOOM 0.5 should leave head and tail alike: ${headToTail(0.5f)}")
            when (voice) {
                in BLOOM_SWEEPS_AND_SETTLES -> {
                    assertTrue(headToTail(1f) > 1.4f, "$voice BLOOM 1 should open the head above the tail: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) < 1f / 1.2f, "$voice BLOOM 0 should start the head below the tail: ${headToTail(0f)}")
                }
                // STEP's ladder can outlast the note (STEP_SECONDS per rung),
                // so its tail need not reach PEAK: assert direction only.
                GlintVoice.STEP -> {
                    assertTrue(headToTail(1f) > 1.1f, "STEP BLOOM 1 should begin above where it is heading: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) < 1f / 1.1f, "STEP BLOOM 0 should begin below where it is heading: ${headToTail(0f)}")
                }
                else -> error("$voice has no BLOOM assertion in this test")
            }
        }
    }

    /**
     * Same still as above: tail centroid (0.75 * duration to the end) at
     * BLOOM 0 and BLOOM 1 against BLOOM 0.5 (still). Whichever way the path
     * came in, by the tail it must be sitting on PEAK. For SWEEP,
     * envAt(0.678, 0.45) is ~3e-5 at 0.75 * 0.9044 s - the sweep is fully
     * settled by five-plus t60s whatever BLOOM's sign. For BRASS the
     * structural argument in [BLOOM_SWEEPS_AND_SETTLES]'s doc applies:
     * 0.75 * duration is ~1.01 t60 of its own DECAY-derived envelope, past
     * the point where any DECAY's amp envelope has collapsed to background
     * level.
     *
     * Does not apply to STEP: its ladder is a staircase with no reason to
     * return to PEAK inside the note, and at this fixture a full-BLOOM ladder
     * takes several seconds to arrive - see the test above.
     *
     * Measured 2026-09-29, tail centroid at BLOOM 0 -> 0.5 -> 1: SWEEP 1126.4
     * Hz at every BLOOM (identical to 0.1 Hz); BRASS 1125.5 -> 1126.4 ->
     * 1127.4 Hz (0.09% apart). Two orders of magnitude inside the 20% bar.
     */
    @Test
    fun `BLOOM lands before the note ends, whatever it did on the way`() {
        for (voice in BLOOM_SWEEPS_AND_SETTLES) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun tail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                return FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.75f, snip.durationSeconds)).centroidHz
            }
            val landed = tail(0.5f)
            for (bloom in listOf(0f, 1f)) {
                assertTrue(
                    kotlin.math.abs(tail(bloom) - landed) < landed * 0.2f,
                    "$voice BLOOM $bloom must have landed on PEAK by the tail: ${tail(bloom)} vs $landed",
                )
            }
        }
    }

    @Test
    fun `BLOOM sweeps slowly enough to hear`() {
        // The rate used to run 0.30 s down to 0.06 s as BLOOM rose - depth
        // and rate on one knob, so a big sweep was always a fast one. At the
        // shipped coupling the centroid fell to 0.92x of its opening value
        // and then sat flat: a control that measured as nearly static and
        // was heard as "I don't get a sense of movement". At a fixed 0.45 s
        // it travels to 0.35x over 300 ms, the one change the 2026-09-26
        // audition marked KEEP.
        //
        // The assertion is the rate itself, at the moment the two constants
        // differ most. At 0.05 s the old 0.06 s sweep was finished
        // (envAt(0.05, 0.06) = 0.003); the new one is still well open. A
        // test that the rate is independent of depth would be tautological
        // now that the rate is a constant - this tests the constant's value.
        //
        // Measured 2026-09-29: early 3120.9 Hz against settled 1943.7 Hz (ratio
        // 1.606, bar 1.25). The figure is lower than the 1.987 the first
        // version of this test read because the path now interpolates in
        // log-ratio (kBase * 4^x) where the old sweep was linear
        // (kBase * (1 + 3x)): on the same 0.45 s clock, at 50 ms it sits
        // nearer PEAK.
        val voice = GlintVoice.SWEEP
        val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.5f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.85f)
        val snip = Glint.render(voice, still + ("BLOOM" to 1f))
        val early = FeatureExtractor.extract(slice(snip, 0.04f, 0.08f)).centroidHz
        val settled = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.8f, snip.durationSeconds)).centroidHz
        assertTrue(early > settled * 1.25f, "the sweep should still be open at 50 ms: $early vs settled $settled")
    }

    @Test
    fun `BLOOM at its ugliest still renders legal audio`() {
        // GLINT has no feedback path, so BLOOM is allowed to be ugly at
        // either end - but ugly must still be finite, in range and in tune.
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0f, 1f)) {
                for (peak in listOf(0f, 0.5f, 1f)) {
                    val snip = Glint.render(voice, mapOf("BLOOM" to bloom, "PEAK" to peak, "BODY" to 1f))
                    assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice BLOOM $bloom PEAK $peak broke range")
                    assertTrue(snip.peak() > 0.5f, "$voice BLOOM $bloom PEAK $peak too quiet")
                }
            }
        }
    }

    @Test
    fun `a GLINT patch round-trips through JSON`() {
        val patch = GlintPatch("Glass Test", GlintVoice.SWEEP, mapOf("PEAK" to 0.7f, "FOLLOW" to 0.2f, "BLOOM" to 0.2f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `a GLINT patch rejects a macro the voice does not have`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.SWEEP, mapOf("CUTOFF" to 0.5f))
        }
    }

    @Test
    fun `a GLINT patch rejects a macro out of range`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.SWEEP, mapOf("PEAK" to 1.4f))
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
        // read 1650.29 Hz soft against 1648.09 Hz hard - backwards. A macro
        // earns its place with a sweep that never falls, per voice.
        //
        // Strictly-rising was the original bar here, and it cannot hold: two
        // adjacent PEAK steps can both snap to the same k and render
        // byte-identical. That is not a defect - it is what
        // `PEAK values inside one snap zone render identically` REQUIRES for
        // any two PEAK values landing on the same snapped k. The suite would
        // be contradicting itself: one test demanding byte-identity inside a
        // snap zone, this one forbidding it. Both are satisfiable together
        // only when no two of the 9 grid points share a snapped k, which is
        // an accident of this grid's spacing, not a property PEAK has. Ties
        // are normal snap behaviour, on every voice: the snap is one function
        // of PEAK shared by all three.
        //
        // So the bar is non-decreasing at every step, plus a 3x end-to-end
        // rise so a macro that merely plateaus the whole way (dead, not
        // snapped) still fails - not `PluckTest.kt`'s "PICK is dead between"
        // per-step `abs(diff) > 1f` clause, which would fail here for exactly
        // the same reason a strict rise does: a snapped macro has legitimate
        // zero-diff steps.
        //
        // The sweep is held still (BLOOM 0.5) so PEAK is the only thing
        // moving the centroid: at any other BLOOM the head window would be
        // reading where the path starts, not where PEAK sits. The readings
        // print on every run.
        //
        // Measured 2026-09-29, the gate this test locks down:
        //   PEAK sweep SWEEP: 359.3, 517.6, 726.9, 1107.8, 1678.0, 2440.4, 3568.1, 5207.1, 7589.9
        //   PEAK sweep STEP:  718.4, 1034.9, 1453.5, 2215.1, 3354.8, 4878.7, 7131.5, 10403.7, 15145.9
        //   PEAK sweep BRASS: 359.3, 517.6, 726.9, 1107.8, 1678.0, 2440.4, 3568.1, 5207.1, 7589.9
        // BRASS reads the same as SWEEP because at BLOOM 0.5 neither has a
        // path. At 0.01 PEAK spacing (100 adjacent pairs) every voice ties 36
        // times - the snap, not a broken knob: the snapped k repeats 36 times -
        // and falls 0 times, so non-decreasing is not vacuous. End/start
        // centroid ratio at the 9-point grid: SWEEP 21.1x, STEP 21.1x, BRASS
        // 21.1x, against the 3x bar.
        for (voice in GlintVoice.entries) {
            // Ruled exemption, 2026-09-29: VOWEL leaves this gate for good. Its
            // centroid rises from OO to AH and then falls (EH and EE have lower
            // F1s than AH), so both bars miss: it falls at step 5, and end to
            // start is 1.76x against the 3x bar. Measured at this fixture,
            // TUNE 0.4:
            //   PEAK sweep VOWEL: 324.9, 416.4, 529.7, 609.9, 694.8, 675.8, 680.6, 607.2, 571.1
            // OO and OH are not inverted, so the spec's swap is not the fix.
            // The line is ordered on F2 presence, not on centroid, and
            // `GlintVowelTest`'s `VOWEL's PEAK sweep is monotonic in F2
            // presence, the axis the line is ordered on` gates VOWEL's PEAK on
            // that instead.
            if (voice == GlintVoice.VOWEL) continue
            val still = mapOf("TUNE" to 0.4f, "BLOOM" to 0.5f, "BODY" to 0.3f, "FOLLOW" to 1f, "DECAY" to 0.6f)
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
        val patch = GlintPatch("Vel Test", GlintVoice.SWEEP, Glint.defaults(GlintVoice.SWEEP))
        assertEquals("PEAK", Velocity.brightnessSpec(patch)?.name, "GLINT should render velocity through PEAK")
    }

    @Test
    fun `atVelocity is genuinely darker at low velocity`() {
        // Does NOT discriminate PEAK from the soften() fallback: Velocity.soften
        // also darkens a soft hit, so this assertion would pass even if PEAK were
        // never wired up. The test directly above this one (`GLINT uses PEAK for
        // velocity, not the soften fallback`) is what proves the real routing, but
        // it only checks SWEEP. This one earns its place by covering every
        // voice - a coarse "velocity is directionally correct everywhere" guard
        // that the routing test alone doesn't give. VOWEL passes here at its
        // default PEAK only, because by centroid its soft key reads brighter
        // than its hard one above PEAK 0.5; `GlintVowelTest`'s `a soft VOWEL
        // key sings a vowel with less F2, at every PEAK` covers the whole knob
        // on F2 presence, the axis the line is ordered on.
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

    /**
     * The spec's requirement: measure the centroid in windows and assert the
     * plateaus. A glide would show a different centroid in every window;
     * steps show runs of equal ones with jumps between.
     *
     * The 0.25x bar is only a discriminator if a continuous ramp cannot clear
     * it. The control for that is SWEEP: the same journey (same BLOOM, same
     * everything else) on a smooth exponential clock instead of rungs - a
     * ramp by construction, and the mechanism-absent case this bar has to
     * fail on.
     *
     * Measured 2026-09-29 at BLOOM 1, DECAY 0.9, BODY 0 (early / late / next
     * centroid, then |late - early| over |next - early|): STEP 13982.4 /
     * 13993.2 / 13550.9 Hz, 10.9 over 431.4 = 0.025 - a tenth of the bar.
     * Controls, same fixture: SWEEP 0.614 and BRASS 0.479, both far over it, so
     * a continuous ramp cannot pass by accident. At BLOOM 0 (a rising ladder)
     * STEP reads 0.024 and SWEEP 0.499.
     */
    @Test
    fun `STEP's formant is piecewise constant, not a ramp`() {
        val snip = Glint.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
        val step = Glint.STEP_SECONDS
        // Two probes inside one step must agree; probes either side of a step
        // boundary must not.
        val early = FeatureExtractor.extract(slice(snip, step * 0.25f, step * 0.45f)).centroidHz
        val late = FeatureExtractor.extract(slice(snip, step * 0.55f, step * 0.75f)).centroidHz
        val next = FeatureExtractor.extract(slice(snip, step * 1.25f, step * 1.45f)).centroidHz
        assertTrue(
            kotlin.math.abs(late - early) < kotlin.math.abs(next - early) * 0.25f,
            "STEP: within-step centroid moved $early -> $late, across-step moved $early -> $next — that is a ramp, not a staircase",
        )
    }

    @Test
    fun `STEP travels further the further BLOOM sits from centre, either way`() {
        val kBase = 8f
        fun rungs(bloom: Float) = Glint.stepLadder(kBase, Glint.startRatio(kBase, Glint.bloomSign(bloom))).size
        assertEquals(1, rungs(0.5f))
        assertTrue(rungs(0.75f) < rungs(1f), "falling: ${rungs(0.75f)} vs ${rungs(1f)}")
        assertTrue(rungs(0.25f) < rungs(0f), "rising: ${rungs(0.25f)} vs ${rungs(0f)}")
    }

    /**
     * The step is quantised to the phase wrap, where the window has just
     * reached zero - the one instant any `k` starts a cycle from silence
     * instead of interrupting a wide-open window mid-sine.
     *
     * Compare STEP against itself, not against another voice: a window
     * straddling a step boundary against an equal-width window fully inside
     * one rung, close enough in time that the envelope has barely moved
     * between them. Same voice, same render, same render call, differing
     * only in whether a step boundary falls inside the window. (A second
     * voice as the control would be confounded by its own carrier: a sine's
     * sample-to-sample step scales with frequency, and two voices at the same
     * nominal k sit at different carriers whenever their roots differ.)
     *
     * Measured on `synthesize`'s raw oversampled buffer, not `render`:
     * `Dsp.decimate` low-passes to the output Nyquist, which is exactly the
     * filter that would smooth a wrap discontinuity away before it could be
     * seen.
     *
     * TUNE must be off STEP's default (0.5, i.e. 440 Hz): the root (220 Hz)
     * times [Glint.STEP_SECONDS] (0.15 s) is 33, an integer, so every octave
     * of the root (TUNE 0, 0.5, 1 -> 220/440/880 Hz) lands the nominal step
     * boundary on an exact whole number of cycles - the step and the phase
     * wrap coincide by construction there, and a step taken mid-cycle would
     * have nowhere to land to show itself. TUNE=0.3 -> f0=329.6 Hz ->
     * 329.6*0.15=49.4, comfortably off-integer, exposes it.
     *
     * Measured 2026-09-29 at TUNE 0.3: straddle 0.15471 against inside 0.11398
     * (1.357x, clears the 3x bar). Mutation-verified the same day: moving the
     * ratio update out of the wrap block, so k is read every sample (the exact
     * mistake this design guards against), gives straddle 0.39973 (3.507x) -
     * FAILS, as it must. The margin over the bar is modest, because a
     * one-harmonic step at k of about 36 is a small jump. Reverted
     * immediately after.
     */
    @Test
    fun `STEP does not click when it steps`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        // TUNE=0.3, not left at its 0.5 default - see this test's own KDoc
        // for why the default's exact-integer step/cycle ratio would hide
        // the very bug this test exists to catch.
        val raw = Glint.synthesize(GlintVoice.STEP, mapOf("TUNE" to 0.3f, "BLOOM" to 1f, "DECAY" to 0.9f), rate)
        val snip = Snip(raw, 1, rate)
        val step = Glint.STEP_SECONDS
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
            "STEP's worst jump straddling a step boundary ($straddle) vs fully inside one rung ($inside) — the step is clicking",
        )
    }

    /**
     * The spec's central claim for BRASS: brightness follows loudness - k
     * rides the note's own level, so a struck brass note is brightest at the
     * strike and its formant falls as the note does.
     *
     * Fixture: `Glint.defaults(BRASS)` (TUNE 0.5, PEAK 0.45, FOLLOW 0.8) +
     * BLOOM 1, DECAY 0.9, BODY 0 (silences the second formant so only the
     * main burst's ratio drives the centroid).
     *
     * This bar does NOT by itself discriminate BRASS's coupling from SWEEP's
     * fixed clock: that also falls from an opened head to a settled tail, and
     * its 0.45 s t60 is settled by this fixture's 0.45-0.60 s tail window
     * whatever DECAY is. `BRASS's fall tracks the amplitude envelope, not a
     * separate curve`, immediately below, is the test that actually tells the
     * two mechanisms apart - the discriminating power lives there, not here.
     *
     * Measured 2026-09-29: BRASS head 4459.1 Hz, tail 1836.3 Hz (0.412 against
     * the 0.8 bar). SWEEP on its plain clock at the same fixture reads head
     * 2995.0, tail 1726.9 (0.577), which clears the bar too - as this note
     * says it must.
     */
    @Test
    fun `BRASS's formant falls as the note decays`() {
        val snip = Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
        val head = FeatureExtractor.extract(slice(snip, 0.02f, 0.10f)).centroidHz
        val tail = FeatureExtractor.extract(slice(snip, 0.45f, 0.60f)).centroidHz
        assertTrue(tail < head * 0.8f, "BRASS's centroid went $head -> $tail — it did not fall")
    }

    /**
     * The one test in this trio that actually separates BRASS from SWEEP:
     * BRASS's path runs on the note's own DECAY, so a longer note must still
     * be bright at a fixed wall-clock instant where a shorter note has
     * already gone dark. A sweep on the constant BLOOM_T60 has no idea how
     * long the note is and reads the same at that instant either way.
     *
     * Fixture: BLOOM 1, BODY 0, everything else at `Glint.defaults(BRASS)`;
     * DECAY 0.3 (t60 ~0.251 s, duration ~0.339 s) for the short note, DECAY
     * 0.95 (t60 ~1.238 s, duration ~1.672 s) for the long one; both probed at
     * 0.25-0.30 s. The short note's probe sits deep in its own tail (its amp
     * envelope is ~0.001 of peak there), so the slice has to be a real
     * reading and not `FeatureExtractor`'s silent-buffer fallback, which
     * would return a centroid of exactly 0 and pass this bar vacuously - the
     * probed slice holds 2205 frames and must peak well above the
     * `total <= EPSILON` (1e-10) floor that triggers the fallback.
     *
     * Measured 2026-09-29: BRASS shortC 1728.6 Hz, longC 2302.1 Hz (1.332x,
     * bar 1.15); the short slice peaks at 1.1E-3 and the long one at 0.174 -
     * real readings, far above the floor. The control, SWEEP on its fixed
     * BLOOM_T60 clock, reads shortC 1760.0, longC 1759.1 (0.9995x) - fails the
     * bar cleanly, which is what makes the bar separate the two mechanisms
     * instead of passing by default.
     */
    @Test
    fun `BRASS's fall tracks the amplitude envelope, not a separate curve`() {
        // The spec's requirement. x is the note's own level, so a LONGER
        // note must hold its brightness longer in absolute time: the coupling
        // has no clock of its own. A fixed-rate sweep would fall at the same
        // wall-clock rate regardless of DECAY.
        val short = Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 1f, "DECAY" to 0.3f, "BODY" to 0f))
        val long = Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 1f, "DECAY" to 0.95f, "BODY" to 0f))
        val at = 0.25f
        val shortC = FeatureExtractor.extract(slice(short, at, at + 0.05f)).centroidHz
        val longC = FeatureExtractor.extract(slice(long, at, at + 0.05f)).centroidHz
        assertTrue(
            longC > shortC * 1.15f,
            "at ${at}s the long note's centroid is $longC and the short note's is $shortC — the fall is not tied to the envelope",
        )
    }

    /**
     * The control: at BLOOM 0.5 the sign value is 0, so the path's start is
     * PEAK itself and there is nowhere for the coupling to go - BRASS is an
     * ordinary fixed-ratio saw-window voice. If this fails, BLOOM is not
     * actually gating the coupling.
     *
     * Measured 2026-09-29: head 1725.7 Hz, tail 1725.6 Hz (0.0001 against the
     * 0.15 bar).
     */
    @Test
    fun `BRASS at BLOOM centre does not move its formant`() {
        val snip = Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 0.5f, "DECAY" to 0.9f, "BODY" to 0f))
        val head = FeatureExtractor.extract(slice(snip, 0.02f, 0.10f)).centroidHz
        val tail = FeatureExtractor.extract(slice(snip, 0.45f, 0.60f)).centroidHz
        assertTrue(
            kotlin.math.abs(tail - head) < head * 0.15f,
            "BRASS at BLOOM 0.5 moved its centroid $head -> $tail",
        )
    }
}
