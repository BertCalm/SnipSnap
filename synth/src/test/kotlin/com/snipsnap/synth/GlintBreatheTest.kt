package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
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
        var worst = 0.0
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0.2f, 0.5f, 0.8f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to bloom, "TUNE" to tune))
                    val e = Keys.seamError(held.audio, held.loopStart)
                    worst = maxOf(worst, e)
                    assertTrue(e < Keys.MAX_SEAM_ERROR, "$voice BLOOM $bloom TUNE $tune seam $e")
                }
            }
        }
        println("GLINT breathe worst seam over 4 voices x 3 BLOOMs x 2 TUNEs: $worst (bar ${Keys.MAX_SEAM_ERROR})")
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
        // The first quarter swings to the breath's brightest point, the third to its darkest.
        val d = breathDistance(GlintVoice.SWEEP, mapOf("BLOOM" to 1f))
        assertTrue(d > 0.1, "the breath does not move the colour: $d")
    }

    @Test
    fun `VOWEL's breath depth grows with BLOOM's distance from centre`() {
        // PEAK 0.5 is AH, mid-line. BLOOM 0.6, 0.8 and 1 are s = 0.2, 0.6 and 1, and the
        // breath swings the position by BREATHE_SHARE * 4 * s = 0.2, 0.6 and 1 vowels. Taken
        // from the one-shot's start, which the line's ends clamp, s = 0.6 and 1 swung alike.
        val blooms = listOf(0.6f, 0.8f, 1f)
        val d = blooms.map { breathDistance(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "TUNE" to 0f, "BLOOM" to it)) }
        println("GLINT breathe VOWEL first vs third quarter, BLOOM 0.6 / 0.8 / 1.0: " + d.joinToString(" / ") { fixed(it) })
        assertTrue(d[0] < d[1] && d[1] < d[2], "VOWEL's breath does not deepen with BLOOM: $d")
    }

    @Test
    fun `a SWEEP breath keeps its depth at high PEAK`() {
        // BLOOM 1 starts 4x above PEAK. At PEAK 0.9 (kBase about 29.6) the one-shot's start is
        // cut to K_MAX, and a breath taken from it swung k about 28 to 32 where spec §3's
        // swings about 21 to 40.
        val mid = breathDistance(GlintVoice.SWEEP, mapOf("BLOOM" to 1f, "PEAK" to 0.45f))
        val high = breathDistance(GlintVoice.SWEEP, mapOf("BLOOM" to 1f, "PEAK" to 0.9f))
        println("GLINT breathe SWEEP BLOOM 1 first vs third quarter, PEAK 0.45: ${fixed(mid)}, PEAK 0.9: ${fixed(high)}")
        assertTrue(high >= 0.5 * mid, "the breath at PEAK 0.9 (${fixed(high)}) is under half its depth at PEAK 0.45 (${fixed(mid)})")
    }

    @Test
    fun `every voice's loop sits at the melodic loudness target, whatever its onset`() {
        // BRASS's onset is a fortepiano accent twice as loud as its loop: levelling the whole
        // file by it left the loop 1.4 dB (15.2%) under the target at BRASS's default BLOOM,
        // a hair past the bar, and 2 dB (20.5%) under at BLOOM 0.5, where the loop is a still
        // pad - so both are checked.
        val target = Dsp.MELODIC_LOUDNESS_TARGET
        val fixtures = listOf("default macros" to emptyMap<String, Float>(), "BLOOM 0.5" to mapOf("BLOOM" to 0.5f))
        val loudness = fixtures.flatMap { (label, macros) ->
            GlintVoice.entries.map { voice ->
                val held = GlintHeld.render(voice, macros)
                val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
                Triple("$voice at $label", Loudness.of(Snip(loop, channels = 1, sampleRate = Dsp.RATE)), held.audio.maxOf { abs(it) })
            }
        }
        for ((what, loud, filePeak) in loudness) {
            println("GLINT breathe $what: loop loudness ${fixed(loud.toDouble())} against target ${fixed(target.toDouble())}, file peak ${fixed(filePeak.toDouble())}")
        }
        for ((what, loud, _) in loudness) {
            assertTrue(abs(loud - target) / target < 0.15f, "$what: loop loudness $loud is not within 15% of $target")
        }
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
        // and SNAP_FLOOR: the off-the-rung read `ratios(x, -1, out)` and the
        // render's own `breathRatios` must both keep the free landing whichever
        // side of it the breath swings, however small - only `stepLadder`
        // pinned that below the floor before.
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
            path.breathRatios(x, k)
            assertEquals(kBase, k[0], 1e-6f, "swing $x should rest on PEAK ($kBase), not on ${Math.round(kBase)}")
        }
    }

    @Test
    fun `held STEP's breath plays whole harmonics around an unrounded PEAK`() {
        // PEAK 0.7 puts kBase near 16.28: off every harmonic, above the snap ceiling.
        val voice = GlintVoice.STEP
        val macros = Glint.defaults(voice) + mapOf("PEAK" to 0.7f, "TUNE" to 0.5f, "BLOOM" to 1f)
        val f0 = Glint.frequencyFor(voice, macros.getValue("TUNE"))
        val kBase = Glint.ratioFor(voice, macros.getValue("TUNE"), macros.getValue("PEAK"), macros.getValue("FOLLOW"))
        assertTrue(
            kBase > Glint.SNAP_CEILING && kBase != Math.round(kBase).toFloat(),
            "test setup expected an unrounded kBase above the snap ceiling, got $kBase",
        )
        val path = GlintPath.of(voice, macros, f0)
        val k = FloatArray(2)
        // The breath's extremes are whole harmonics, and not the landing's own.
        for (swing in listOf(0.25f, -0.25f)) {
            path.breathRatios(swing, k)
            assertTrue(k[0] == Math.round(k[0]).toFloat(), "swing $swing should play a whole harmonic, got ${k[0]}")
            assertTrue(k[0] != Math.round(kBase).toFloat(), "swing $swing should be off the landing's harmonic ${Math.round(kBase)}, got ${k[0]}")
        }
        // A breath too small to leave the landing's harmonic rests on PEAK itself.
        path.breathRatios(1e-3f, k)
        assertEquals(kBase, k[0], 1e-6f, "a tiny swing should rest on PEAK ($kBase), not on ${Math.round(kBase)}")
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

    /**
     * `BandDistance.whole` between the first and third quarters of a held loop - the two
     * extremes of one breath - once each is known to sound: BandDistance maps silence to a
     * zero band vector, so a silent quarter reads a full unit away from a sounding one.
     */
    private fun breathDistance(voice: GlintVoice, macros: Map<String, Float>): Double {
        val held = GlintHeld.render(voice, macros)
        val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
        val q = loop.size / 4
        val first = loop.copyOfRange(0, q)
        val third = loop.copyOfRange(2 * q, 3 * q)
        assertTrue(first.maxOf { abs(it) } > 0.05f, "$voice $macros: the first quarter is silent")
        assertTrue(third.maxOf { abs(it) } > 0.05f, "$voice $macros: the third quarter is silent")
        return BandDistance.whole(first, third, Dsp.RATE)
    }

    /** Four decimals, whatever the machine's locale. */
    private fun fixed(v: Double) = "%.4f".format(java.util.Locale.ROOT, v)
}
