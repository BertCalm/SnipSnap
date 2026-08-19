package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThumpTest {

    // ---------- the classifier is the judge ----------
    // A THUMP factory sound isn't done until our own analysis calls it what
    // it claims to be. This is the playability contract, executable.

    private fun classOf(voice: ThumpVoice) = Classifier.classify(Thump.render(voice)).drumClass

    @Test fun `factory kick is a kick`() = assertEquals(DrumClass.KICK, classOf(ThumpVoice.KICK))
    @Test fun `factory snare is a snare`() = assertEquals(DrumClass.SNARE, classOf(ThumpVoice.SNARE))
    @Test fun `factory closed hat is a closed hat`() =
        assertEquals(DrumClass.HAT_CLOSED, classOf(ThumpVoice.HAT_CLOSED))
    @Test fun `factory open hat is an open hat`() =
        assertEquals(DrumClass.HAT_OPEN, classOf(ThumpVoice.HAT_OPEN))
    @Test fun `factory clap is a clap`() = assertEquals(DrumClass.CLAP, classOf(ThumpVoice.CLAP))
    @Test fun `factory tom is a tom`() = assertEquals(DrumClass.TOM, classOf(ThumpVoice.TOM))

    // ---------- macros are audible and bounded ----------

    @Test
    fun `kick TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `kick DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `snare SNAP shifts tone toward noise`() {
        val tone = FeatureExtractor.extract(Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 0.05f)))
        val noise = FeatureExtractor.extract(Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 1f)))
        assertTrue(noise.flatness > tone.flatness, "SNAP up should be noisier: ${tone.flatness} -> ${noise.flatness}")
    }

    @Test
    fun `hat METAL brightens`() {
        val dull = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED, mapOf("METAL" to 0f)))
        val bright = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED, mapOf("METAL" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz)
    }

    @Test
    fun `open hat rings longer than closed at defaults`() {
        val closed = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED))
        val open = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_OPEN))
        assertTrue(open.decayMs > closed.decayMs * 2)
    }

    @Test
    fun `every corner of every macro space renders clean audio`() {
        // Bounded-by-construction, verified: all-zeros and all-ones must be
        // playable sounds, not silence, clipping or NaN.
        for (voice in ThumpVoice.entries) {
            for (value in floatArrayOf(0f, 1f)) {
                val macros = Thump.macrosFor(voice).associate { it.name to value }
                val snip = Thump.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice at $value rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "$voice at $value produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "$voice at $value clipped")
                assertTrue(snip.peak() > 0.5f, "$voice at $value is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `scramble is reproducible and always playable`() {
        for (voice in ThumpVoice.entries) {
            val a = Thump.scramble(voice, Random(42))
            val b = Thump.scramble(voice, Random(42))
            assertEquals(a, b, "same seed must roll the same patch")

            repeat(10) { roll ->
                val macros = Thump.scramble(voice, Random(roll))
                assertTrue(macros.values.all { it in 0f..1f })
                val snip = Thump.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $roll broke")
                assertTrue(snip.peak() > 0.5f, "$voice roll $roll too quiet")
            }
        }
    }

    @Test
    fun `scrambled kicks still read as kicks most of the time`() {
        // The whole point of bounded ranges: the dice land somewhere musical.
        var kicks = 0
        repeat(10) { roll ->
            val c = Classifier.classify(Thump.render(ThumpVoice.KICK, Thump.scramble(ThumpVoice.KICK, Random(roll))))
            if (c.drumClass == DrumClass.KICK) kicks++
        }
        assertTrue(kicks >= 7, "only $kicks/10 scrambled kicks classified as KICK")
    }

    @Test
    fun `unknown macros are ignored, known ones clamp`() {
        val snip = Thump.render(ThumpVoice.KICK, mapOf("WOBBLE" to 0.5f, "TUNE" to 9f))
        assertTrue(snip.frameCount > 0)
    }

    @Test
    fun `renders are deterministic`() {
        val a = Thump.render(ThumpVoice.SNARE)
        val b = Thump.render(ThumpVoice.SNARE)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `renders are mpc-native`() {
        val snip = Thump.render(ThumpVoice.CLAP)
        assertEquals(44_100, snip.sampleRate)
        assertEquals(1, snip.channels)
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through json`() {
        val patch = ThumpPatch("Basement Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.2f, "DRIVE" to 0.8f))
        assertEquals(patch, ThumpPatch.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `patch render equals direct render`() {
        val patch = ThumpPatch("X", ThumpVoice.TOM, mapOf("TUNE" to 0.7f))
        assertTrue(patch.render().samples.contentEquals(Thump.render(ThumpVoice.TOM, mapOf("TUNE" to 0.7f)).samples))
    }

    @Test
    fun `patch validation refuses nonsense`() {
        assertFailsWith<IllegalArgumentException> { ThumpPatch("", ThumpVoice.KICK, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ThumpPatch("x", ThumpVoice.KICK, mapOf("CUTOFF" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { ThumpPatch("x", ThumpVoice.KICK, mapOf("TUNE" to 2f)) }
        assertFailsWith<com.snipsnap.json.JsonException> { ThumpPatch.fromJsonText("""{"engine":"VELVET"}""") }
    }
}
