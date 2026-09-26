package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlintTest {

    /**
     * The all-ones corner's peak floor is lower than the other two cases'
     * because `Dsp.levelTo` normalizes to loudness (RMS of the loudest
     * 200 ms), not peak, and a denser window lands a lower peak at equal
     * loudness. KAZOO's trapezoid holds flat for KAZOO_FLAT of the cycle,
     * so its mean-square energy at a given peak is ~0.8 against REED's and
     * BOTTLE's 1/3 - about 2.4x - which alone predicts a ~1.55x lower peak
     * at matched loudness.
     *
     * Measured 2026-09-26, all macros = 1 (k fixed at 8, Task 1):
     *   REED   peak=0.6518
     *   BOTTLE peak=0.6334
     *   KAZOO  peak=0.4430
     *
     * Sweeping KAZOO's DECAY alone (TUNE at default) isolates DECAY, not
     * TUNE, as the driver - a long decay keeps the loudest-RMS window near
     * full level, raising measured loudness and so lowering the normalized
     * peak further:
     *   DECAY=0.0 -> peak=0.99
     *   DECAY=0.3 -> peak=0.936
     *   DECAY=0.5 -> peak=0.742
     *   DECAY=0.7 -> peak=0.590
     *   DECAY=0.8 -> peak=0.530
     *   DECAY=0.9 -> peak=0.480  (crosses below 0.5)
     *   DECAY=1.0 -> peak=0.439
     *
     * 0.35, not a tighter floor: Task 5's BLOOM changes KAZOO's crest
     * factor again, and 0.443 against 0.40 is only 10% margin. 0.35 still
     * catches a genuinely broken render (near zero), which is all this
     * assertion is for.
     */
    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in GlintVoice.entries) {
            val cases = listOf(
                emptyMap<String, Float>() to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 0f } to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 1f } to 0.35f,
            )
            for ((macros, peakFloor) in cases) {
                val snip = Glint.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > peakFloor, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six macros`() {
        for (voice in GlintVoice.entries) {
            assertEquals(
                listOf("TUNE", "PEAK", "FOLLOW", "BODY", "BLOOM", "DECAY"),
                Glint.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every window ends at exactly zero - the whole engine rests on this`() {
        for (voice in GlintVoice.entries) {
            assertEquals(0f, Glint.windowAt(voice, 1f), 1e-6f, "$voice window must close the cycle")
            assertTrue(Glint.windowAt(voice, 0.5f) > 0f, "$voice window must be open mid-cycle")
        }
    }

    @Test
    fun `declared window means match numeric integration`() {
        // BODY subtracts these to kill DC. A wrong constant is a DC offset
        // that only shows up after normalization, so pin them here.
        for (voice in GlintVoice.entries) {
            var sum = 0.0
            val n = 100_000
            for (i in 0 until n) sum += Glint.windowAt(voice, i.toFloat() / n)
            val measured = (sum / n).toFloat()
            assertEquals(measured, Glint.windowMean(voice), 1e-3f, "$voice window mean")
        }
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Glint.frequencyFor(GlintVoice.REED, i / 100f))
        assertEquals(Glint.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(Glint.rootHz(GlintVoice.REED), Glint.frequencyFor(GlintVoice.REED, 0f), 1e-3f)
        assertEquals(Glint.rootHz(GlintVoice.REED) * 4f, Glint.frequencyFor(GlintVoice.REED, 1f), 1e-2f)
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in GlintVoice.entries) {
            val short = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(long.decayMs > short.decayMs * 1.5f, "$voice DECAY should stretch the note: ${short.decayMs} -> ${long.decayMs}")
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.render(voice, mapOf("PEAK" to 0.7f))
            val b = Glint.render(voice, mapOf("PEAK" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.scramble(voice, Random(11))
            val b = Glint.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Glint.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

}
