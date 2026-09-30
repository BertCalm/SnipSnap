package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §3. */
class GlintBreatheTest {

    @Test
    fun `the breath plan is whole cycles in whole frames, in tune`() {
        for (hz in listOf(110f, 155.56f, 220f, 440f, 880f)) {
            val p = GlintHeld.breathPlan(hz)
            val cents = abs(1200 * log2(p.f0 / hz))
            assertTrue(cents < 0.01, "$hz Hz plays ${p.f0} Hz, $cents cents off")
            val seconds = p.loopFrames.toDouble() / Dsp.RATE
            assertTrue(abs(seconds - GlintHeld.BREATHE_SECONDS) <= 1.0 / hz, "$hz Hz loop is $seconds s")
        }
    }

    @Test
    fun `every voice's breath closes - the seam is below the organ's bar`() {
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0.2f, 0.5f, 0.8f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to bloom, "TUNE" to tune))
                    val e = Keys.seamError(held.audio, held.loopStart)
                    assertTrue(e < Keys.MAX_SEAM_ERROR, "$voice BLOOM $bloom TUNE $tune seam $e")
                }
            }
        }
    }

    @Test
    fun `the loop is one breath long`() {
        for (voice in GlintVoice.entries) {
            val m = Glint.defaults(voice)
            val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
            val held = GlintHeld.render(voice, m)
            assertEquals(plan.loopFrames, held.audio.size - held.loopStart, "$voice")
        }
    }

    @Test
    fun `a still pad at BLOOM 0 point 5 on the lowest zone is steady, seamless and audible`() {
        for (voice in GlintVoice.entries) {
            val held = GlintHeld.render(voice, mapOf("BLOOM" to 0.5f, "TUNE" to 0f))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR)
            val peak = loop.maxOf { abs(it) }
            assertTrue(peak > 0.3f, "$voice still pad is too quiet: $peak")
            // Steady: the first and last quarter of the breath carry the same energy.
            val q = loop.size / 4
            val a = loop.copyOfRange(0, q).sumOf { (it * it).toDouble() }
            val b = loop.copyOfRange(loop.size - q, loop.size).sumOf { (it * it).toDouble() }
            // 3%, not 1%: a quarter of the breath is not a whole number of cycles,
            // and a cycle's energy sits at its front, so the two quarters' partial
            // cycles alone differ by up to about 1% at 110 Hz.
            assertTrue(abs(a - b) / a < 0.03, "$voice still pad is not steady: $a vs $b")
        }
    }

    @Test
    fun `a breathing pad moves - its quarters differ in colour`() {
        val held = GlintHeld.render(GlintVoice.SWEEP, mapOf("BLOOM" to 1f))
        val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
        val q = loop.size / 4
        // The first quarter swings to the breath's brightest point, the third to its darkest.
        val first = loop.copyOfRange(0, q)
        val third = loop.copyOfRange(2 * q, 3 * q)
        // BandDistance maps silence to a zero band vector, so a silent quarter
        // would read as a full unit away from a sounding one: both are heard first.
        assertTrue(first.maxOf { abs(it) } > 0.05f, "the first quarter is silent")
        assertTrue(third.maxOf { abs(it) } > 0.05f, "the third quarter is silent")
        val d = BandDistance.whole(first, third, Dsp.RATE)
        assertTrue(d > 0.1, "the breath does not move the colour: $d")
    }

    @Test
    fun `held STEP at its longest ladder stays bounded`() {
        // PEAK 0.54: kBase = 10, kBase * 4 = K_MAX - the most rungs STEP can have.
        val held = GlintHeld.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "PEAK" to 0.54f))
        assertTrue(held.audio.size < 12 * Dsp.RATE, "zone is ${held.audio.size / Dsp.RATE.toFloat()} s")
        assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `held STEP below the snap floor rests on PEAK, not on its nearest whole harmonic`() {
        // A still held STEP (BLOOM 0.5) at a PEAK whose ratio sits between K_MIN
        // and SNAP_FLOOR: the breath's `ratios(x, -1, out)` must keep the free
        // landing whichever side of it x swings, however small - only
        // `stepLadder` pinned that below the floor before.
        val voice = GlintVoice.STEP
        val macros = Glint.defaults(voice) + mapOf("PEAK" to 0.02f, "TUNE" to 0.5f, "BLOOM" to 0.5f)
        val f0 = Glint.frequencyFor(voice, macros.getValue("TUNE"))
        val kBase = Glint.ratioFor(voice, macros.getValue("TUNE"), macros.getValue("PEAK"), macros.getValue("FOLLOW"))
        assertTrue(
            kBase < Glint.SNAP_FLOOR && kBase != Math.round(kBase).toFloat(),
            "test setup expected an unrounded kBase below the snap floor, got $kBase",
        )
        val path = GlintPath.of(voice, macros, f0)
        val k = FloatArray(2)
        for (x in listOf(0.25f, -0.25f, 1e-6f)) {
            path.ratios(x, -1, k)
            assertEquals(kBase, k[0], 1e-6f, "x = $x should rest on PEAK ($kBase), not on ${Math.round(kBase)}")
        }
    }

    @Test
    fun `held renders are deterministic`() {
        val a = GlintHeld.render(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f))
        val b = GlintHeld.render(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f))
        assertEquals(a.loopStart, b.loopStart)
        assertTrue(a.audio.contentEquals(b.audio))
    }

    @Test
    fun `a held render nobody wants any more stops`() {
        var asked = 0
        val t0 = System.nanoTime()
        assertFailsWith<java.util.concurrent.CancellationException> {
            GlintHeld.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "PEAK" to 0.1f)) { ++asked > 0 }
        }
        assertTrue(asked >= 1)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }
}
