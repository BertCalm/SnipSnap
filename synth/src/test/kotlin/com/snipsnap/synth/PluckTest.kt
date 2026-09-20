package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `scrambles are reproducible and stay in range`() {
        for (voice in PluckVoice.entries) {
            assertEquals(Pluck.scramble(voice, Random(2)), Pluck.scramble(voice, Random(2)))
            repeat(8) { seed ->
                val snip = Pluck.render(voice, Pluck.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
            }
        }
    }

    @Test
    fun `scrambled hits usually avoid the DC-thump fake-kick decay`() {
        // The DC-thump regression guard: a dark, damped pluck must usually
        // not decay into a fake kick. SCRAMBLE now rolls near a preset
        // (docs/SYNTH_UPGRADE.md, U2), so - like a preset itself can - a
        // roll can land close to that boundary; "most of the time", not
        // "always", is the doc's own contract for a scrambled roll.
        for (voice in PluckVoice.entries) {
            var misses = 0
            val rolls = 30
            repeat(rolls) { seed ->
                val c = Classifier.classify(Pluck.render(voice, Pluck.scramble(voice, Random(seed))))
                if (c.drumClass == DrumClass.KICK || c.drumClass == DrumClass.LOOP || c.drumClass == DrumClass.UNKNOWN) misses++
            }
            assertTrue(misses <= rolls / 3, "$voice: $misses/$rolls scrambled rolls came back unplayable")
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Pluck's own wiring: see DspTest for the central proof.
        for (voice in PluckVoice.entries) {
            val preset = PluckPresets.forVoice(voice).first()
            assertEquals(
                preset.macros,
                Pluck.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Pluck.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - see ThumpTest's own version of this test.
            assertEquals(
                Dsp.scrambleNear(Pluck.defaults(voice), 1f, Random(2)),
                Pluck.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
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

    @Test
    fun `a loop length below the KS minimum fails loudly instead of going unstable`() {
        // The real voice table never gets close to this (measured minimum
        // `exact` across every voice x TUNE semitone x DAMP is 175.93
        // samples, at KALIMBA TUNE=1/DAMP=1), so this drives Pluck.ks
        // directly with a synthetic freq/rate pair
        // that pushes the loop length under 2 samples - the old
        // `.coerceAtLeast(2)` produced a negative `frac` here, and `a =
        // (1-frac)/(1+frac)` with frac=-0.7 comes out ~5.67, an
        // unconditionally unstable feedback allpass. It must now fail the
        // require instead of silently returning something that blows up.
        assertFailsWith<IllegalArgumentException> {
            Pluck.ks(freq = 70_000f, seconds = 0.2f, damp = 0f, bodyLoopHz = 2600f, pickHz = 3000f, seed = 1, rate = 176_400)
        }
    }

    @Test
    fun `a loop length safely above the KS minimum still renders`() {
        // The boundary itself: exact ~2.68 samples here (comfortably above
        // MIN_LOOP_SAMPLES) must NOT throw and must produce a finite,
        // in-range buffer - the require must not be so conservative it
        // rejects legitimate high notes.
        val out = Pluck.ks(freq = 50_000f, seconds = 0.05f, damp = 0f, bodyLoopHz = 2600f, pickHz = 3000f, seed = 1, rate = 176_400)
        assertTrue(out.isNotEmpty() && out.all { it.isFinite() }, "a valid near-boundary loop length should still render cleanly")
    }
}
