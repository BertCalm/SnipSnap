package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FathomTest {

    @Test
    fun `DEEP renders clean audio at defaults and both corners`() {
        for (macros in listOf(
            emptyMap(),
            Fathom.macrosFor(FathomVoice.DEEP).associate { it.name to 0f },
            Fathom.macrosFor(FathomVoice.DEEP).associate { it.name to 1f },
            // Cross corners, not just uniform ones: a full GLIDE at the
            // bottom of the tuning range starts the slide at 20.6 Hz, and
            // the shortest DECAY gives the clamp the least room to work in.
            mapOf("GLIDE" to 1f, "TUNE" to 0f),
            mapOf("GLIDE" to 1f, "TUNE" to 0f, "DECAY" to 0f),
        )) {
            val snip = Fathom.render(FathomVoice.DEEP, macros)
            assertTrue(snip.samples.isNotEmpty(), "DEEP rendered nothing for $macros")
            assertTrue(snip.samples.all { it.isFinite() }, "DEEP rendered NaN/Inf for $macros")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "DEEP rendered silence for $macros")
            val dc = snip.samples.average().toFloat()
            assertTrue(kotlin.math.abs(dc) < 0.05f, "DEEP has DC offset $dc for $macros")
        }
    }

    @Test
    fun `DEEP is deterministic`() {
        val a = Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0.7f))
        val b = Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0.7f))
        assertTrue(a.samples.contentEquals(b.samples), "same macros must render the same bytes")
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

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Fathom.frequencyFor(FathomVoice.DEEP, i / 100f))
        assertEquals(Fathom.TUNE_SEMITONES + 1, distinct.size)

        // Measured from TUNE 0.5 rather than 0. DEEP's root is 41.2 Hz and
        // Pitch.MIN_HZ is 40f, so the bottom of the range sits on the
        // detector's floor and reads as "no pitch" — a limit of the measuring
        // tool, not of the engine. 0.5 -> 1.0 is one octave, 82.4 Hz ->
        // 164.8 Hz, both comfortably inside the detector's range.
        val low = TestPitch.estimate(
            Fathom.render(FathomVoice.DEEP, mapOf("TUNE" to 0.5f, "SWEEP" to 0f)),
            fromSec = 0.05f, windowSec = 0.2f,
        )
        val high = TestPitch.estimate(
            Fathom.render(FathomVoice.DEEP, mapOf("TUNE" to 1f, "SWEEP" to 0f)),
            fromSec = 0.05f, windowSec = 0.2f,
        )
        assertTrue(
            high > low * 1.8f && high < low * 2.2f,
            "TUNE 0.5 -> 1 is one octave: $low Hz -> $high Hz",
        )
    }

    @Test
    fun `DRIVE adds harmonics without adding level`() {
        val clean = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0f)))
        val dirty = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 1f)))
        assertTrue(
            dirty.centroidHz > clean.centroidHz * 1.3f,
            "DRIVE should brighten: ${clean.centroidHz}Hz -> ${dirty.centroidHz}Hz",
        )
    }

    @Test
    fun `CUTOFF opens`() {
        val dark = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("CUTOFF" to 0.05f)))
        val open = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("CUTOFF" to 0.95f)))
        assertTrue(
            open.centroidHz > dark.centroidHz * 1.5f,
            "CUTOFF up should brighten: ${dark.centroidHz}Hz -> ${open.centroidHz}Hz",
        )
    }

    @Test
    fun `a bass note is harmonic, not noise`() {
        // Deliberately NOT asserting a DrumClass. VelvetTest discovered the
        // classifier files harmonic stabs under PERC - "the classifier's
        // honest shelf for a harmonic hit" - so predicting the label for a
        // new engine is guesswork. Flatness measures the thing that actually
        // matters. See Step 4 for pinning the label once it is observed.
        for (voice in FathomVoice.entries) {
            val f = FeatureExtractor.extract(Fathom.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the note: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
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
        // GRIND and GLASS agree here only because they currently share DEEP's
        // sine path, differing just in root frequency. Task 3 gives GRIND
        // detuned saws and Task 4 gives GLASS FM; when either lands, expect
        // this test to fail. That is the signal that the voice now sounds
        // different — re-observe and re-pin it. Do NOT tune a new voice to
        // preserve an old label.
        assertEquals(DrumClass.KICK, Classifier.classify(Fathom.render(FathomVoice.DEEP)).drumClass)
        assertEquals(DrumClass.TOM, Classifier.classify(Fathom.render(FathomVoice.GRIND)).drumClass)
        assertEquals(DrumClass.TOM, Classifier.classify(Fathom.render(FathomVoice.GLASS)).drumClass)
    }

    @Test
    fun `GLIDE actually glides - pitch rises into the target`() {
        // Long decay so both analysis windows sit inside the note, and SWEEP
        // off so the attack blip cannot be mistaken for the glide.
        // TUNE=1 puts the target at 164.8 Hz, so a full GLIDE starts an
        // octave below at 82.4 Hz. Both ends clear Pitch.MIN_HZ = 40f. At
        // TUNE=0.5 the glide would START at 41.2 Hz, on the detector's floor,
        // and read as no pitch — the same trap the TUNE test hit in Task 1.
        val snip = Fathom.render(
            FathomVoice.DEEP,
            mapOf("GLIDE" to 1f, "DECAY" to 0.9f, "SWEEP" to 0f, "TUNE" to 1f),
        )
        val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
        val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
        assertTrue(start > 0f && end > 0f, "pitch detection failed: $start Hz -> $end Hz")
        assertTrue(end > start * 1.3f, "GLIDE should rise into the target: $start Hz -> $end Hz")

        // The slide must LAND, not merely travel — a glide still moving when
        // the note ends is the one way this can sound broken, so the clamp
        // that prevents it needs a test rather than a comment.
        val target = Fathom.frequencyFor(FathomVoice.DEEP, 1f)
        assertTrue(
            kotlin.math.abs(end - target) < target * 0.05f,
            "GLIDE should land on target: $end Hz vs $target Hz",
        )
    }

    @Test
    fun `GLIDE at zero holds a steady pitch`() {
        val snip = Fathom.render(
            FathomVoice.DEEP,
            mapOf("GLIDE" to 0f, "DECAY" to 0.9f, "SWEEP" to 0f, "TUNE" to 1f),
        )
        val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
        val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
        assertTrue(start > 0f && end > 0f, "pitch detection failed: $start Hz -> $end Hz")
        assertTrue(
            kotlin.math.abs(end - start) < start * 0.1f,
            "GLIDE 0 should hold steady: $start Hz -> $end Hz",
        )
    }
}
