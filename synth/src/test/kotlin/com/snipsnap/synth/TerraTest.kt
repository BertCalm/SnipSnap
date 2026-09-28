package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.abs
import kotlin.test.Test
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
    // among them, read as KICK - measured against the real Classifier, this
    // one does not (S6.3 also cites PERC_HIGH/CHROMATIC_PERC, which do not
    // exist as DrumClass values at all). Measured: centroidHz=127.95,
    // lowRatio=0.643. lowRatio > 0.55 puts it in the bass family, but
    // neither KICK gate clears - centroid is under the stretch ceiling
    // (130Hz) but lowRatio falls well short of the 0.85 the stretch also
    // needs, and it's over the plain 100Hz ceiling outright - so it falls
    // through to TOM. The COMPOUND_MEMBRANE table's overtones (up to the
    // 5.92x partial) are real, audible spectral energy, not a low-passed
    // sub - about 41% of this hit's power sits above the fundamental - and
    // that is what a physical djembe actually sounds like: fuller and more
    // harmonic than an 808-style kick. TOM reads as the more honest class
    // for this instrument, not a bug to chase toward KICK.
    @Test
    fun `djembe bass classifies as a tom`() {
        val snip = Terra.render(TerraVoice.COMPOUND_MEMBRANE, djembeBass)
        assertEquals(DrumClass.TOM, Classifier.classify(snip).drumClass)
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
}
