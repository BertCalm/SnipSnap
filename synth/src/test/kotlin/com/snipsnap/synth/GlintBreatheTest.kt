package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
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
        val diffs = mutableListOf<Pair<String, Float>>()
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0.2f, 0.5f, 0.8f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to bloom, "TUNE" to tune))
                    val e = Keys.seamError(held.audio, held.loopStart)
                    worst = maxOf(worst, e)
                    assertTrue(e < Keys.MAX_SEAM_ERROR, "$voice BLOOM $bloom TUNE $tune seam $e")
                    // The second breath is the first, sample for sample, from its 64th frame on:
                    // the decimator reaches 48 oversampled samples (about 12 frames) back, so by
                    // then it has forgotten the onset.
                    val len = held.audio.size - held.loopStart
                    var maxDiff = 0f
                    for (j in 64 until len) {
                        maxDiff = maxOf(maxDiff, abs(held.audio[held.loopStart - len + j] - held.audio[held.loopStart + j]))
                    }
                    diffs += "$voice BLOOM $bloom TUNE $tune" to maxDiff
                }
            }
        }
        val worstDiff = diffs.maxOf { it.second }
        println(
            "GLINT breathe worst seam over 4 voices x 3 BLOOMs x 2 TUNEs: $worst (bar ${Keys.MAX_SEAM_ERROR}), " +
                "worst sample difference between the two breaths from frame 64: $worstDiff",
        )
        for ((what, d) in diffs) assertEquals(0f, d, "$what: the second breath differs from the first")
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
            // And in colour: the energy check cannot see a k breath, which moves the spectrum and
            // not the level. The first and third quarters are where a breath would be at its two
            // extremes (see the breathing pad test below).
            val colour = BandDistance.whole(loop.copyOfRange(0, q), loop.copyOfRange(2 * q, 3 * q), Dsp.RATE)
            println("GLINT breathe still pad $voice, first vs third quarter: ${fixed(colour)} (bar 0.05)")
            assertTrue(colour < 0.05, "$voice still pad changes colour: first vs third quarter $colour")
        }
    }

    @Test
    fun `a breathing pad moves - its quarters differ in colour`() {
        // The first quarter swings to the breath's brightest point, the third to its darkest.
        for (voice in GlintVoice.entries) {
            val d = breathDistance(voice, mapOf("BLOOM" to 1f))
            println("GLINT breathe $voice BLOOM 1, first vs third quarter: ${fixed(d)} (bar 0.1)")
            assertTrue(d > 0.1, "$voice: the breath does not move the colour: $d")
        }
    }

    /**
     * Spec §3: the breath is signed like the approach, so it begins toward the side the onset
     * began on. BLOOM above centre falls into PEAK from above and breathes up first; below
     * centre it rises into PEAK and breathes down first. The breath's first quarter is sin > 0,
     * so the swing at its crest (BREATHE_SHARE, and on BRASS times |s|: e(t) of its level) must
     * put the burst above (up) or below (down) where PEAK rests. VOWEL is read on F2, the vowel
     * line's brightness axis: F1 falls toward both neighbours of AH.
     */
    @Test
    fun `each breath begins toward the side its approach began on`() {
        for (voice in GlintVoice.entries) {
            for ((bloom, up) in listOf(0.8f to true, 0.2f to false)) {
                val m = Glint.defaults(voice) + ("BLOOM" to bloom)
                val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
                val path = GlintPath.of(voice, m, f0)
                val swing = GlintHeld.BREATHE_SHARE * (if (voice == GlintVoice.BRASS) abs(path.bloom) else 1f)
                val k = FloatArray(2)
                path.breathRatios(0f, k)
                val rest = k.copyOf()
                path.breathRatios(swing, k)
                val burst = if (voice == GlintVoice.VOWEL) 1 else 0
                println(
                    "GLINT breathe $voice BLOOM $bloom: ratio ${fixed(rest[burst].toDouble())} at rest, ${fixed(k[burst].toDouble())} at the crest" +
                        (if (voice == GlintVoice.VOWEL) " (F2 ${fixed((rest[burst] * f0).toDouble())} Hz to ${fixed((k[burst] * f0).toDouble())} Hz)" else ""),
                )
                if (up) {
                    assertTrue(k[burst] > rest[burst], "$voice BLOOM $bloom should breathe up first: ${k[burst]} at the crest against ${rest[burst]} at rest")
                } else {
                    assertTrue(k[burst] < rest[burst], "$voice BLOOM $bloom should breathe down first: ${k[burst]} at the crest against ${rest[burst]} at rest")
                }
            }
        }
    }

    /**
     * Spec §3: BRASS breathes its amplitude around BRASS_REST and its k follows through e(t),
     * loudness and brightness together. BODY is 0 because the second burst stays at full level in
     * the held render and would dilute both readings. The crest (sin = +1) is a quarter of the way
     * round the loop and the trough three quarters. BLOOM 1 is louder = brighter; BLOOM 0 is
     * louder = darker, a muted strike.
     */
    @Test
    fun `BRASS breathes its level, and its colour follows it`() {
        fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)
        fun centroid(x: FloatArray) = FeatureExtractor.extract(Snip(x, channels = 1, sampleRate = Dsp.RATE)).centroidHz
        for ((bloom, louderIsBrighter) in listOf(1f to true, 0f to false)) {
            val held = GlintHeld.render(GlintVoice.BRASS, mapOf("BLOOM" to bloom, "BODY" to 0f))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            val crest = loop.copyOfRange(loop.size / 4 - 2048, loop.size / 4 + 2048)
            val trough = loop.copyOfRange(3 * loop.size / 4 - 2048, 3 * loop.size / 4 + 2048)
            val rmsCrest = rms(crest)
            val rmsTrough = rms(trough)
            val centroidCrest = centroid(crest)
            val centroidTrough = centroid(trough)
            println(
                "GLINT breathe BRASS BLOOM $bloom: rms crest ${fixed(rmsCrest)}, trough ${fixed(rmsTrough)} " +
                    "(${fixed(rmsCrest / rmsTrough)}x); centroid crest ${fixed(centroidCrest.toDouble())} Hz, " +
                    "trough ${fixed(centroidTrough.toDouble())} Hz (${fixed((centroidCrest / centroidTrough).toDouble())}x)",
            )
            assertTrue(rmsCrest > 1.3 * rmsTrough, "BRASS BLOOM $bloom does not breathe its level: rms crest $rmsCrest, trough $rmsTrough")
            if (louderIsBrighter) {
                assertTrue(centroidCrest > 1.1f * centroidTrough, "BRASS BLOOM $bloom: the louder crest should be brighter: centroid $centroidCrest Hz against $centroidTrough Hz")
            } else {
                assertTrue(centroidCrest < centroidTrough / 1.1f, "BRASS BLOOM $bloom: the louder crest should be darker: centroid $centroidCrest Hz against $centroidTrough Hz")
            }
        }
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
        // A still held STEP (BLOOM 0.5) at a PEAK whose ratio is off every whole
        // harmonic and outside the snap band: PEAK 0.02 sits between K_MIN and
        // SNAP_FLOOR, PEAK 0.7 (kBase about 16.28) above SNAP_CEILING. The
        // render's own `breathRatios` must keep the free landing whichever side
        // of it the breath swings, however small - only `stepLadder` pinned
        // that outside the band before.
        val voice = GlintVoice.STEP
        for ((peak, belowFloor) in listOf(0.02f to true, 0.7f to false)) {
            val macros = Glint.defaults(voice) + mapOf("PEAK" to peak, "TUNE" to 0.5f, "BLOOM" to 0.5f)
            val f0 = Glint.frequencyFor(voice, macros.getValue("TUNE"))
            val kBase = Glint.ratioFor(voice, macros.getValue("TUNE"), macros.getValue("PEAK"), macros.getValue("FOLLOW"))
            if (belowFloor) {
                assertTrue(
                    kBase < Glint.SNAP_FLOOR && kBase != Math.round(kBase).toFloat(),
                    "test setup expected an unrounded kBase below the snap floor at PEAK $peak, got $kBase",
                )
            } else {
                assertTrue(
                    kBase > Glint.SNAP_CEILING && kBase != Math.round(kBase).toFloat(),
                    "test setup expected an unrounded kBase above the snap ceiling at PEAK $peak, got $kBase",
                )
            }
            val path = GlintPath.of(voice, macros, f0)
            val k = FloatArray(2)
            for (swing in listOf(0.25f, -0.25f, 1e-6f)) {
                path.breathRatios(swing, k)
                assertEquals(kBase, k[0], 1e-6f, "PEAK $peak: swing $swing should rest on PEAK ($kBase), not on ${Math.round(kBase)}")
            }
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
        // The breath's extremes are whole harmonics, and not the landing's own: up it
        // plays between PEAK and the path's start, down it plays under PEAK.
        for (swing in listOf(0.25f, -0.25f)) {
            path.breathRatios(swing, k)
            assertTrue(k[0] == Math.round(k[0]).toFloat(), "swing $swing should play a whole harmonic, got ${k[0]}")
            assertTrue(k[0] != Math.round(kBase).toFloat(), "swing $swing should be off the landing's harmonic ${Math.round(kBase)}, got ${k[0]}")
            if (swing > 0f) {
                assertTrue(
                    k[0] > kBase && k[0] < Glint.startRatio(kBase, 1f),
                    "swing $swing should sit between PEAK ($kBase) and the path's start, got ${k[0]}",
                )
            } else {
                assertTrue(k[0] < kBase, "swing $swing should sit under PEAK ($kBase), got ${k[0]}")
            }
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
            GlintHeld.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "PEAK" to 0.1f)) { ++asked > 3 }
        }
        // The render asks at frames 0, 4096, 8192 and 12288 of its oversampled run, and stops at the fourth.
        assertEquals(4, asked)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    /**
     * `BandDistance.whole` between the first and third quarters of a held loop - the two
     * extremes of one breath - once each is known to sound: BandDistance refuses a silent
     * span outright, so these checks name which quarter it was.
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
