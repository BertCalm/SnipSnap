package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkinTest {

    // ---------- the classifier is the judge ----------
    // Same contract as ThumpTest: a factory sound isn't done until our own
    // analysis calls it what it claims to be.

    private fun classOf(voice: SkinVoice) = Classifier.classify(Skin.render(voice)).drumClass

    @Test fun `factory kick is a kick`() = assertEquals(DrumClass.KICK, classOf(SkinVoice.KICK))
    @Test fun `factory snare is a snare`() = assertEquals(DrumClass.SNARE, classOf(SkinVoice.SNARE))
    @Test fun `factory closed hat is a closed hat`() =
        assertEquals(DrumClass.HAT_CLOSED, classOf(SkinVoice.HAT_CLOSED))
    @Test fun `factory open hat is an open hat`() =
        assertEquals(DrumClass.HAT_OPEN, classOf(SkinVoice.HAT_OPEN))

    // ---------- macros are audible and bounded ----------

    @Test
    fun `kick TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `kick DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `kick TONE brightens`() {
        val dark = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dark.centroidHz, "TONE up should raise the centroid: ${dark.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `snare SNAP shifts tone toward noise`() {
        val tone = FeatureExtractor.extract(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 0.05f)))
        val noise = FeatureExtractor.extract(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 1f)))
        assertTrue(noise.flatness > tone.flatness, "SNAP up should be noisier: ${tone.flatness} -> ${noise.flatness}")
    }

    @Test
    fun `hat TONE brightens`() {
        val dull = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz, "TONE up should raise the centroid: ${dull.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `open hat rings longer than closed at defaults`() {
        val closed = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED))
        val open = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_OPEN))
        assertTrue(open.decayMs > closed.decayMs * 2)
    }

    @Test
    fun `every corner of every macro space renders clean audio`() {
        for (voice in SkinVoice.entries) {
            for (value in floatArrayOf(0f, 1f)) {
                val macros = Skin.macrosFor(voice).associate { it.name to value }
                val snip = Skin.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice at $value rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "$voice at $value produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "$voice at $value clipped")
                assertTrue(snip.peak() > 0.5f, "$voice at $value is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `no voice has a DC offset`() {
        for (voice in SkinVoice.entries) {
            val snip = Skin.render(voice)
            val dc = snip.samples.average().toFloat()
            assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc")
        }
    }

    @Test
    fun `scramble is reproducible and always playable`() {
        for (voice in SkinVoice.entries) {
            val a = Skin.scramble(voice, Random(42))
            val b = Skin.scramble(voice, Random(42))
            assertEquals(a, b, "same seed must roll the same patch")

            repeat(10) { roll ->
                val macros = Skin.scramble(voice, Random(roll))
                assertTrue(macros.values.all { it in 0f..1f })
                val snip = Skin.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $roll broke")
                assertTrue(snip.peak() > 0.5f, "$voice roll $roll too quiet")
            }
        }
    }

    @Test
    fun `scramble honors temperature`() {
        for (voice in SkinVoice.entries) {
            assertEquals(
                Skin.defaults(voice),
                Skin.scramble(voice, Random(1), temperature = 0f),
                "$voice: temperature 0 should return the default untouched",
            )
            val flat = Skin.scramble(voice, Random(1), temperature = 1f)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")
            assertEquals(
                Dsp.scrambleNear(Skin.defaults(voice), 1f, Random(2)),
                Skin.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 must match Dsp.scrambleNear's own flat-uniform contract",
            )
        }
    }

    @Test
    fun `unknown macros are ignored, known ones clamp`() {
        val snip = Skin.render(SkinVoice.KICK, mapOf("WOBBLE" to 0.5f, "TUNE" to 9f))
        assertTrue(snip.frameCount > 0)
    }

    @Test
    fun `renders are deterministic`() {
        val a = Skin.render(SkinVoice.SNARE)
        val b = Skin.render(SkinVoice.SNARE)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `renders are mpc-native`() {
        val snip = Skin.render(SkinVoice.HAT_OPEN)
        assertEquals(44_100, snip.sampleRate)
        assertEquals(1, snip.channels)
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through json`() {
        val patch = SkinPatch("Room Kick", SkinVoice.KICK, mapOf("TUNE" to 0.2f, "DECAY" to 0.8f))
        assertEquals(patch, SkinPatch.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `patch render equals direct render`() {
        val patch = SkinPatch("X", SkinVoice.SNARE, mapOf("SNAP" to 0.7f))
        assertTrue(patch.render().samples.contentEquals(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 0.7f)).samples))
    }

    @Test
    fun `patch validation refuses nonsense`() {
        assertFailsWith<IllegalArgumentException> { SkinPatch("", SkinVoice.KICK, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SkinPatch("x", SkinVoice.KICK, mapOf("CUTOFF" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { SkinPatch("x", SkinVoice.KICK, mapOf("TUNE" to 2f)) }
        assertFailsWith<com.snipsnap.json.JsonException> { SkinPatch.fromJsonText("""{"engine":"VELVET"}""") }
    }

    @Test
    fun `PUNCH at its factory default roughly preserves loudness through the full render`() {
        // Same contract ThumpTest proves for THUMP: PunchTest verifies
        // Punch.apply's own loudness match in isolation, this proves the
        // ordering survives contact with render()'s own Dsp.normalize /
        // Dsp.limitPeak either side of it.
        for (voice in SkinVoice.entries) {
            val off = Loudness.of(Skin.render(voice, mapOf("PUNCH" to 0f)))
            val default = Loudness.of(Skin.render(voice))
            assertTrue(
                kotlin.math.abs(default - off) < off * 0.25f,
                "$voice: PUNCH off vs its factory default should stay close: $off -> $default",
            )
        }
    }
}
