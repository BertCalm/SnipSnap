package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VelvetTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        // The corners are also the SVF stability test: CHIP's bright
        // defaults rendered NaN silence before the cutoff and damping
        // bounds were pulled inside the filter's stable region.
        for (voice in VelvetVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Velvet.macrosFor(voice).associate { it.name to 0f },
                Velvet.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Velvet.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot")
            }
        }
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in VelvetVoice.entries) {
            assertEquals(Velvet.scramble(voice, Random(3)), Velvet.scramble(voice, Random(3)))
            repeat(8) { seed ->
                val snip = Velvet.render(voice, Velvet.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
                val c = Classifier.classify(snip)
                assertTrue(
                    c.drumClass != DrumClass.KICK && c.drumClass != DrumClass.LOOP &&
                        c.drumClass != DrumClass.UNKNOWN,
                    "$voice roll $seed classified ${c.drumClass}",
                )
            }
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in VelvetVoice.entries) {
            assertTrue(
                Velvet.render(voice).samples.contentEquals(Velvet.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `factory defaults all classify as percussion`() {
        // These read as tonal stabs to a human; PERC is the classifier's
        // honest shelf for a harmonic hit. The CLAP-vs-stab distinction is
        // covered by the flatness gate this engine forced into Classifier.
        for (voice in VelvetVoice.entries) {
            val c = Classifier.classify(Velvet.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    @Test
    fun `a stab is harmonic, not noise - the thing that separates it from a clap`() {
        for (voice in VelvetVoice.entries) {
            val f = FeatureExtractor.extract(Velvet.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `CUTOFF opens`() {
        val dark = FeatureExtractor.extract(Velvet.render(VelvetVoice.BASS, mapOf("CUTOFF" to 0.05f)))
        val open = FeatureExtractor.extract(Velvet.render(VelvetVoice.BASS, mapOf("CUTOFF" to 0.95f)))
        assertTrue(
            open.centroidHz > dark.centroidHz * 1.5f,
            "CUTOFF up should brighten: ${dark.centroidHz} -> ${open.centroidHz}",
        )
    }

    @Test
    fun `SHAPE walks saw to pulse audibly`() {
        val sawSide = Velvet.render(VelvetVoice.BRASS, mapOf("SHAPE" to 0f))
        val pulseSide = Velvet.render(VelvetVoice.BRASS, mapOf("SHAPE" to 1f))
        assertEquals(sawSide.frameCount, pulseSide.frameCount)
        var diff = 0.0
        var level = 0.0
        for (i in sawSide.samples.indices) {
            diff += Math.abs((sawSide.samples[i] - pulseSide.samples[i]).toDouble())
            level += Math.abs(sawSide.samples[i].toDouble())
        }
        assertTrue(diff > level * 0.3, "SHAPE ends should sound different")
    }

    @Test
    fun `FAT thickens audibly`() {
        val thin = Velvet.render(VelvetVoice.BRASS, mapOf("FAT" to 0f))
        val fat = Velvet.render(VelvetVoice.BRASS, mapOf("FAT" to 1f))
        var diff = 0.0
        var level = 0.0
        for (i in thin.samples.indices) {
            diff += Math.abs((thin.samples[i] - fat.samples[i]).toDouble())
            level += Math.abs(thin.samples[i].toDouble())
        }
        assertTrue(diff > level * 0.3, "the unison spread should be audible")
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Velvet.render(VelvetVoice.SQUELCH, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Velvet.render(VelvetVoice.SQUELCH, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the stab: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Velvet.frequencyFor(VelvetVoice.BRASS, i / 100f))
        assertEquals(Velvet.TUNE_SEMITONES + 1, distinct.size)

        // The estimator locks onto the sub oscillator an octave under the
        // base — fine, it halves both ends, so the two-octave ratio holds.
        val lowNote = TestPitch.estimate(Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 0f)), fromSec = 0.05f, windowSec = 0.2f)
        val highNote = TestPitch.estimate(Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 1f)), fromSec = 0.05f, windowSec = 0.2f)
        assertTrue(
            highNote > lowNote * 3f && highNote < lowNote * 5f,
            "TUNE 0 -> 1 is two octaves: measured $lowNote Hz -> $highNote Hz",
        )
    }
}
