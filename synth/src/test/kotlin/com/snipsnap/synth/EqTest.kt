package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqTest {

    private val snare = Thump.render(ThumpVoice.SNARE)

    @Test
    fun `the center detent is silence`() {
        val out = Eq.process(snare) // all defaults = 0.5 = flat
        assertTrue(out.samples.contentEquals(snare.samples), "flat EQ must be a copy")
    }

    @Test
    fun `BASS moves the low band both ways`() {
        val flat = FeatureExtractor.extract(snare)
        val boosted = FeatureExtractor.extract(Eq.process(snare, mapOf("BASS" to 1f)))
        val cut = FeatureExtractor.extract(Eq.process(snare, mapOf("BASS" to 0f)))
        assertTrue(boosted.lowRatio > flat.lowRatio * 1.3f, "BASS up: ${flat.lowRatio} -> ${boosted.lowRatio}")
        assertTrue(cut.lowRatio < flat.lowRatio * 0.7f, "BASS down: ${flat.lowRatio} -> ${cut.lowRatio}")
    }

    @Test
    fun `AIR moves the top both ways`() {
        val flat = FeatureExtractor.extract(snare)
        val open = FeatureExtractor.extract(Eq.process(snare, mapOf("AIR" to 1f)))
        val dark = FeatureExtractor.extract(Eq.process(snare, mapOf("AIR" to 0f)))
        assertTrue(open.centroidHz > flat.centroidHz * 1.1f, "AIR up: ${flat.centroidHz} -> ${open.centroidHz}")
        assertTrue(dark.centroidHz < flat.centroidHz * 0.85f, "AIR down: ${flat.centroidHz} -> ${dark.centroidHz}")
    }

    @Test
    fun `MID is audible and peak-matched`() {
        val scooped = Eq.process(snare, mapOf("MID" to 0f))
        assertTrue(abs(scooped.peak() - snare.peak()) < 0.05f, "tone, never loudness")
        var diff = 0.0
        for (i in snare.samples.indices) diff += abs((snare.samples[i] - scooped.samples[i]).toDouble())
        assertTrue(diff / snare.samples.size > 0.002, "a full mid scoop should be audible")
    }

    @Test
    fun `modest EQ keeps a kick a kick, and the chain carries eq first`() {
        val kick = Thump.render(ThumpVoice.KICK)
        assertEquals(
            DrumClass.KICK,
            Classifier.classify(Eq.process(kick, mapOf("BASS" to 0.7f, "AIR" to 0.6f))).drumClass,
        )

        val chain = FxChain(eq = mapOf("BASS" to 0.8f), spring = mapOf("MIX" to 0.3f))
        val back = FxChain.fromJsonText(chain.toJsonText())
        assertEquals(chain, back)
        assertTrue(back.process(kick).samples.contentEquals(chain.process(kick).samples))
    }

    @Test
    fun `EQ is deterministic, clean and stereo-safe on any roll`() {
        repeat(6) { seed ->
            val out = Eq.process(snare, Eq.scramble(Random(seed)))
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "roll $seed broke")
            assertTrue(abs(out.peak() - snare.peak()) < 0.05f, "roll $seed changed loudness")
        }
        assertTrue(
            Eq.process(snare, mapOf("BASS" to 1f)).samples.contentEquals(
                Eq.process(snare, mapOf("BASS" to 1f)).samples,
            ),
        )
        val stereo = Snip(FloatArray(snare.frameCount * 2) { snare.samples[it / 2] }, 2, 44_100)
        val out = Eq.process(stereo, mapOf("AIR" to 0.9f))
        assertEquals(2, out.channels)
        for (f in 0 until out.frameCount) {
            assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
        }
    }
}
