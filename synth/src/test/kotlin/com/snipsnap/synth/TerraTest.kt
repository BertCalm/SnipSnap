package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerraTest {

    @Test
    fun `render is bit-for-bit deterministic`() {
        val a = Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass)
        val b = Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass)
        // Snip.equals deliberately compares format and length only, not
        // sample contents (Cleanup.kt's own KDoc on it) - contentEquals is
        // what actually proves bit-for-bit here.
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `render stays in bounds`() {
        val snip = Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass)
        for (s in snip.samples) assertTrue(s in -1f..1f, "sample out of bounds: $s")
    }

    // POS 0 and POS 1 land exactly on Modes.atPosition's own degenerate
    // case (|sin(n*pi*p)| is 0 for every mode at p=0 and at p=1, an integer
    // multiple of pi) - both ends used to silence the whole modal bank,
    // leaving only the bare exciter. A whole-buffer RMS/peak comparison
    // does NOT catch this: Terra.render peak-normalizes the whole buffer,
    // so an exciter-only render (almost entirely true zero) gets scaled up
    // by a much larger factor to reach the same target peak, and its
    // whole-buffer RMS lands surprisingly close to a real render's -
    // measured center=0.1288 vs an unclamped low/high of 0.0801, comfortably
    // over half of center despite carrying no modal ring at all. What
    // normalization can't fake is a window well past every exciter's own
    // duration (HARD_STICK's ~80 samples, FLESH_PALM's worst case ~530):
    // pre-fix that tail is exactly 0 before normalization (0 scaled by
    // anything is still 0), so this checks samples 700-2000 instead, across
    // every voice the fix touched.
    @Test
    fun `pos macro rings the body at both extremes, for every voice`() {
        val presets = mapOf(
            TerraVoice.COMPOUND_MEMBRANE to djembeBass,
            TerraVoice.RESONANT_CAVITY to uduLowWhoomp,
            TerraVoice.CONICAL_BELL to agogoLowBell,
            TerraVoice.TUNED_BAR to balafonKeyGourd,
        )
        val tailStart = 700
        val tailEnd = 2000
        for ((voice, macros) in presets) {
            fun tailPeak(pos: Float): Float {
                val samples = Terra.render(voice, macros + ("POS" to pos)).samples
                var peak = 0f
                for (i in tailStart until minOf(tailEnd, samples.size)) peak = maxOf(peak, abs(samples[i]))
                return peak
            }
            val center = tailPeak(0.5f)
            val low = tailPeak(0f)
            val high = tailPeak(1f)
            // 0.15 leaves a wide margin either side of what's actually
            // measured: real per-voice ratios run from TUNED_BAR's 0.23 up,
            // while a genuine collapse measures exactly 0 (three of the
            // four voices) or a tiny filter-memory residual (RESONANT_
            // CAVITY's cavity biquad) - nowhere near this bar.
            assertTrue(low > center * 0.15f, "$voice POS=0 should still ring the body past the exciter: low=$low center=$center")
            assertTrue(high > center * 0.15f, "$voice POS=1 should still ring the body past the exciter: high=$high center=$center")
        }
    }

    // TERRA had no transient shaping at all (straight from the exciter/
    // modal mix to decimate) - measured as a real contributor to reading
    // "small, meek, dull" next to Thump, which always runs every voice
    // through Punch. Every voice's default now measures a real attack: the
    // first 10ms comfortably louder than the 10-100ms window that follows,
    // never close to a flat, un-shaped sustain (which would sit near 1x).
    @Test
    fun `every default has a real attack, not a flat sustain`() {
        for (voice in TerraVoice.entries) {
            val samples = Terra.render(voice).samples
            fun rms(range: IntRange): Double {
                var sumSq = 0.0
                for (i in range) sumSq += (samples[i] * samples[i]).toDouble()
                return Math.sqrt(sumSq / range.count())
            }
            val onset = rms(0 until minOf(441, samples.size))
            val restEnd = minOf(4410, samples.size)
            val rest = if (restEnd > 441) rms(441 until restEnd) else onset
            assertTrue(onset > rest * 1.5, "$voice's onset should stand out from its own sustain: onset=$onset rest=$rest")
        }
    }

    // Djembe Bass, TERRA_World_Percussion_Synth_Spec.md S5 (Pad 03):
    // fundamental 73Hz, hardness 0.30, droop 0.12, strike position 0.05
    // ("warm thump"). TUNE/DROOP below are this engine's macros solved back
    // to those raw values (Dsp.expMap/Dsp.lin are the forward maps in
    // Terra.compoundMembrane) - FORCE and POS take S5's 0..1 values directly.
    private val djembeBass = mapOf(
        "TUNE" to 0.1362f,
        "FORCE" to 0.30f,
        "POS" to 0.05f,
        "DROOP" to 0.1846f,
    )

    // The spec's own classifier matrix (S6.3) guesses pads 01-06, Djembe Bass
    // among them, read as KICK (S6.3 also cites PERC_HIGH/CHROMATIC_PERC,
    // which do not exist as DrumClass values at all). Measured against the
    // real Classifier: this pad used to fall through to TOM instead
    // (centroidHz=127.95, lowRatio=0.643 - over the plain 100Hz ceiling and
    // short of the stretch rule's 0.85 lowRatio), but that was itself
    // downstream of Modes.atPosition's mirror-symmetric |sin(n*pi*p)| weight
    // law: POS=0.05 was landing on an edge-like weighting near-identical to
    // POS=0.95's, not the near-center weighting a strike position of 0.05
    // actually describes. With that fixed (position now anchored at the
    // formula's true center, see compoundMembrane's own POS comment), this
    // pad measures centroidHz=79.44, lowRatio=0.802 - comfortably under the
    // plain 100Hz KICK ceiling - and KICK is what the spec's own table
    // expected all along.
    @Test
    fun `djembe bass classifies as a kick`() {
        val snip = Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass)
        assertEquals(DrumClass.KICK, Classifier.classify(snip).drumClass)
    }

    // Measured before fixing this: flatness and highRatio both rounded to
    // 0.0000 at FORCE=0 AND FORCE=1 - fleshPalmExciter was a pure raised-
    // cosine pulse with no noise term at all, so a soft vs. hard hand-strike
    // was spectrally indistinguishable, unlike HARD_STICK's own hardness-
    // scaled noise (see "force injects noise into the strike" below). Now
    // measures flatness 9.6e-4 (FORCE=0) vs 1.3e-3 (FORCE=1).
    @Test
    fun `force adds grit to a hand-struck strike too`() {
        val soft = FeatureExtractor.extract(Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass + ("FORCE" to 0f)))
        val hard = FeatureExtractor.extract(Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass + ("FORCE" to 1f)))
        assertTrue(
            hard.flatness > soft.flatness,
            "FORCE should add noise/grit to a hand-struck strike too: soft=${soft.flatness} hard=${hard.flatness}",
        )
    }

    // ---------- RESONANT_CAVITY ----------

    @Test
    fun `resonant cavity render is bit-for-bit deterministic`() {
        val a = Terra.render(TerraVoice.RESONANT_CAVITY, uduLowWhoomp)
        val b = Terra.render(TerraVoice.RESONANT_CAVITY, uduLowWhoomp)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `resonant cavity render stays in bounds`() {
        val snip = Terra.render(TerraVoice.RESONANT_CAVITY, cajonLowPort)
        for (s in snip.samples) assertTrue(s in -1f..1f, "sample out of bounds: $s")
    }

    // Udu Low Whoomp, TERRA_World_Percussion_Synth_Spec.md S5 (Pad 01):
    // fundamental 55Hz, hardness 0.10, droop 0.05, CavityMix 0.90 ("deep air
    // push"). Strike position isn't given per-pad in S5, so STRIKE stays at
    // TerraParams' own default (0.25).
    private val uduLowWhoomp = mapOf(
        "TUNE" to 0.1058f,
        "FORCE" to 0.10f,
        "DROOP" to 0.0769f,
        "CAVITY" to 0.90f,
    )

    // Cajón Low Port, S5 (Pad 04): fundamental 60Hz, hardness 0.20, no droop,
    // CavityMix 0.70, RattleAmount 0.15 ("slight snare rattle") - S2.4's
    // "rattle" becomes this engine's BUZZ macro (see macrosFor's own KDoc
    // for why it isn't called RATTLE).
    private val cajonLowPort = mapOf(
        "TUNE" to 0.1516f,
        "FORCE" to 0.20f,
        "DROOP" to 0f,
        "CAVITY" to 0.70f,
        "BUZZ" to 0.15f,
    )

    @Test
    fun `udu low whoomp classifies as a kick`() {
        val snip = Terra.render(TerraVoice.RESONANT_CAVITY, uduLowWhoomp)
        assertEquals(DrumClass.KICK, Classifier.classify(snip).drumClass)
    }

    @Test
    fun `cajon low port classifies as a kick`() {
        val snip = Terra.render(TerraVoice.RESONANT_CAVITY, cajonLowPort)
        assertEquals(DrumClass.KICK, Classifier.classify(snip).drumClass)
    }

    @Test
    fun `cavity coupling is audible - CAVITY changes the spectrum`() {
        val dry = FeatureExtractor.extract(
            Terra.render(TerraVoice.RESONANT_CAVITY, uduLowWhoomp + ("CAVITY" to 0f)),
        )
        val wet = FeatureExtractor.extract(
            Terra.render(TerraVoice.RESONANT_CAVITY, uduLowWhoomp + ("CAVITY" to 1f)),
        )
        assertTrue(
            dry.centroidHz != wet.centroidHz,
            "CAVITY should change the spectrum: dry=${dry.centroidHz} wet=${wet.centroidHz}",
        )
    }

    @Test
    fun `buzz adds high-frequency energy`() {
        val dry = FeatureExtractor.extract(
            Terra.render(TerraVoice.RESONANT_CAVITY, cajonLowPort + ("BUZZ" to 0f)),
        )
        val buzzed = FeatureExtractor.extract(
            Terra.render(TerraVoice.RESONANT_CAVITY, cajonLowPort + ("BUZZ" to 1f)),
        )
        assertTrue(
            buzzed.centroidHz > dry.centroidHz,
            "BUZZ should raise the centroid: dry=${dry.centroidHz} buzzed=${buzzed.centroidHz}",
        )
    }

    // ---------- CONICAL_BELL ----------

    @Test
    fun `conical bell render is bit-for-bit deterministic`() {
        val a = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell)
        val b = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `conical bell render stays in bounds`() {
        val snip = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell)
        for (s in snip.samples) assertTrue(s in -1f..1f, "sample out of bounds: $s")
    }

    // Measured: centroidHz=653.8, lowRatio=0.0002, highRatio=0.0021,
    // flatness=0.0006 - a pure, mid-range tone, nowhere near bass-family
    // (lowRatio) or bright enough for HAT/SNARE (highRatio) or noisy enough
    // for CLAP (flatness). PERC is the classifier's own no-confident-match
    // shelf, and it's the right one here: THUMP/SKIN's own RIDE/STICK/
    // SHAKER voices land there for the identical reason (README's own
    // "no dedicated DrumClass" note) - a mid-tonal metal bell isn't kick,
    // snare, hat, tom or clap, and PERC says so honestly rather than
    // guessing.
    @Test
    fun `agogo low bell classifies as perc`() {
        val snip = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell)
        assertEquals(DrumClass.PERC, Classifier.classify(snip).drumClass)
    }

    // Checks the ordering CLACK is supposed to give (quiet click, *then* a
    // fresh strike), not just that the buffer grew - a buggy version that
    // starts the bell's own decay clock at frame 0 regardless of the
    // pre-roll (so the bell is already ringing underneath the click) would
    // also pass a plain length check. Peak *ratio* rather than an absolute
    // threshold: Terra.render normalizes the whole buffer, so only a
    // same-buffer comparison stays meaningful.
    @Test
    fun `clack is quiet, and the bell only starts ringing after it`() {
        val plain = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell + ("CLACK" to 0f))
        val clacked = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell + ("CLACK" to 1f))
        assertTrue(
            clacked.frameCount > plain.frameCount,
            "CLACK should extend the render by its own pre-roll: plain=${plain.frameCount} clacked=${clacked.frameCount}",
        )

        val clackSamples = clacked.frameCount - plain.frameCount
        val preroll = clacked.samples.copyOfRange(0, clackSamples)
        val bodyEnd = minOf(clacked.samples.size, clackSamples + 2000)
        val body = clacked.samples.copyOfRange(clackSamples, bodyEnd)

        val prerollPeak = preroll.maxOf { abs(it) }
        val bodyPeak = body.maxOf { abs(it) }

        assertTrue(prerollPeak > 0f, "the pre-roll should carry the click's own noise burst, not silence")
        assertTrue(
            prerollPeak < bodyPeak * 0.5f,
            "the bell should stay silent during the pre-roll, not already ringing underneath the click: " +
                "prerollPeak=$prerollPeak bodyPeak=$bodyPeak",
        )
    }

    // Agogô Low Bell, S5 (Pad 13): fundamental 587.3Hz (D5), hardness 0.85,
    // no droop (CONICAL_BELL has no DROOP macro at all - see macrosFor).
    private val agogoLowBell = mapOf(
        "TUNE" to 0.2508f,
        "FORCE" to 0.85f,
    )

    // Measured: the whole-buffer spectral centroid barely moves between
    // FORCE 0 and 1 (653.7984 vs 653.7983Hz) - HARD_STICK's 1.8ms strike is
    // too brief against ~0.5s of sustained modal ring to shift a whole-file
    // centroid. FORCE's actual effect (noise mixed into the strike itself)
    // has to be measured in the strike's own head, not the whole render -
    // same reasoning as SkinTest's head-windowed overtone check.
    @Test
    fun `force injects noise into the strike`() {
        val soft = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell + ("FORCE" to 0f)).samples
        val hard = Terra.render(TerraVoice.CONICAL_BELL, agogoLowBell + ("FORCE" to 1f)).samples
        val head = minOf(soft.size, hard.size, 400)
        var sumSq = 0.0
        for (i in 0 until head) {
            val d = (soft[i] - hard[i]).toDouble()
            sumSq += d * d
        }
        assertTrue(sumSq > 1e-6, "FORCE=1 should inject audible noise into the strike's head: sumSq=$sumSq")
    }

    // ---------- TUNED_BAR ----------

    @Test
    fun `tuned bar render is bit-for-bit deterministic`() {
        val a = Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd)
        val b = Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `tuned bar render stays in bounds`() {
        val snip = Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd)
        for (s in snip.samples) assertTrue(s in -1f..1f, "sample out of bounds: $s")
    }

    // Measured: centroidHz=355.9, lowRatio=0.0019, highRatio=0.400,
    // flatness=0.314 - brighter and noisier than the bell (the buzz stage
    // and the bar's huge upper-partial ratios, 6.27x/17.55x/34.39x, both
    // push this up), but still short of SNARE's 0.5 highRatio gate and
    // CLAP's 0.35 flatness gate. Same shelf, same reasoning as the bell.
    @Test
    fun `balafon key gourd classifies as perc`() {
        val snip = Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd)
        assertEquals(DrumClass.PERC, Classifier.classify(snip).drumClass)
    }

    // Balafon Key Gourd, S5 (Pad 16): fundamental 329.6Hz (E4), hardness
    // 0.70, BuzzAmount 0.60 ("spider-egg membrane buzz on wooden bar").
    private val balafonKeyGourd = mapOf(
        "TUNE" to 0.7573f,
        "FORCE" to 0.70f,
        "BUZZ" to 0.60f,
    )

    @Test
    fun `tuned bar buzz adds high-frequency energy`() {
        val dry = FeatureExtractor.extract(Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd + ("BUZZ" to 0f)))
        val buzzed = FeatureExtractor.extract(Terra.render(TerraVoice.TUNED_BAR, balafonKeyGourd + ("BUZZ" to 1f)))
        assertTrue(
            buzzed.centroidHz > dry.centroidHz,
            "BUZZ should raise the centroid: dry=${dry.centroidHz} buzzed=${buzzed.centroidHz}",
        )
    }

    // ---------- the bank's level input (spec "Testing", test 7) ----------

    /**
     * A mode held at exactly 0 must keep its phase running, so it reopens
     * where it would have been. The wrong guard (skipping on gain x level,
     * before the phase accumulates) shows as a phase error after reopening,
     * not a click: Phase 0 measured up to 0.33 of a 0.82 bank peak, with a
     * largest first difference of 0.0004 against 0.0003. So this compares
     * waveforms after the window, sample for sample, not a step.
     */
    @Test
    fun `a mode held at zero level reopens in phase`() {
        var captured: Terra.Body? = null
        Terra.bankWith(TerraVoice.COMPOUND_MEMBRANE, emptyMap()) { captured = it; null }
        val body = requireNotNull(captured)
        val a = (0.020f * body.rate).toInt()
        val b = (0.060f * body.rate).toInt()
        val k = 2
        val window = FloatArray(b + 1) { n -> if (n in a until b) 0f else 1f }
        val level = Array(body.modes.size) { m -> if (m == k) window else floatArrayOf(1f) }
        fun bank(modes: List<Modes.Mode>, level: Array<FloatArray>?) =
            Terra.strikeAndModalBank(modes, body.fundamentalHz, body.droopDepth, body.frames, body.rate, { 0f }, body.onsetSamples, level = level)
        val reference = bank(body.modes, null)
        val zeroed = bank(body.modes, level)
        val without = bank(body.modes.filterIndexed { m, _ -> m != k }, null)
        assertContentEquals(reference.copyOfRange(0, a), zeroed.copyOfRange(0, a), "a curve of ones changed the bank before the window")
        assertContentEquals(without.copyOfRange(a, b), zeroed.copyOfRange(a, b), "the zeroed mode still sounded inside the window")
        assertContentEquals(reference.copyOfRange(b, reference.size), zeroed.copyOfRange(b, zeroed.size), "the mode did not reopen in phase")
    }
}
