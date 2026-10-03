package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ---------- HIT (spec "HIT, the design"; "Testing", tests 1, 2 and 8) ----------

    private val impulse = floatArrayOf(1f)

    /** Phase 0's G-P0d and G-P0e on the forty cases: HIT 0 with any head, and an impulse at subtle and strong, are today's TERRA bit for bit. */
    @Test
    fun `HIT 0 with any head and an impulse at subtle and strong render the frozen TERRA`() {
        val heads = listOf("tkick", "tsnare", "wraith").map { TerraStrikers.head(it) }
        var renders = 0
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros).samples
            for (h in heads) {
                assertContentEquals(frozen, Terra.renderStruck(c.voice, c.macros, h, 0f).samples, "${c.label}, HIT 0")
                renders++
            }
            for (hit in listOf(0.5f, 1f)) {
                assertContentEquals(frozen, Terra.renderStruckAt(c.voice, c.macros, impulse, hit).samples, "${c.label}, an impulse at HIT $hit")
                renders++
            }
        }
        assertEquals(200, renders)
    }

    /** Elsewhere (1 - c) + c may round once (spec); every gain an impulse gives stays within one ulp of 1. */
    @Test
    fun `an impulse leaves every gain within one ulp of 1 at any strength`() {
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            for (c in listOf(0.1f, 0.25f, 0.3f, 0.7f, 0.9f)) {
                val gains = assertNotNull(Terra.hitLevel(body, impulse, c), "$voice c=$c")
                for (curve in gains) for (g in curve) assertTrue(abs(g - 1f) <= Math.ulp(1f), "$voice c=$c: gain $g")
            }
        }
    }

    /** A coloured body with no peak gives a non-finite `s`; the striker renders as absent (spec, "Failure handling"). */
    @Test
    fun `a head with nothing in it renders today's body at HIT 1`() {
        for (voice in TerraVoice.entries) {
            val plain = Terra.render(voice).samples
            val nothing = FloatArray(Fork.STRIKER_SAMPLES)
            assertNull(Terra.hitLevel(TerraMeasure.bodyOf(voice), Terra.upsample(nothing), 1f), "$voice: a level match from nothing")
            assertContentEquals(plain, Terra.renderStruck(voice, emptyMap(), nothing, 1f).samples, "$voice: an empty head changed the drum")
        }
    }

    // ---------- HIT's floor (R1b; spec "HIT, the design", decision 22) ----------

    /** The default is the owner's choice from R1b's page (-12 dB, 2026-10-02), among the three choices the page offered (none, -18 and -12 dB; -6 dB was out, see TerraAuditionGenerator.R1B_FLOOR_CHOICES); a floor outside 0..1 is refused. */
    @Test
    fun `HIT's floor defaults to a quarter of today's level, the owner's choice among R1b's three`() {
        assertEquals(0.25f, Terra.HIT_FLOOR, "the default is -12 dB, the owner's choice from R1b's page")
        assertEquals(listOf(0f, 0.125f, 0.25f), TerraAuditionGenerator.R1B_FLOOR_CHOICES)
        assertTrue(Terra.HIT_FLOOR in TerraAuditionGenerator.R1B_FLOOR_CHOICES)
        val body = TerraMeasure.bodyOf(TerraVoice.TUNED_BAR)
        for (bad in listOf(-0.01f, 1.01f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException>("floor $bad") { Terra.hitLevel(body, impulse, 1f, hitFloor = bad) }
        }
    }

    /**
     * The controller's ruling for R1b: phi = 0 must reproduce R1's HIT bit for
     * bit. Compared with LegacyTerraHit, R1's colouring frozen at 58757f10,
     * not with the floored code itself: every level curve on the four voices,
     * the ten strikers and four strengths, then the forty cases rendered at
     * HIT 1 with a dark and a bright head.
     */
    @Test
    fun `a floor of 0 is R1's HIT bit for bit`() {
        var curves = 0
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            for (s in TerraStrikers.TEN) {
                val x = Terra.upsample(TerraStrikers.head(s.id))
                for (c in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                    val r1 = assertNotNull(LegacyTerraHit.level(body, x, c), "$voice ${s.id} c=$c: R1's colouring")
                    val now = assertNotNull(Terra.hitLevel(body, x, c, hitFloor = 0f), "$voice ${s.id} c=$c: the floored colouring at 0")
                    assertEquals(r1.size, now.size, "$voice ${s.id} c=$c: mode count")
                    for (k in r1.indices) assertContentEquals(r1[k], now[k], "$voice ${s.id} c=$c mode ${k + 1}")
                    curves++
                }
            }
        }
        assertEquals(160, curves)
        var renders = 0
        for (id in listOf("kick01", "tsnare")) {
            val x = Terra.upsample(TerraStrikers.head(id))
            for (c in TerraCases.all) {
                val r1 = Terra.renderWith(c.voice, c.macros) { body -> LegacyTerraHit.level(body, x, 1f)?.let { Terra.BankInputs(level = it) } }
                assertContentEquals(r1.samples, Terra.renderStruckAt(c.voice, c.macros, x, 1f, hitFloor = 0f).samples, "${c.label}, $id at HIT 1")
                renders++
            }
        }
        assertEquals(80, renders)
    }

    /**
     * The floor's promise, on the voice a dark head thins most (R1's page,
     * question 2): at HIT 1, struck by the factory kick (A01_Kick_01.wav), no
     * bar mode rings below phi x today's level, for every choice R1b's page
     * offers.
     *
     * Three readings:
     * - exactly, on the level curves: every floored value equals the floor-0
     *   value wherever that value is at least phi, and equals phi exactly
     *   elsewhere. That pins `s` taken from the unfloored |P_k| and the floor
     *   applied after it.
     * - on the bank: each mode's amplitude against today's (TerraMeasure.
     *   modeLevels, from 20 ms) is at least phi less 0.5 dB, Phase 0's
     *   materiality rule;
     * - the same reading matches the held gain within 0.5 dB, so the measure
     *   reads what the floor did.
     *
     * At phi = 0 at least one bar mode's held gain must fall more than
     * 0.5 dB below the smallest non-zero choice (0.125 x 10^(-0.5/20),
     * about 0.118), the same 0.5 dB gap the page generator checks between
     * floors. Otherwise at least two of the floor clips on R1b's page
     * hold the same tone after 20 ms, and the plan stops. Measured by plan
     * review: the held gains are 1.354 / 0.010 / 0.005 / 0.001.
     */
    @Test
    fun `at full HIT no bar mode rings below the floor, struck by the factory kick`() {
        val voice = TerraVoice.TUNED_BAR
        val body = TerraMeasure.bodyOf(voice)
        val x = Terra.upsample(TerraStrikers.head("kick01"))
        val from = TerraMeasure.MODE_FROM_FRAMES
        val window = TerraMeasure.MODE_WINDOW
        val today = TerraMeasure.modeLevels(Terra.bankWith(voice, emptyMap(), null), body, from, window)
        for ((k, level) in today.withIndex()) assertTrue(level > 0.0, "bar mode ${k + 1} is silent today")
        val unfloored = assertNotNull(Terra.hitLevel(body, x, 1f, hitFloor = 0f))
        println("TERRA HIT floor: the factory kick at HIT 1 with no floor holds the bar's modes at ${unfloored.joinToString(" / ") { "%.3f".format(it.last()) }} of today's")
        val tolerance = Math.pow(10.0, -0.5 / 20.0)
        val lowest = unfloored.minOf { it.last() }
        val smallest = TerraAuditionGenerator.R1B_FLOOR_CHOICES.filter { it > 0f }.min()
        assertTrue(lowest < smallest * tolerance, "no bar mode holds 0.5 dB below $smallest of today's under the factory kick at HIT 1 (lowest $lowest), so some of R1b's floor clips would hold one tone")
        for (phi in TerraAuditionGenerator.R1B_FLOOR_CHOICES) {
            val gains = assertNotNull(Terra.hitLevel(body, x, 1f, hitFloor = phi), "floor $phi")
            for (k in gains.indices) {
                for (n in gains[k].indices) {
                    val u = unfloored[k][n]
                    if (u >= phi) {
                        assertEquals(u, gains[k][n], "floor $phi mode ${k + 1} at $n: not R1's gain where the floor does not bite")
                    } else {
                        assertEquals(phi, gains[k][n], "floor $phi mode ${k + 1} at $n: not the floor where it bites")
                    }
                }
            }
            val struck = TerraMeasure.modeLevels(Terra.bankStruckAt(voice, emptyMap(), x, 1f, hitFloor = phi), body, from, window)
            val ratios = DoubleArray(today.size) { k -> struck[k] / today[k] }
            println(
                "TERRA HIT floor $phi: bar mode levels against today's " +
                    ratios.joinToString(" / ") { "%.3f (%+.1f dB)".format(it, 20 * log10(maxOf(it, 1e-9))) } +
                    ", held gains " + gains.joinToString(" / ") { "%.3f".format(it.last()) },
            )
            for (k in ratios.indices) {
                val held = gains[k].last().toDouble()
                assertTrue(ratios[k] >= phi * tolerance, "floor $phi: bar mode ${k + 1} rings at ${ratios[k]} of today's, under the floor")
                assertTrue(abs(ratios[k] - held) <= 0.06 * maxOf(held, 0.01), "floor $phi: bar mode ${k + 1} reads ${ratios[k]} on the bank against a held gain of $held")
            }
        }
    }

    /** `c = 0` is the null path at every floor (the controller's ruling), and an impulse (`s · |P| = 1`) is untouched even under the highest choice (0.25). */
    @Test
    fun `HIT 0 at every floor, and an impulse under the highest floor, render the frozen TERRA`() {
        val head = TerraStrikers.head("kick01")
        val highest = TerraAuditionGenerator.R1B_FLOOR_CHOICES.max()
        var renders = 0
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros).samples
            for (phi in TerraAuditionGenerator.R1B_FLOOR_CHOICES) {
                assertContentEquals(frozen, Terra.renderStruck(c.voice, c.macros, head, 0f, hitFloor = phi).samples, "${c.label}, HIT 0 at floor $phi")
                renders++
            }
            assertContentEquals(frozen, Terra.renderStruckAt(c.voice, c.macros, impulse, 1f, hitFloor = highest).samples, "${c.label}, an impulse at HIT 1 under floor $highest")
            renders++
        }
        assertEquals(160, renders)
    }

    /**
     * Recipe provenance, read before any claim below is blamed on HIT. The
     * six synthesised strikers' macro values are not in the tree (see
     * TerraStrikers), so this checks that the ten are still the ten Phase 0
     * measured. It uses Phase 0's T2 (record, Appendix A): OB against
     * unstruck, at defaults, over the ten strikers, as mean / min / max per
     * voice. Every one of the ten moves the mean, so a drifted recipe or a
     * changed factory WAV shows here even when the three named strikers'
     * rows in the next test still pass.
     *
     * Asserted at HIT 0.5 within 0.5 dB, Phase 0's materiality rule: T2 may
     * come from struck-shape's float path, which differs from the
     * prototype's by up to 6e-4 per sample on the cavity (G-P0h). At HIT 1
     * the extremes are printed only, because several sit within about 3 dB
     * of T3's OB floor (cavity -14.61, bar -33.78), where a reading is
     * distortion products, not mode energy.
     *
     * Each striker's source format, peak and onset are printed first, so a
     * failure names which source changed.
     *
     * Read at a floor of 0 (`hitFloor = 0f`): this pins the algorithm Phase 0
     * measured, which R1b's floor leaves bit for bit at 0 (`a floor of 0 is
     * R1's HIT bit for bit`). The floor default's own claims are the coupling
     * and monotone tests below, which read the default.
     */
    @Test
    fun `the ten strikers reproduce Phase 0's overtone spread at subtle - recipe provenance`() {
        class Spread(val voice: TerraVoice, val at05: DoubleArray, val at1: DoubleArray)
        val t2 = listOf(
            Spread(TerraVoice.COMPOUND_MEMBRANE, doubleArrayOf(-0.33, -8.08, 3.48), doubleArrayOf(-0.67, -15.70, 6.87)),
            Spread(TerraVoice.RESONANT_CAVITY, doubleArrayOf(2.99, -7.00, 12.13), doubleArrayOf(6.33, -14.61, 24.49)),
            Spread(TerraVoice.CONICAL_BELL, doubleArrayOf(-1.12, -5.66, 3.31), doubleArrayOf(-2.86, -15.04, 8.41)),
            Spread(TerraVoice.TUNED_BAR, doubleArrayOf(-1.61, -7.34, 6.74), doubleArrayOf(-9.45, -33.78, 27.10)),
        )
        for (s in TerraStrikers.TEN) {
            val src = s.snip
            println(
                "TERRA striker ${s.id} ${s.name}: ${src.channels} ch, ${src.sampleRate} Hz, ${src.frameCount} frames, " +
                    "peak ${"%.4f".format(src.peak())}, onset at sample ${TerraMeasure.onsetOf(src.samples)}",
            )
        }
        for (row in t2) {
            val nominal = TerraMeasure.bodyOf(row.voice).fundamentalHz
            val today = TerraMeasure.ob(Terra.render(row.voice), nominal).toDouble()
            for ((hit, phase0) in listOf(0.5f to row.at05, 1f to row.at1)) {
                val moves = TerraStrikers.TEN.map { s ->
                    TerraMeasure.ob(Terra.renderStruck(row.voice, emptyMap(), TerraStrikers.head(s.id), hit, hitFloor = 0f), nominal).toDouble() - today
                }
                val got = doubleArrayOf(moves.average(), moves.min(), moves.max())
                println("TERRA HIT T2 ${row.voice} HIT $hit per striker: " + TerraStrikers.TEN.zip(moves).joinToString { (s, m) -> "${s.id} ${"%.2f".format(m)}" })
                println(
                    "TERRA HIT T2 ${row.voice} HIT $hit: mean / min / max ${got.joinToString(" / ") { "%.2f".format(it) }} dB " +
                        "(Phase 0 ${phase0.joinToString(" / ") { "%.2f".format(it) }})",
                )
                if (hit == 0.5f) {
                    for ((i, what) in listOf("mean", "min", "max").withIndex()) {
                        assertEquals(phase0[i], got[i], 0.5, "${row.voice} HIT 0.5: the ten strikers' $what OB move is not Phase 0's")
                    }
                }
            }
        }
    }

    /**
     * The build is the algorithm Phase 0 measured, read at a floor of 0
     * (`hitFloor = 0f`, R1's HIT bit for bit). OB is read from 20 ms
     * on the float render, at defaults; the figures are spec "Testing" test
     * 2's and the Phase-0 record's T3 and Appendix B. 0.5 dB is Phase 0's
     * own materiality rule. A slip in the running projection, the level
     * match's two windows or the hold-last lookup moves these by whole dB.
     * A reading more than 0.05 dB off but inside 0.5 is printed, and should
     * be explained before Task 5 starts.
     */
    @Test
    fun `HIT reproduces the overtone balance Phase 0 measured`() {
        class Row(val voice: TerraVoice, val striker: String?, val hit: Float, val ob: Double)
        val rows = listOf(
            Row(TerraVoice.COMPOUND_MEMBRANE, null, 0f, -18.12),
            Row(TerraVoice.RESONANT_CAVITY, null, 0f, -37.83),
            Row(TerraVoice.CONICAL_BELL, null, 0f, -46.10),
            Row(TerraVoice.TUNED_BAR, null, 0f, -41.42),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tkick", 0.5f, -21.07),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tsnare", 0.5f, -15.23),
            Row(TerraVoice.COMPOUND_MEMBRANE, "wraith", 0.5f, -14.64),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tkick", 1f, -25.23),
            Row(TerraVoice.COMPOUND_MEMBRANE, "wraith", 1f, -11.25),
            Row(TerraVoice.RESONANT_CAVITY, "tkick", 0.5f, -44.84),
            Row(TerraVoice.RESONANT_CAVITY, "wraith", 0.5f, -29.67),
            Row(TerraVoice.CONICAL_BELL, "tkick", 0.5f, -47.78),
            Row(TerraVoice.CONICAL_BELL, "tsnare", 0.5f, -43.91),
            Row(TerraVoice.TUNED_BAR, "tkick", 0.5f, -47.82),
            Row(TerraVoice.TUNED_BAR, "wraith", 0.5f, -43.28),
        )
        for (r in rows) {
            val nominal = TerraMeasure.bodyOf(r.voice).fundamentalHz
            val snip = if (r.striker == null) Terra.render(r.voice) else Terra.renderStruck(r.voice, emptyMap(), TerraStrikers.head(r.striker), r.hit, hitFloor = 0f)
            val ob = TerraMeasure.ob(snip, nominal).toDouble()
            println("TERRA HIT OB ${r.voice} ${r.striker ?: "unstruck"} HIT ${r.hit}: ${"%.2f".format(ob)} dB (Phase 0 ${"%.2f".format(r.ob)})")
            assertEquals(r.ob, ob, 0.5, "${r.voice} ${r.striker} HIT ${r.hit}")
        }
    }

    /**
     * Coupling, not layering (spec "Testing", test 2), measured as a band
     * ratio and never the centroid, which barely moves (struck-r1). Phase 0,
     * HIT 0.5:
     * - membrane: THUMP KICK -21.07 against THUMP SNARE -15.23 dB;
     * - cavity: THUMP KICK -44.84 against WRAITH WORD -29.67 dB;
     * - bell: THUMP KICK -47.78 against THUMP SNARE -43.91 dB;
     * - bar: THUMP KICK -47.82 against WRAITH WORD -43.28 dB.
     *
     * 3 dB is the spec's proposed bar. The fundamental moves 0.00 cents on
     * membrane, bell and bar. The cavity's final render reads up to 2.11
     * cents, because its fixed 75 Hz stage and a one-peak estimator move the
     * reading while the bank is exact; so the cavity's claim is asserted on
     * the bank, and the final reading is printed.
     */
    @Test
    fun `HIT couples - a dull and a bright head move the overtone balance after 20 ms, not the tuning or the length`() {
        val pairs = listOf(
            Triple(TerraVoice.COMPOUND_MEMBRANE, "tkick", "tsnare"),
            Triple(TerraVoice.RESONANT_CAVITY, "tkick", "wraith"),
            Triple(TerraVoice.CONICAL_BELL, "tkick", "tsnare"),
            Triple(TerraVoice.TUNED_BAR, "tkick", "wraith"),
        )
        for ((voice, dull, bright) in pairs) {
            val body = TerraMeasure.bodyOf(voice)
            val today = Terra.render(voice)
            val struck = listOf(dull, bright).associateWith { Terra.renderStruck(voice, emptyMap(), TerraStrikers.head(it), 0.5f) }
            val obDull = TerraMeasure.ob(struck.getValue(dull), body.fundamentalHz)
            val obBright = TerraMeasure.ob(struck.getValue(bright), body.fundamentalHz)
            println("TERRA HIT coupling $voice: $dull ${"%.2f".format(obDull)} dB, $bright ${"%.2f".format(obBright)} dB, ${"%.2f".format(obBright - obDull)} apart")
            assertTrue(abs(obBright - obDull) >= 3.0, "$voice: the two heads moved the overtone balance only ${obBright - obDull} dB apart")
            for ((id, s) in struck) assertEquals(today.frameCount, s.frameCount, "$voice $id changed the length")
            val f0Today = TerraMeasure.f0(today.samples, today.sampleRate, body.fundamentalHz)
            if (voice != TerraVoice.RESONANT_CAVITY) {
                for ((id, s) in struck) {
                    val cents = TerraMeasure.cents(TerraMeasure.f0(s.samples, s.sampleRate, body.fundamentalHz), f0Today)
                    println("TERRA HIT tuning $voice $id: ${"%.2f".format(cents)} cents")
                    assertTrue(abs(cents) <= 2.0, "$voice $id moved the fundamental $cents cents")
                }
            } else {
                val bankToday = Terra.bankWith(voice, emptyMap(), null)
                val f0BankToday = TerraMeasure.f0(bankToday, body.rate, body.fundamentalHz)
                for ((id, s) in struck) {
                    val bank = Terra.bankStruckAt(voice, emptyMap(), Terra.upsample(TerraStrikers.head(id)), 0.5f)
                    val bankCents = TerraMeasure.cents(TerraMeasure.f0(bank, body.rate, body.fundamentalHz), f0BankToday)
                    val finalCents = TerraMeasure.cents(TerraMeasure.f0(s.samples, s.sampleRate, body.fundamentalHz), f0Today)
                    println("TERRA HIT tuning $voice $id: bank ${"%.2f".format(bankCents)} cents, final render ${"%.2f".format(finalCents)} cents (Phase 0: up to 2.11 at HIT 0.5)")
                    assertTrue(abs(bankCents) <= 2.0, "$voice $id moved the bank's fundamental $bankCents cents")
                }
            }
        }
    }

    /**
     * Spec "Testing", test 8, over the ten strikers and c in {0, .25, .5,
     * .75, 1}:
     * - Every striker's OB moves monotonically on every voice (Phase 0: 10 of
     *   10), at the 0.01 dB step tolerance, except a curve that moves under
     *   0.5 dB across the whole knob: |OB(1) - OB(0)| and the max-min span
     *   both under 0.5 dB, Phase 0's materiality rule. Such a curve has no
     *   audible effect to be monotone in, and is printed with the word
     *   "exempt" and its span. Every curve that moves 0.5 dB or more is
     *   checked at 0.01 dB. The controller's ruling (2026-10-02), from this
     *   measurement at the default floor: RESONANT_CAVITY struck by BEATBOX
     *   RIM at phi 0.25 reads OB -37.825 / -37.797 / -37.813 / -37.772 /
     *   -37.670 dB at HIT 0 / .25 / .5 / .75 / 1, a span of 0.155 dB with a
     *   0.016 dB step backwards; at phi 0.125 its span is 0.41 dB. The test
     *   stays at the default floor (not phi = 0), and the step tolerance for
     *   moving curves stays at 0.01 dB.
     * - At 0.5 the drum class never changes, and the first-5-ms peak is
     *   today's. Phase 0 read 1.000 on every voice; 0.005 is that figure's
     *   rounding.
     * - At 1 the class changes and the attack are printed beside Phase 0's
     *   (record, Appendix A, T11/T12): 5 of 10 membrane renders TOM to PERC;
     *   the first-5-ms peak's mean over the ten strikers, as a ratio and in
     *   dB, is 0.815 / -2.22 dB on the membrane, 0.832 / -1.85 dB on the
     *   cavity, 0.970 on the bell and 1.000 on the bar.
     *
     * Any future velocity registration of HIT needs this sweep first.
     */
    @Test
    fun `HIT is monotone in its amount and keeps today's attack and class at subtle`() {
        val strengths = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val phase0AtStrong = mapOf(
            TerraVoice.COMPOUND_MEMBRANE to "0.815 / -2.22 dB",
            TerraVoice.RESONANT_CAVITY to "0.832 / -1.85 dB",
            TerraVoice.CONICAL_BELL to "0.970",
            TerraVoice.TUNED_BAR to "1.000",
        )
        var flipsAtStrong = 0
        var renders = 0
        var exempt = 0
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            val today = Terra.render(voice)
            val todayClass = Classifier.classify(today).drumClass
            val todayPk5 = TerraMeasure.firstFiveMsPeak(today)
            var strongRatioSum = 0.0
            var strongDbSum = 0.0
            var strongChangeDbSum = 0.0
            for (s in TerraStrikers.TEN) {
                val head = TerraStrikers.head(s.id)
                val sweep = strengths.map { c -> if (c == 0f) today else Terra.renderStruck(voice, emptyMap(), head, c) }
                renders += 4
                val obs = sweep.map { TerraMeasure.ob(it, body.fundamentalHz).toDouble() }
                val steps = obs.zipWithNext { x, y -> y - x }
                println("TERRA HIT sweep $voice ${s.name}: OB ${obs.joinToString(" / ") { "%.2f".format(it) }}")
                val span = obs.max() - obs.min()
                val travel = abs(obs.last() - obs.first())
                if (travel < 0.5 && span < 0.5) {
                    println("TERRA HIT sweep $voice ${s.name}: exempt from the step check, OB span ${"%.3f".format(span)} dB (|OB(1) - OB(0)| ${"%.3f".format(travel)} dB), under 0.5 dB")
                    exempt++
                } else {
                    assertTrue(steps.all { it >= -0.01 } || steps.all { it <= 0.01 }, "$voice ${s.name}: OB is not monotone in HIT: $obs")
                }
                val subtle = sweep[2]
                assertEquals(todayClass, Classifier.classify(subtle).drumClass, "$voice ${s.name}: HIT 0.5 changed the drum class")
                val pk5 = TerraMeasure.firstFiveMsPeak(subtle)
                assertTrue(abs(pk5 - todayPk5) <= 0.005f, "$voice ${s.name}: HIT 0.5 moved the first-5-ms peak to $pk5 from $todayPk5")
                val strong = sweep[4]
                val strongClass = Classifier.classify(strong).drumClass
                if (strongClass != todayClass) flipsAtStrong++
                val strongPk5 = TerraMeasure.firstFiveMsPeak(strong)
                strongRatioSum += strongPk5
                strongDbSum += 20.0 * log10(maxOf(strongPk5, 1e-6f).toDouble())
                strongChangeDbSum += 20.0 * log10(maxOf(strongPk5, 1e-6f).toDouble() / maxOf(todayPk5, 1e-6f))
                println("TERRA HIT 1 $voice ${s.name}: class $strongClass (today $todayClass), first-5-ms peak ${"%.3f".format(strongPk5)}")
            }
            val n = TerraStrikers.TEN.size
            println(
                "TERRA HIT 1 $voice attack: first-5-ms peak mean ${"%.3f".format(strongRatioSum / n)} / ${"%.2f".format(strongDbSum / n)} dB, " +
                    "${"%.2f".format(strongChangeDbSum / n)} dB from today's ${"%.3f".format(todayPk5)} (Phase 0: ${phase0AtStrong.getValue(voice)})",
            )
        }
        println("TERRA HIT 1: $flipsAtStrong class changes in 40 renders (Phase 0: 5, all membrane TOM to PERC)")
        println("TERRA HIT sweep: $exempt of 40 curves exempt from the step check (total OB move under 0.5 dB)")
        assertEquals(160, renders)
    }

    /** The pre-roll keeps under the 50 % ceiling `clack is quiet, and the bell only starts ringing after it` holds it to (Phase 0: 5.9 % today, 16.5 % at HIT 1 with THUMP KICK). */
    @Test
    fun `the CLACK pre-roll stays quiet under HIT`() {
        val plainLength = Terra.render(TerraVoice.CONICAL_BELL, mapOf("CLACK" to 0f)).frameCount
        for (id in listOf("tkick", "tsnare")) {
            for (hit in listOf(0.5f, 1f)) {
                val out = Terra.renderStruck(TerraVoice.CONICAL_BELL, mapOf("CLACK" to 1f), TerraStrikers.head(id), hit).samples
                val clack = out.size - plainLength
                assertTrue(clack > 0, "CLACK 1 should lengthen the render")
                val preroll = out.copyOfRange(0, clack).maxOf { abs(it) }
                val body = out.copyOfRange(clack, minOf(out.size, clack + 2000)).maxOf { abs(it) }
                println("TERRA HIT CLACK $id HIT $hit: pre-roll ${"%.1f".format(100 * preroll / body)} % of the body")
                assertTrue(preroll < body * 0.5f, "$id HIT $hit: the pre-roll is ${preroll / body} of the body")
            }
        }
    }

    // ---------- robustness, and the drive HIT hands the cavity and BUZZ (spec "Testing", tests 3 and 6) ----------

    /** All ten strikers in the printed BUZZ and drive table when TERRA_FULL=1; the three named ones otherwise (CI minutes are metered). The cost test does not read it. */
    private val full = System.getenv("TERRA_FULL") == "1"

    @Test
    fun `every hostile source renders a real hit or today's body, on every voice`() {
        var renders = 0
        for (h in TerraStrikers.hostile()) {
            val head = Terra.captureStriker(h.snip)
            for (voice in TerraVoice.entries) {
                val plain = Terra.render(voice)
                val out = TerraPatch("Hostile", voice, emptyMap(), head?.let { TerraPatch.Striker(it, 1f) }).render()
                assertEquals(plain.frameCount, out.frameCount, "${h.label} on $voice changed the length")
                assertTrue(out.samples.all { it.isFinite() && abs(it) <= 1f }, "${h.label} on $voice: a sample is not finite or is over full scale")
                assertTrue(out.peak() > 0f, "${h.label} on $voice rendered silence")
                if (head == null) assertContentEquals(plain.samples, out.samples, "${h.label} on $voice: no striker, yet not today's body")
                renders++
            }
        }
        println("TERRA hostile renders: $renders at HIT 1")
    }

    /** One random striker source: a THUMP voice at scrambled macros, noise and sine bursts at any level after any lead silence, a single sample, NaN- and Inf-laced noise, or silence and DC. */
    private fun randomSource(r: Random, kind: Int): Snip {
        val rate = Dsp.RATE
        fun level() = Math.pow(10.0, -6.0 + 6.0 * r.nextDouble()).toFloat()
        fun lead() = FloatArray(r.nextInt(0, rate / 5))
        return when (kind) {
            0 -> {
                val v = ThumpVoice.entries[r.nextInt(ThumpVoice.entries.size)]
                Thump.render(v, Thump.scramble(v, r))
            }
            1 -> {
                val a = level()
                Snip(lead() + FloatArray(r.nextInt(rate / 200, rate / 3)) { (r.nextFloat() * 2f - 1f) * a }, 1, rate)
            }
            2 -> {
                val a = level()
                val hz = 40.0 + 7960.0 * r.nextDouble()
                Snip(lead() + FloatArray(r.nextInt(rate / 100, rate / 2)) { i -> (a * sin(2.0 * PI * hz * i / rate)).toFloat() }, 1, rate)
            }
            3 -> Snip(FloatArray(10_000).also { it[r.nextInt(10_000)] = level() }, 1, rate)
            4 -> {
                val a = level()
                Snip(FloatArray(rate / 5) { if (r.nextInt(50) == 0) Float.NaN else if (r.nextInt(200) == 0) Float.POSITIVE_INFINITY else (r.nextFloat() * 2f - 1f) * a }, 1, rate)
            }
            else -> when (r.nextInt(3)) {
                0 -> Snip(FloatArray(0), 1, rate)
                1 -> Snip(FloatArray(rate / 10), 1, rate)
                else -> Snip(FloatArray(rate / 10) { 0.4f }, 1, rate)
            }
        }
    }

    /**
     * Struck-motion M7.2's sweep: 200 random strikers on random TERRA pads at
     * random HIT, through the patch the phone would save. The capture rule's
     * promise: no render is non-finite, silent, off-length or over full scale
     * (M7.2: 0 / 0 / 0 with the rule, against 14 silent for the raw capture).
     * Fallbacks - a source with no hit to take - are counted and printed.
     */
    @Test
    fun `200 random strikers on random TERRA pads - never non-finite, silent, off-length or over full scale`() {
        var nonFinite = 0
        var silent = 0
        var offLength = 0
        var over = 0
        var fallbacks = 0
        for (i in 0 until 200) {
            val r = Random(9_000 + i)
            val voice = TerraVoice.entries[r.nextInt(TerraVoice.entries.size)]
            val macros = Terra.macrosFor(voice).associate { it.name to r.nextFloat() }
            val head = Terra.captureStriker(randomSource(r, i % 6))
            val hit = r.nextFloat()
            if (head == null) fallbacks++
            val plain = Terra.render(voice, macros)
            val out = TerraPatch("Sweep", voice, macros, head?.let { TerraPatch.Striker(it, hit) }).render()
            if (!out.samples.all { it.isFinite() }) nonFinite++
            if (out.peak() <= 0f) silent++
            if (out.frameCount != plain.frameCount) offLength++
            if (out.samples.any { abs(it) > 1f }) over++
            if (head == null) assertContentEquals(plain.samples, out.samples, "case $i: no striker, yet not today's body")
        }
        println("TERRA sweep: 200 cases, $nonFinite non-finite, $silent silent, $offLength off-length, $over over full scale, $fallbacks fell back to today's body (M7.2 had 15 on its own random set)")
        assertEquals(0, nonFinite, "non-finite renders")
        assertEquals(0, silent, "silent renders")
        assertEquals(0, offLength, "renders whose length moved")
        assertEquals(0, over, "renders over full scale")
    }

    /** Phase 0's G-P0f: HIT 1 with THUMP KICK on the forty cases keeps TERRA's length, finite and within full scale. */
    @Test
    fun `HIT 1 with a kick head keeps all forty cases finite, in range and the same length`() {
        val head = TerraStrikers.head("tkick")
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros)
            val out = Terra.renderStruck(c.voice, c.macros, head, 1f)
            assertEquals(frozen.frameCount, out.frameCount, c.label)
            assertTrue(out.samples.all { it.isFinite() && abs(it) <= 1f }, c.label)
        }
    }

    /**
     * Spec "Testing", test 6, pinned to decision 2 as R1's page took it on
     * 2026-10-01 (question 3, "Follow the hit"): BUZZ follows the striker. The
     * level match `s` matches the body's peak, not the 75 Hz band-passed level
     * the cavity's tanh sees nor the 0.12 threshold BUZZ gates on. So a dark
     * head keeps the rattle about as long as today's, and a bright head
     * shortens it.
     *
     * Each figure is pinned within ±20 % of its measurement (the controller's
     * ruling for R1b, which replaces the spec's ±0.05 ratio bands), in two
     * tables:
     * - at a floor of 0, R1's HIT, against R1's printed figures (commit
     *   19ff3762), which equal Phase 0's to the printed digit;
     * - at the floor default ([Terra.HIT_FLOOR], 0.25, the owner's choice), against
     *   the figures measured with the floor in place (R1b's plan review,
     *   confirmed by its Task 2 Step 1).
     *
     * Measured at the default, against R1's floor-0 figures (cavity ms / bar
     * ms / tanh input). Each row reads "floored, against R1's":
     * - THUMP KICK HIT 0.5: 63.9 / 55.2 / 0.3277, against 63.7 / 55.2 / 0.3260;
     * - THUMP KICK HIT 1: 69.8 / 55.3 / 0.3598, against 69.3 / 54.2 / 0.3571;
     * - THUMP SNARE HIT 0.5: 40.1 / 47.8 / 0.2471, against 40.0 / 47.8 / 0.2468;
     * - THUMP SNARE HIT 1: 24.8 / 41.5 / 0.2053, against 24.7 / 41.3 / 0.2049;
     * - WRAITH WORD HIT 0.5: 35.4 / 35.8 / 0.2413, against 32.2 / 35.7 / 0.2381;
     * - WRAITH WORD HIT 1: 16.6 / 13.7 / 0.1936, against 14.2 / 13.3 / 0.1867.
     *
     * THUMP KICK at HIT 1 holds the tanh input 0.0002 under the cap at the
     * default (0.3598); at a floor of 0.5 it reads 0.3661, past it, which is
     * why 0.5 is not one of [TerraAuditionGenerator.R1B_FLOOR_CHOICES].
     *
     * Two claims sit beside the bands and are never re-thresholded:
     * - the cavity's tanh input stays at or under 0.36 (within 4 % of linear,
     *   the spec's cap) in both tables;
     * - at HIT 1, at the default, THUMP KICK keeps BUZZ above 0.12 longer
     *   than WRAITH WORD, on the cavity and on the bar. That is "follows the
     *   striker": a band-passed level match would hold the two near each
     *   other and near today's.
     *
     * The other seven strikers are printed, not pinned, when TERRA_FULL=1.
     * When R1b's page picks a floor other than 0.25, the floored table is
     * measured again at the new value by the same step.
     */
    @Test
    fun `BUZZ follows the striker - the cavity's and the bar's drive under HIT, pinned`() {
        class Drive(val id: String, val hit: Float, val cavityMs: Double, val barMs: Double, val tanhIn: Double)
        val r1 = listOf(
            Drive("tkick", 0.5f, 63.7, 55.2, 0.3260),
            Drive("tkick", 1f, 69.3, 54.2, 0.3571),
            Drive("tsnare", 0.5f, 40.0, 47.8, 0.2468),
            Drive("tsnare", 1f, 24.7, 41.3, 0.2049),
            Drive("wraith", 0.5f, 32.2, 35.7, 0.2381),
            Drive("wraith", 1f, 14.2, 13.3, 0.1867),
        )
        // Measured at Terra.HIT_FLOOR = 0.25 (R1b's plan review; confirmed by R1b Task 2 Step 1).
        val floored = listOf(
            Drive("tkick", 0.5f, 63.9, 55.2, 0.3277),
            Drive("tkick", 1f, 69.8, 55.3, 0.3598),
            Drive("tsnare", 0.5f, 40.1, 47.8, 0.2471),
            Drive("tsnare", 1f, 24.8, 41.5, 0.2053),
            Drive("wraith", 0.5f, 35.4, 35.8, 0.2413),
            Drive("wraith", 1f, 16.6, 13.7, 0.1936),
        )
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val buzz = mapOf("BUZZ" to 1f)
        val mix = Terra.defaults(TerraVoice.RESONANT_CAVITY).getValue("CAVITY")
        fun near(expected: Double, got: Double, what: String) =
            assertTrue(abs(got - expected) <= 0.2 * abs(expected), "$what: $got, pinned at $expected ± 20 %")
        fun measure(id: String, hit: Float, floor: Float): DoubleArray {
            val x = Terra.upsample(TerraStrikers.head(id))
            val cavity = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, buzz, x, hit, floor), mix, rate), 0.12f, rate)
            val bar = TerraMeasure.msAbove(Terra.bankStruckAt(TerraVoice.TUNED_BAR, buzz, x, hit, floor), 0.12f, rate)
            val tanhIn = TerraMeasure.tanhInput(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, emptyMap(), x, hit, floor), rate).toDouble()
            return doubleArrayOf(cavity, bar, tanhIn)
        }

        val cavityToday = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankWith(TerraVoice.RESONANT_CAVITY, buzz, null), mix, rate), 0.12f, rate)
        val barToday = TerraMeasure.msAbove(Terra.bankWith(TerraVoice.TUNED_BAR, buzz, null), 0.12f, rate)
        val tanhToday = TerraMeasure.tanhInput(Terra.bankWith(TerraVoice.RESONANT_CAVITY, emptyMap(), null), rate).toDouble()
        println("TERRA drive today: cavity ${"%.1f".format(cavityToday)} ms and bar ${"%.1f".format(barToday)} ms above 0.12 at BUZZ 1, cavity tanh input ${"%.4f".format(tanhToday)} (R1 and Phase 0: 55.6, 53.2, 0.3075)")
        near(55.6, cavityToday, "today: cavity ms above 0.12")
        near(53.2, barToday, "today: bar ms above 0.12")
        near(0.3075, tanhToday, "today: cavity tanh input")

        val got = mutableMapOf<String, DoubleArray>()
        for ((floor, table) in listOf(0f to r1, Terra.HIT_FLOOR to floored)) {
            for (row in table) {
                val (cavity, bar, tanhIn) = measure(row.id, row.hit, floor).also { got["$floor ${row.id} ${row.hit}"] = it }
                println(
                    "TERRA drive floor $floor ${row.id} HIT ${row.hit}: cavity ${"%.1f".format(cavity)} ms (x${"%.2f".format(cavity / cavityToday)}), " +
                        "bar ${"%.1f".format(bar)} ms (x${"%.2f".format(bar / barToday)}), tanh input ${"%.4f".format(tanhIn)} (x${"%.2f".format(tanhIn / tanhToday)})",
                )
                val what = "floor $floor ${row.id} HIT ${row.hit}"
                near(row.cavityMs, cavity, "$what: cavity ms above 0.12")
                near(row.barMs, bar, "$what: bar ms above 0.12")
                near(row.tanhIn, tanhIn, "$what: cavity tanh input")
                assertTrue(tanhIn <= 0.36, "$what: the cavity's tanh input $tanhIn is past 0.36, more than 4 % from linear")
            }
        }

        val kick = got.getValue("${Terra.HIT_FLOOR} tkick 1.0")
        val wraith = got.getValue("${Terra.HIT_FLOOR} wraith 1.0")
        assertTrue(kick[0] > wraith[0], "HIT 1: the cavity rattles ${kick[0]} ms under THUMP KICK and ${wraith[0]} ms under WRAITH WORD; BUZZ does not follow the striker")
        assertTrue(kick[1] > wraith[1], "HIT 1: the bar rattles ${kick[1]} ms under THUMP KICK and ${wraith[1]} ms under WRAITH WORD; BUZZ does not follow the striker")

        if (full) {
            for (s in TerraStrikers.TEN.filter { it.id !in setOf("tkick", "tsnare", "wraith") }) {
                for (hit in listOf(0.5f, 1f)) {
                    val (cavity, bar, tanhIn) = measure(s.id, hit, Terra.HIT_FLOOR)
                    println(
                        "TERRA drive floor ${Terra.HIT_FLOOR} ${s.id} HIT $hit (printed, not pinned): cavity x${"%.2f".format(cavity / cavityToday)}, " +
                            "bar x${"%.2f".format(bar / barToday)}, tanh input x${"%.2f".format(tanhIn / tanhToday)}",
                    )
                    assertTrue(cavity.isFinite() && bar.isFinite() && tanhIn.isFinite(), "${s.id} HIT $hit: a drive figure is not finite")
                }
            }
        }
    }

    /**
     * Render time beside Phase 0's two figures (spec, "Architecture", Cost;
     * record, Appendix A): HIT at 1.4-1.7x an unstruck render, capture work
     * counted, and the neutral path at 0.98-1.05x. Neutral here is today's
     * `Terra.render`, now routed through `renderWith`, against the frozen
     * `LegacyTerraBank.render`, which is the claim the null path rests on.
     * Median of 7 after 3 warm-ups, as Phase 0 timed it. Printed, not
     * asserted: timing on a shared runner is not a property of the code.
     */
    @Test
    fun `the cost of a struck render is printed`() {
        val head = TerraStrikers.head("tkick")
        fun medianMs(block: () -> Unit): Double {
            repeat(3) { block() }
            val times = (0 until 7).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()
            return times[3]
        }
        for (voice in TerraVoice.entries) {
            val legacy = medianMs { LegacyTerraBank.render(voice, emptyMap()) }
            val plain = medianMs { Terra.render(voice) }
            val struck = medianMs { Terra.renderStruck(voice, emptyMap(), head, 0.5f) }
            println("TERRA cost $voice: neutral ${"%.1f".format(plain)} ms against the frozen copy's ${"%.1f".format(legacy)} ms (${"%.2f".format(plain / legacy)}x; Phase 0 0.98-1.05x)")
            println("TERRA cost $voice: unstruck ${"%.1f".format(plain)} ms, HIT 0.5 ${"%.1f".format(struck)} ms (${"%.2f".format(struck / plain)}x; Phase 0 1.4-1.7x)")
        }
    }
}
