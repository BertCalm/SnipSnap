package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResinTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in ResinVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Resin.macrosFor(voice).associate { it.name to 0f },
                Resin.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Resin.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six shared macros`() {
        for (voice in ResinVoice.entries) {
            assertEquals(
                listOf("TUNE", "STACK", "CUTOFF", "CREAM", "CONTOUR", "DECAY"),
                Resin.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in ResinVoice.entries) {
            val a = Resin.render(voice, mapOf("CREAM" to 0.7f))
            val b = Resin.render(voice, mapOf("CREAM" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in ResinVoice.entries) {
            val a = Resin.scramble(voice, Random(11))
            val b = Resin.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Resin.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        // The same mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX carry
        // (VelvetTest has the full reasoning): render() must not be a
        // native-rate synthesize() finished the same way.
        for (voice in ResinVoice.entries) {
            val actual = Resin.render(voice)
            val direct = Resin.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.002, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }
}
