package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluckTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in PluckVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Pluck.macrosFor(voice).associate { it.name to 0f },
                Pluck.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Pluck.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot")
            }
        }
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in PluckVoice.entries) {
            assertEquals(Pluck.scramble(voice, Random(2)), Pluck.scramble(voice, Random(2)))
            repeat(8) { seed ->
                val snip = Pluck.render(voice, Pluck.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
                val c = Classifier.classify(snip)
                // The DC-thump regression guard lives here: a dark, damped
                // pluck must never decay into a fake kick.
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
        for (voice in PluckVoice.entries) {
            assertTrue(
                Pluck.render(voice).samples.contentEquals(Pluck.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `factory defaults all classify as percussion`() {
        for (voice in PluckVoice.entries) {
            val c = Classifier.classify(Pluck.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    @Test
    fun `DAMP damps`() {
        val ringing = FeatureExtractor.extract(Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.05f)))
        val muted = FeatureExtractor.extract(Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.95f)))
        assertTrue(
            ringing.decayMs > muted.decayMs * 1.5f,
            "DAMP up should choke the ring: ${ringing.decayMs}ms -> ${muted.decayMs}ms",
        )
    }

    @Test
    fun `PICK brightens the attack`() {
        val soft = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.05f)))
        val hard = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.95f)))
        assertTrue(
            hard.centroidHz > soft.centroidHz * 1.2f,
            "PICK should brighten: ${soft.centroidHz} -> ${hard.centroidHz}",
        )
    }

    @Test
    fun `TUNE snaps to semitones`() {
        // 101 knob positions must land on exactly 25 notes (two octaves
        // inclusive) - pads get notes, not frequencies.
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Pluck.frequencyFor(PluckVoice.NYLON, i / 100f))
        assertEquals(Pluck.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(110f, Pluck.frequencyFor(PluckVoice.NYLON, 0f))
        assertEquals(440f, Pluck.frequencyFor(PluckVoice.NYLON, 1f))
    }

    @Test
    fun `TUNE up raises the pitch of the render`() {
        // Pitch, not centroid: KS loses its highs faster at higher tunings,
        // so brightness actually *falls* two octaves up while the note
        // unmistakably rises. Autocorrelation reads the note.
        val lowNote = TestPitch.estimate(Pluck.render(PluckVoice.NYLON, mapOf("TUNE" to 0f)))
        val highNote = TestPitch.estimate(Pluck.render(PluckVoice.NYLON, mapOf("TUNE" to 1f)))
        assertTrue(
            highNote > lowNote * 3f && highNote < lowNote * 5f,
            "TUNE 0 -> 1 is two octaves: measured $lowNote Hz -> $highNote Hz",
        )
    }

    @Test
    fun `DOUBLE thickens audibly`() {
        val single = Pluck.render(PluckVoice.KOTO, mapOf("DOUBLE" to 0f))
        val doubled = Pluck.render(PluckVoice.KOTO, mapOf("DOUBLE" to 1f))
        assertEquals(single.frameCount, doubled.frameCount)
        var diff = 0.0
        var level = 0.0
        for (i in single.samples.indices) {
            diff += Math.abs((single.samples[i] - doubled.samples[i]).toDouble())
            level += Math.abs(single.samples[i].toDouble())
        }
        // Relative to the note's own level - a pluck is mostly quiet tail,
        // so an absolute threshold would only ever measure the attack.
        assertTrue(diff > level * 0.3, "the second string should be audible: diff/level = ${diff / level}")
    }
}
