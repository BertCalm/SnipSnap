package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FathomTest {

    /**
     * How deeply the amplitude envelope ripples once its decay is removed —
     * the depth of the beating between two detuned oscillators.
     *
     * Depth, not rate. Counting zero crossings was tried and abandoned: at
     * SPREAD=0 the beat is ~0.19 Hz, under one cycle across the whole note,
     * so there is no rate to count and the number produced is the
     * measurement's own noise floor.
     *
     * 50 ms frames — 4.1 cycles of GRIND's 82.4 Hz carrier. Shorter frames
     * alias against the carrier and manufacture a ripple that has nothing to
     * do with SPREAD; at 10 ms the artifact was larger than the signal and
     * inverted the comparison. RMS rather than peak for the same reason:
     * taking an extremum per frame is phase-sensitive.
     *
     * The trend is removed by a least-squares line through the log envelope.
     * An exponential decay is a straight line in log space, so the fit
     * removes it exactly and filters nothing — unlike a moving average,
     * whose window is the same order as the beat period and absorbs the
     * ripple it is meant to expose.
     */
    private fun beatRipple(snip: Snip): Float {
        val win = Dsp.RATE * 50 / 1000                 // 50 ms envelope frames
        val env = snip.samples.asIterable().chunked(win) { frame ->
            kotlin.math.sqrt(frame.map { it * it }.average()).toFloat()
        }
        val body = env.drop(1).dropLast(1).filter { it > 1e-9f }
        val n = body.size
        if (n < 8) return 0f

        val logs = body.map { kotlin.math.ln(it.toDouble()) }
        val meanX = (n - 1) / 2.0
        val meanY = logs.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            sxy += dx * (logs[i] - meanY)
            sxx += dx * dx
        }
        val slope = if (sxx > 0.0) sxy / sxx else 0.0

        val residuals = (0 until n).map { i -> logs[i] - (meanY + slope * (i - meanX)) }
        val meanR = residuals.average()
        val variance = residuals.map { (it - meanR) * (it - meanR) }.average()
        return kotlin.math.sqrt(variance).toFloat()
    }

    @Test
    fun `every voice renders clean audio at defaults, both corners, and DEEP's cross corners`() {
        for (voice in FathomVoice.entries) {
            val cases = listOf(
                emptyMap(),
                Fathom.macrosFor(voice).associate { it.name to 0f },
                Fathom.macrosFor(voice).associate { it.name to 1f },
                // Cross corners, not just uniform ones: a full GLIDE at the
                // bottom of the tuning range starts the slide an octave below
                // TUNE=0's root, and the shortest DECAY gives the clamp the
                // least room to work in. Both macros are shared by every
                // voice, so the corners apply to all three, not just DEEP.
                mapOf("GLIDE" to 1f, "TUNE" to 0f),
                mapOf("GLIDE" to 1f, "TUNE" to 0f, "DECAY" to 0f),
            )
            for (macros in cases) {
                val snip = Fathom.render(voice, macros)
                assertTrue(snip.samples.isNotEmpty(), "$voice rendered nothing for $macros")
                assertTrue(snip.samples.all { it.isFinite() }, "$voice rendered NaN/Inf for $macros")
                assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "$voice rendered silence for $macros")
                val dc = snip.samples.average().toFloat()
                // Worst case measured across all three voices, all five
                // cases: 0.0283 (DEEP, GLIDE=1/TUNE=0/DECAY=0). 0.05 leaves
                // headroom rather than pinning the observed ceiling exactly.
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc for $macros")
            }
        }
    }

    @Test
    fun `SPREAD beats - wider detune ripples the envelope more deeply`() {
        // DRIVE pinned to 0. Saturation compresses amplitude variation, so at
        // the default DRIVE the beating survives as spectral movement rather
        // than envelope movement — real and musical, but invisible to an
        // envelope measurement. Isolating SPREAD from that confound is what
        // makes this a test of SPREAD.
        val narrow = beatRipple(
            Fathom.render(FathomVoice.GRIND, mapOf("SPREAD" to 0f, "DECAY" to 1f, "DRIVE" to 0f)),
        )
        val wide = beatRipple(
            Fathom.render(FathomVoice.GRIND, mapOf("SPREAD" to 1f, "DECAY" to 1f, "DRIVE" to 0f)),
        )
        // Measured 0.0152 -> 0.1959, a 12.9x separation. The 4x factor leaves
        // three-fold headroom so this does not turn fragile if GRIND's
        // defaults are ever retuned.
        assertTrue(wide > narrow * 4f, "SPREAD should ripple deeper: $narrow -> $wide")

        // A second case at GRIND's own default DRIVE (0.45) was proposed to
        // close the gap between this DRIVE=0 measurement and the shipped
        // operating point. Measured instead of assumed: the separation does
        // not merely shrink under drive, it inverts. Swept at DECAY=1:
        // DRIVE=0 -> 12.86x (matches above), DRIVE=0.1 -> 1.57x,
        // DRIVE=0.2 -> 0.74x, DRIVE=0.3 -> 0.62x, DRIVE=0.45 -> 0.59x,
        // DRIVE=1.0 -> 0.60x. At GRIND's shipped DECAY (0.7) and DRIVE
        // (0.45) together the ratio is 0.99x — no separation at all. Drive
        // saturation compresses the wide-SPREAD envelope's beat swings
        // harder than the narrow one's (the wide pair's summed peaks clip
        // deeper into tanh's flat region), so past a small amount of DRIVE
        // the "wider ripples more" relationship this test measures no longer
        // holds. That's the same confound the comment above already names —
        // DRIVE moves the beating from the envelope into the spectrum — just
        // stronger than assumed. No second assertion is added: one would
        // have to assert the opposite of this test's claim at the shipped
        // DRIVE, which is a different (also true) fact, not more coverage of
        // this one.
    }

    @Test
    fun `every voice declares the five shared macros plus its own`() {
        val shared = listOf("TUNE", "GLIDE", "DRIVE", "CUTOFF", "DECAY")
        val own = mapOf(
            FathomVoice.DEEP to "SWEEP",
            FathomVoice.GRIND to "SPREAD",
            FathomVoice.GLASS to "RATIO",
        )
        for (voice in FathomVoice.entries) {
            val names = Fathom.macrosFor(voice).map { it.name }
            assertEquals(names.size, names.toSet().size, "$voice declared a duplicate macro")
            assertEquals(shared + own.getValue(voice), names, "$voice macro names or order")
        }
    }

    /**
     * The macro value to pin each voice's own confound to so [TestPitch] gets
     * a clean, unambiguous fundamental: DEEP's SWEEP blip, GRIND's detuned
     * beating pair, and GLASS's FM sidebands can each otherwise be mistaken
     * for a different pitch than the one the oscillator(s) are actually
     * playing. GLASS pins RATIO to its own default (0.25, which snaps to
     * unison) rather than to 0 — at unison the modulator is exactly the
     * carrier's frequency, so every FM sideband lands on an integer multiple
     * of the true fundamental and the detector still reads it correctly.
     * Measured clean for all three voices: TUNE 0.5->1 read as almost
     * exactly a 2x octave (DEEP 2.0x, GRIND 2.0x, GLASS 1.995x).
     */
    private val pitchSafeConfound: Map<FathomVoice, Pair<String, Float>> = mapOf(
        FathomVoice.DEEP to ("SWEEP" to 0f),
        FathomVoice.GRIND to ("SPREAD" to 0f),
        FathomVoice.GLASS to ("RATIO" to 0.25f),
    )

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        for (voice in FathomVoice.entries) {
            val distinct = HashSet<Float>()
            for (i in 0..100) distinct.add(Fathom.frequencyFor(voice, i / 100f))
            assertEquals(Fathom.TUNE_SEMITONES + 1, distinct.size, "$voice TUNE snap count")

            val (confoundName, confoundValue) = pitchSafeConfound.getValue(voice)
            // Measured from TUNE 0.5 rather than 0. DEEP's root is 41.2 Hz and
            // Pitch.MIN_HZ is 40f, so the bottom of the range sits on the
            // detector's floor and reads as "no pitch" — a limit of the measuring
            // tool, not of the engine. 0.5 -> 1.0 is one octave, both ends
            // comfortably inside the detector's range for every voice.
            val low = TestPitch.estimate(
                Fathom.render(voice, mapOf(confoundName to confoundValue, "TUNE" to 0.5f)),
                fromSec = 0.05f, windowSec = 0.2f,
            )
            val high = TestPitch.estimate(
                Fathom.render(voice, mapOf(confoundName to confoundValue, "TUNE" to 1f)),
                fromSec = 0.05f, windowSec = 0.2f,
            )
            assertTrue(
                high > low * 1.8f && high < low * 2.2f,
                "$voice TUNE 0.5 -> 1 is one octave: $low Hz -> $high Hz",
            )
        }
    }

    @Test
    fun `DRIVE adds harmonics`() {
        // GRIND is deliberately excluded. Measured centroid ratio at
        // DRIVE 0 -> 1 is 0.978 (soft=103.5Hz, hard=101.1Hz) — the centroid
        // does not rise, it is flat to within noise. GRIND's source is
        // already a raw saw pair, harmonically dense across the spectrum
        // before DRIVE touches it, and its default CUTOFF (~330 Hz) sits
        // well above where most of that energy already lives, so saturation
        // has little new high-frequency content left to add that the filter
        // would let through. This is a real difference in how DRIVE behaves
        // on GRIND, not a measurement artifact — asserting a 1.3x brighten
        // here would be false, not merely weak.
        for (voice in listOf(FathomVoice.DEEP, FathomVoice.GLASS)) {
            val clean = FeatureExtractor.extract(Fathom.render(voice, mapOf("DRIVE" to 0f)))
            val dirty = FeatureExtractor.extract(Fathom.render(voice, mapOf("DRIVE" to 1f)))
            assertTrue(
                dirty.centroidHz > clean.centroidHz * 1.3f,
                "$voice DRIVE should brighten: ${clean.centroidHz}Hz -> ${dirty.centroidHz}Hz",
            )
        }
    }

    @Test
    fun `CUTOFF opens`() {
        for (voice in FathomVoice.entries) {
            val dark = FeatureExtractor.extract(Fathom.render(voice, mapOf("CUTOFF" to 0.05f)))
            val open = FeatureExtractor.extract(Fathom.render(voice, mapOf("CUTOFF" to 0.95f)))
            assertTrue(
                open.centroidHz > dark.centroidHz * 1.5f,
                "$voice CUTOFF up should brighten: ${dark.centroidHz}Hz -> ${open.centroidHz}Hz",
            )
        }
    }

    @Test
    fun `a bass note is harmonic, not noise`() {
        // Deliberately NOT asserting a DrumClass. VelvetTest discovered the
        // classifier files harmonic stabs under PERC - "the classifier's
        // honest shelf for a harmonic hit" - so predicting the label for a
        // new engine is guesswork. Flatness measures the thing that actually
        // matters.
        for (voice in FathomVoice.entries) {
            val f = FeatureExtractor.extract(Fathom.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in FathomVoice.entries) {
            val short = FeatureExtractor.extract(Fathom.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Fathom.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(
                long.decayMs > short.decayMs * 1.5f,
                "$voice DECAY should stretch the note: ${short.decayMs}ms -> ${long.decayMs}ms",
            )
        }
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in FathomVoice.entries) {
            val a = Fathom.scramble(voice, Random(7))
            val b = Fathom.scramble(voice, Random(7))
            assertEquals(a, b, "$voice scramble must be reproducible from a seed")
            assertTrue(a.values.all { it in 0f..1f }, "$voice scramble left the 0..1 range")
        }
    }

    @Test
    fun `factory defaults classify consistently`() {
        // Labels observed, not predicted — see `a bass note is harmonic, not
        // noise` for why we don't guess them.
        //
        // GRIND was re-pinned in Task 3: it moved from TOM to KICK once it
        // got its own detuned-saw source instead of sharing DEEP's sine path.
        //
        // GLASS was re-pinned in Task 4: it moved from TOM to PERC once it
        // got its own FM source instead of sharing DEEP's sine path. The
        // sidebands FM adds are exactly the kind of high-frequency energy
        // that pushes the classifier off TOM and onto PERC — the same shelf
        // VelvetTest found for harmonic stabs in general.
        assertEquals(DrumClass.KICK, Classifier.classify(Fathom.render(FathomVoice.DEEP)).drumClass)
        assertEquals(DrumClass.KICK, Classifier.classify(Fathom.render(FathomVoice.GRIND)).drumClass)
        assertEquals(DrumClass.PERC, Classifier.classify(Fathom.render(FathomVoice.GLASS)).drumClass)
    }

    @Test
    fun `GLIDE actually glides - pitch rises into the target`() {
        for (voice in FathomVoice.entries) {
            val (confoundName, confoundValue) = pitchSafeConfound.getValue(voice)
            // Long decay so both analysis windows sit inside the note.
            // TUNE=1 puts the target an octave above where a full GLIDE
            // starts, and both ends clear Pitch.MIN_HZ = 40f for every
            // voice's root. Each voice's own confound (see
            // [pitchSafeConfound]) keeps SWEEP/SPREAD/RATIO from being
            // mistaken for part of the glide.
            val snip = Fathom.render(
                voice,
                mapOf(confoundName to confoundValue, "GLIDE" to 1f, "DECAY" to 0.9f, "TUNE" to 1f),
            )
            val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
            val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
            assertTrue(start > 0f && end > 0f, "$voice pitch detection failed: $start Hz -> $end Hz")
            assertTrue(end > start * 1.3f, "$voice GLIDE should rise into the target: $start Hz -> $end Hz")

            // The slide must LAND, not merely travel — a glide still moving when
            // the note ends is the one way this can sound broken, so the clamp
            // that prevents it needs a test rather than a comment.
            val target = Fathom.frequencyFor(voice, 1f)
            assertTrue(
                kotlin.math.abs(end - target) < target * 0.05f,
                "$voice GLIDE should land on target: $end Hz vs $target Hz",
            )
        }
    }

    @Test
    fun `GLIDE at zero holds a steady pitch`() {
        for (voice in FathomVoice.entries) {
            val (confoundName, confoundValue) = pitchSafeConfound.getValue(voice)
            val snip = Fathom.render(
                voice,
                mapOf(confoundName to confoundValue, "GLIDE" to 0f, "DECAY" to 0.9f, "TUNE" to 1f),
            )
            val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
            val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
            assertTrue(start > 0f && end > 0f, "$voice pitch detection failed: $start Hz -> $end Hz")
            assertTrue(
                kotlin.math.abs(end - start) < start * 0.1f,
                "$voice GLIDE 0 should hold steady: $start Hz -> $end Hz",
            )
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in FathomVoice.entries) {
            val a = Fathom.render(voice, mapOf("DRIVE" to 0.7f))
            val b = Fathom.render(voice, mapOf("DRIVE" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `RATIO snaps - the knob yields exactly the ratio set and no more`() {
        // Render-level, not just ratioFor-level: sweeping RATIO and counting
        // distinct rendered outputs proves the knob's snapping *and* that
        // render actually reads fmRatio to produce audibly different audio
        // for each snapped value — a test of ratioFor alone would still pass
        // if render stopped consuming it. Five distinct renders from a
        // five-element snap set also implies every index was hit: an
        // unreachable ratio would mean fewer than RATIOS.size groups.
        // Following TinesTest's `RATIO snaps to characters, not a continuum`
        // pattern, including its List<Float> collection — a HashSet of
        // FloatArray would hash by identity and make every render "distinct"
        // regardless of content, passing vacuously.
        val distinct = HashSet<List<Float>>()
        for (i in 0..200) {
            val snip = Fathom.render(FathomVoice.GLASS, mapOf("RATIO" to i / 200f))
            distinct.add(snip.samples.take(512))
        }
        assertEquals(Fathom.RATIOS.size, distinct.size, "RATIO must snap to exactly the ratio set, never more")
    }

    @Test
    fun `DRIVE on GLASS deepens the FM, not just the saturation`() {
        val soft = FeatureExtractor.extract(Fathom.render(FathomVoice.GLASS, mapOf("DRIVE" to 0f)))
        val hard = FeatureExtractor.extract(Fathom.render(FathomVoice.GLASS, mapOf("DRIVE" to 1f)))
        assertTrue(
            hard.centroidHz > soft.centroidHz * 1.5f,
            "DRIVE should add sidebands as well as harmonics: ${soft.centroidHz}Hz -> ${hard.centroidHz}Hz",
        )
    }

    @Test
    fun `a FATHOM patch round-trips through JSON`() {
        val original = FathomPatch(
            name = "Sliding Sub",
            voice = FathomVoice.DEEP,
            macros = mapOf("GLIDE" to 0.8f, "DRIVE" to 0.6f),
        )
        val restored = Patches.fromJsonText(original.toJsonText())
        assertEquals(original.engine, restored.engine)
        assertEquals(original.voiceName, restored.voiceName)
        assertEquals(original.macros, restored.macros)
        assertTrue(
            original.render().samples.contentEquals(restored.render().samples),
            "a restored patch must render identical audio",
        )
    }

    @Test
    fun `a FATHOM patch rejects a macro the voice does not have`() {
        assertFailsWith<IllegalArgumentException> {
            FathomPatch("Bad", FathomVoice.DEEP, mapOf("SPREAD" to 0.5f))
        }
    }
}
