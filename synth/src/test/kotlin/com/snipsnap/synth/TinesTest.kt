package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TinesTest {

    // ---------- the playability contract ----------

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in TinesVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Tines.macrosFor(voice).associate { it.name to 0f },
                Tines.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Tines.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
            }
        }
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in TinesVoice.entries) {
            assertEquals(Tines.scramble(voice, Random(4)), Tines.scramble(voice, Random(4)))
            repeat(8) { seed ->
                val snip = Tines.render(voice, Tines.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
                val c = Classifier.classify(snip)
                // A scrambled hit may land on a neighbouring pad, but never on
                // the two placements that would actually hurt: the kick slot
                // (these have no sub content) or the loop shelf (they are hits).
                assertTrue(
                    c.drumClass != DrumClass.KICK && c.drumClass != DrumClass.LOOP &&
                        c.drumClass != DrumClass.UNKNOWN,
                    "$voice roll $seed classified ${c.drumClass}",
                )
            }
        }
    }

    @Test
    fun `every voice is a one-shot, not a phrase`() {
        for (voice in TinesVoice.entries) {
            val full = Tines.macrosFor(voice).associate { it.name to 1f }
            assertTrue(
                Tines.render(voice, full).durationSeconds < 1.5f,
                "$voice at full DECAY must stay under the loop threshold",
            )
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in TinesVoice.entries) {
            assertTrue(
                Tines.render(voice).samples.contentEquals(Tines.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    // ---------- identity, judged by the classifier ----------

    @Test
    fun `factory defaults all classify as percussion`() {
        for (voice in TinesVoice.entries) {
            val c = Classifier.classify(Tines.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    // ---------- macros do what their names promise ----------

    @Test
    fun `BRIGHT brightens`() {
        val dull = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("BRIGHT" to 0.05f)))
        val bright = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("BRIGHT" to 0.95f)))
        assertTrue(
            bright.centroidHz > dull.centroidHz * 1.3f,
            "BRIGHT should push the centroid up: ${dull.centroidHz} -> ${bright.centroidHz}",
        )
    }

    @Test
    fun `TUNE tunes`() {
        val lowHit = FeatureExtractor.extract(Tines.render(TinesVoice.BLOCK, mapOf("TUNE" to 0.1f)))
        val highHit = FeatureExtractor.extract(Tines.render(TinesVoice.BLOCK, mapOf("TUNE" to 0.9f)))
        assertTrue(
            highHit.centroidHz > lowHit.centroidHz * 1.3f,
            "TUNE up should raise the pitch centre: ${lowHit.centroidHz} -> ${highHit.centroidHz}",
        )
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the ring: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
    }

    @Test
    fun `RATIO snaps to characters, not a continuum`() {
        // 25 knob positions must collapse onto exactly the snapped set - the
        // whole point of RATIO is that every position is a character.
        val distinct = HashSet<List<Float>>()
        for (i in 0 until 25) {
            val snip = Tines.render(TinesVoice.BELL, mapOf("RATIO" to i / 24f))
            distinct.add(snip.samples.take(512))
        }
        assertEquals(Tines.RATIOS.size, distinct.size)
    }

    @Test
    fun `WOBBLE moves the pitch during the note`() {
        val steady = Tines.render(TinesVoice.TOY, mapOf("WOBBLE" to 0f))
        val wobbly = Tines.render(TinesVoice.TOY, mapOf("WOBBLE" to 1f))
        // Same length, audibly different waveform - the LFO is baked in.
        assertEquals(steady.frameCount, wobbly.frameCount)
        var diff = 0.0
        for (i in steady.samples.indices) diff += Math.abs((steady.samples[i] - wobbly.samples[i]).toDouble())
        assertTrue(diff / steady.samples.size > 0.05, "WOBBLE at full should audibly modulate")
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through JSON`() {
        val patch = TinesPatch("Glass Half Full", TinesVoice.CHIME, mapOf("SHIMMER" to 0.8f, "DECAY" to 0.6f))
        val back = TinesPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(back.render().samples.contentEquals(patch.render().samples))
    }

    @Test
    fun `a THUMP patch refuses to load as TINES`() {
        val thump = ThumpPatch("Basement Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.2f))
        assertFailsWith<com.snipsnap.json.JsonException> {
            TinesPatch.fromJsonText(thump.toJsonText())
        }
    }

    @Test
    fun `patch validates macro names and ranges`() {
        assertFailsWith<IllegalArgumentException> {
            TinesPatch("Bad", TinesVoice.BELL, mapOf("CUTOFF" to 0.5f))
        }
        assertFailsWith<IllegalArgumentException> {
            TinesPatch("Bad", TinesVoice.BELL, mapOf("TUNE" to 1.5f))
        }
    }
}
