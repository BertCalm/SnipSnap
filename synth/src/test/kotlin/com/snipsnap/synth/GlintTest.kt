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
    fun `declared window means match numeric integration`() {
        // BODY subtracts these to kill DC. A wrong constant is a DC offset
        // that only shows up after normalization, so pin them here.
        for (voice in GlintVoice.entries) {
            var sum = 0.0
            val n = 100_000
            for (i in 0 until n) sum += Glint.windowAt(voice, i.toFloat() / n)
            val measured = (sum / n).toFloat()
            assertEquals(measured, Glint.windowMean(voice), 1e-3f, "$voice window mean")
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

    /** [x] linearly interpolated at a fractional sample position. */
    private fun sampleAt(x: FloatArray, pos: Float): Float {
        val i = pos.toInt()
        val f = pos - i
        return x[i] * (1f - f) + x[i + 1] * f
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
        // Measured 2026-09-26 across 3 voices x 4 PEAK settings x both BLOOM
        // extremes: worst case 0.99188 at BLOOM 0, dropping to 0.98643 at
        // BLOOM 1 (BOTTLE, PEAK 0.9) - still comfortably above the bar. The
        // period is fixed by the window's wrap independent of k, so BLOOM's
        // time-varying k only changes in-cycle shape, not period; a
        // one-period correlation measures exactly that shape identity, so
        // the small drop is the sweep genuinely changing the waveform's
        // shape cycle to cycle while its period stays exact - the opposite
        // signature from phase drift, which would worsen with elapsed time
        // rather than with sweep speed. A synthetic control whose pitch
        // drifts 9% scores 0.73149, so the 0.98 threshold has a wide margin
        // and can still fail.
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0f, 1f)) {
                val still = mapOf("TUNE" to 0.5f, "BLOOM" to bloom, "BODY" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.7f)
                val f0 = Glint.frequencyFor(voice, 0.5f)
                for (peak in listOf(0.1f, 0.35f, 0.6f, 0.9f)) {
                    val corr = periodCorrelation(Glint.render(voice, still + ("PEAK" to peak)), f0)
                    assertTrue(corr > 0.98f, "$voice at PEAK $peak BLOOM $bloom: period broke, correlation $corr")
                }
            }
        }
    }

    @Test
    fun `the ratio snaps to harmonics below the ceiling and runs free above it`() {
        for (k in listOf(2.4f, 3.7f, 11.6f)) {
            assertEquals(Math.round(k).toFloat(), Glint.snapRatio(k), 1e-6f, "k=$k should snap")
        }
        for (k in listOf(12.7f, 23.4f, 39.1f)) {
            assertEquals(k, Glint.snapRatio(k), 1e-6f, "k=$k should run free")
        }
    }

    @Test
    fun `PEAK values inside one snap zone render identically`() {
        // Snapping is real, not cosmetic: two PEAK settings that land on the
        // same harmonic must produce the same bytes.
        val voice = GlintVoice.BOTTLE
        val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "FOLLOW" to 1f)
        fun ratioAt(peak: Float) = Glint.snapRatio(Glint.ratioAtReference(peak).coerceIn(Glint.K_MIN, Glint.K_MAX))
        val pairs = (0..100).map { it / 100f }.groupBy { ratioAt(it) }.values.firstOrNull { it.size >= 2 }
        assertTrue(pairs != null, "expected at least one snap zone with two PEAK values in it")
        val a = Glint.render(voice, still + ("PEAK" to pairs!!.first()))
        val b = Glint.render(voice, still + ("PEAK" to pairs.last()))
        assertTrue(a.samples.contentEquals(b.samples), "same snapped harmonic must render the same bytes")
    }

    @Test
    fun `render dispatches through the oversampled path, not directly at RATE`() {
        // The mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX/RESIN carry —
        // but it only means anything at the TOP of the sweep. Simulated
        // 2026-09-25 before this plan was written: at k=8 and mid TUNE the
        // diff is 0.00007 (REED), 0.00000 (BOTTLE), 0.00004 (KAZOO) — the
        // burst is nowhere near Nyquist and oversampling changes nothing.
        // At PEAK 1 and TUNE 1, k is 40 and k*f0 reaches 17.6 kHz: 0.00223
        // (REED), 0.04557 (BOTTLE), 0.06231 (KAZOO). REED is the tight one,
        // hence the 0.001 threshold rather than the 0.002 other engines use.
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
            // Find a PEAK landing closest to an integer ratio above the
            // ceiling, and one landing closest to halfway between two.
            // minByOrNull rather than first{tolerance}: the ratio map is
            // exponential, so step size near the top is coarse and a fixed
            // tolerance can miss entirely and throw instead of failing.
            val candidates = (0..4000).map { it / 4000f }
                .filter { Glint.ratioAtReference(it) > Glint.SNAP_CEILING + 1f }
            val onHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0f) }!!
            val offHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0.5f) }!!
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
        // Measured 2026-09-26 at PEAK 0.5 (ratio well below SNAP_CEILING, so
        // both notes land on the same snapped harmonic and the effect is
        // visible without raising PEAK): ridesLow=2222.7 Hz,
        // ridesHigh=7056.7 Hz (3.17x, clears the >1.8x bar) and
        // parkedLow=3935.5 Hz, parkedHigh=3922.6 Hz (0.997x, clears the
        // <1.35x bar) — the snap did not hide the effect here.
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
    fun `BODY puts a fundamental under the peak`() {
        // Not lowRatio: Features' low band stops at 200 Hz, but BOTTLE and
        // KAZOO are rooted at 220 and TUNE only transposes upward, so their
        // fundamental is always above that edge — measured 0.0000 -> 0.0000
        // for BOTTLE and a slight DECREASE for KAZOO. The energy is really
        // there; the band was wrong. Below 2 kHz catches it for every voice
        // while the burst at PEAK 0.8 sits well above.
        //
        // Measured 2026-09-26, BODY 0 -> 1: REED 0.1257 -> 0.2001 (1.59x),
        // BOTTLE 0.0008 -> 0.1288 (159x), KAZOO 0.0473 -> 0.1092 (2.31x).
        // REED's sawtooth window spreads burst sidebands well down the
        // spectrum, so its bare sub-2kHz floor is already high and BODY's
        // contribution is proportionally smaller even though the effect is
        // real — 1.3x is 22% margin under REED's worst case of 1.59x, and
        // 0.05 is under KAZOO's worst full value of 0.1092.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.2f, "PEAK" to 0.8f, "BLOOM" to 0f, "FOLLOW" to 1f)
            val bare = lowMid(Glint.render(voice, still + ("BODY" to 0f)))
            val full = lowMid(Glint.render(voice, still + ("BODY" to 1f)))
            assertTrue(full > 0.05f, "$voice BODY should put real energy under 2 kHz, got $full")
            assertTrue(full > bare * 1.3f, "$voice BODY should add low end: $bare -> $full")
        }
    }

    @Test
    fun `BODY carries no DC at any setting`() {
        // The window is unipolar. Mixed in raw it would push DC straight
        // through Dsp.levelTo and out to the WAV; windowMean is subtracted
        // to stop that, and this is the assertion that catches a wrong mean.
        for (voice in GlintVoice.entries) {
            for (body in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val snip = Glint.render(voice, mapOf("BODY" to body, "BLOOM" to 0f))
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.02f, "$voice at BODY $body has DC $dc")
            }
        }
    }

    @Test
    fun `the glass tail - the body burns off and leaves the resonance ringing`() {
        // Measured as low+mid share, not centroid: centroidHz is dominated by
        // the burst, so the body evaporating moves it only 2-8% (1.068 /
        // 1.081 / 1.022) — and lowering BODY_DECAY_RATIO makes that WORSE,
        // not better. The share below 2 kHz is what actually changes.
        //
        // Measured 2026-09-26 at BODY 0.9, head -> tail: REED 0.2479 ->
        // 0.1434 (1.73x), BOTTLE 81.8x, KAZOO 0.1488 -> 0.0568 (2.62x). The
        // control at BODY 0.02 is flat for all three (already inside the
        // 1.5x bar), which is the half that proves the fall is the body and
        // not the amp envelope. 1.4x is 24% margin under REED's worst case
        // of 1.73x.
        //
        // BODY_DECAY_RATIO was swept 0.15 to 5.0 while chasing this bar
        // before it was known to be the wrong instrument: lowering it (the
        // pre-authorised direction) makes the differential WORSE, not
        // better, because a faster-decaying body has less energy left in the
        // head window. Do not retry that; the fix was the metric, not the
        // constant.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.75f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            fun headAndTail(body: Float): Pair<Float, Float> {
                val snip = Glint.render(voice, still + ("BODY" to body))
                return lowMid(slice(snip, 0f, 0.1f)) to
                    lowMid(slice(snip, snip.durationSeconds * 0.6f, snip.durationSeconds))
            }
            val (head, tail) = headAndTail(0.9f)
            assertTrue(head > tail * 1.4f, "$voice should turn to glass as it fades: $head -> $tail")
            val (flatHead, flatTail) = headAndTail(0.02f)
            assertTrue(flatTail <= flatHead * 1.5f, "$voice with no body should not change: $flatHead -> $flatTail")
        }
    }

    /**
     * Measured 2026-09-26, still = TUNE 0.3 PEAK 0.4 BODY 0.2 FOLLOW 1 DECAY
     * 0.7 (duration 0.9044s for all three voices), head/tail centroid ratio:
     *   REED   BLOOM 0 -> 1.0028   BLOOM 1 -> 1.8871
     *   BOTTLE BLOOM 0 -> 0.9938   BLOOM 1 -> 1.7900
     *   KAZOO  BLOOM 0 -> 1.0086   BLOOM 1 -> 1.8543
     * Every BLOOM 1 case clears the 1.4x bar by 28-40%; every BLOOM 0 case
     * sits at parity (0.99-1.01), well inside the 1.2x bar.
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
            assertTrue(headToTail(1f) > 1.4f, "$voice BLOOM 1 should open the head well above the tail: ${headToTail(1f)}")
            assertTrue(headToTail(0f) < 1.2f, "$voice BLOOM 0 should leave head and tail alike: ${headToTail(0f)}")
        }
    }

    /**
     * Measured 2026-09-26, same still as above, tail centroid (0.75 * duration
     * to the end) at BLOOM 0 vs BLOOM 1:
     *   REED   1127.9448 Hz -> 1127.9445 Hz (diff 0.0003 Hz)
     *   BOTTLE 2307.9585 Hz -> 2307.9578 Hz (diff 0.0007 Hz)
     *   KAZOO  2286.9421 Hz -> 2286.9434 Hz (diff 0.0013 Hz)
     * BLOOM_SLOW_T60 = 0.30s against a 0.67s DECAY-0.7 t60 leaves the sweep
     * fully settled (envAt past 5 t60s) well before the 0.75-duration mark,
     * so the two tails are identical to four significant figures - nowhere
     * near the 20% bar.
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
            assertTrue(
                kotlin.math.abs(tail(1f) - tail(0f)) < tail(0f) * 0.2f,
                "the sweep must have landed by the tail: ${tail(1f)} vs ${tail(0f)}",
            )
        }
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

}
