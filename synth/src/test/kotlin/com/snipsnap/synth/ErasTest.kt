package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErasTest {

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Float = 1.0f, amp: Float = 0.6f) = Snip(
        FloatArray((seconds * rate).toInt()) { i ->
            (amp * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
        },
        1, rate,
    )

    private fun noisyHit(seconds: Float = 0.6f): Snip {
        val rnd = kotlin.random.Random(7)
        return Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                ((rnd.nextFloat() * 2 - 1) * Math.exp(-i / (0.1 * rate))).toFloat() * 0.7f
            },
            1, rate,
        )
    }

    @Test
    fun `every era is deterministic, duration-preserving, and finite`() {
        val src = noisyHit()
        for (era in Eras.names) {
            val a = Eras.process(era, src)
            val b = Eras.process(era, src)
            assertTrue(a.samples.contentEquals(b.samples), "$era: same input, same bytes")
            assertEquals(rate, a.sampleRate, "$era: output at the source rate")
            assertTrue(
                abs(a.frameCount - src.frameCount) <= src.frameCount / 100 + 4,
                "$era: duration preserved (${a.frameCount} vs ${src.frameCount})",
            )
            assertTrue(a.samples.all { it.isFinite() }, "$era: finite output")
            assertTrue(!a.samples.contentEquals(src.samples), "$era: actually did something")
        }
    }

    @Test
    fun `sp1200 really is twelve bits at a low rate`() {
        // A slow ramp exposes the quantizer: 12 bits can hold at most 4096
        // distinct levels, and truncation means the grid is exact.
        val ramp = Snip(FloatArray(40_000) { i -> i / 40_000f * 2 - 1 }, 1, rate)
        val aged = Eras.process("sp1200", ramp)
        val distinct = aged.samples.map { Math.round(it * 2048f) }.distinct().size
        assertTrue(distinct <= 4097, "12-bit output holds <= 4096 levels, saw $distinct")
        // And every sample sits on the 1/2048 grid.
        assertTrue(
            aged.samples.all { abs(it * 2048f - Math.round(it * 2048f)) < 1e-3f },
            "samples sit on the 12-bit grid",
        )
    }

    @Test
    fun `phone band-limits to the telephone band`() {
        // The passband tops out at 3.4 kHz, so energy above 2 kHz shrinks
        // hard but not to zero - the 2..3.4 kHz slice legitimately survives.
        // The sharper claim is the rolloff: 85% of the energy must now live
        // below the band edge's neighbourhood.
        val bright = noisyHit()
        val before = FeatureExtractor.extract(bright)
        val after = FeatureExtractor.extract(Eras.process("phone", bright))
        assertTrue(after.highRatio < before.highRatio * 0.45f, "highs shrank: ${before.highRatio} -> ${after.highRatio}")
        assertTrue(after.rolloffHz < 4_500f, "the spectrum now lives in the phone band: rolloff ${after.rolloffHz} Hz")
    }

    @Test
    fun `mpc60 rolls the top and coarsens the grid without killing the body`() {
        val bright = noisyHit()
        val aged = Eras.process("mpc60", bright)
        val before = FeatureExtractor.extract(bright)
        val after = FeatureExtractor.extract(aged)
        assertTrue(after.highRatio < before.highRatio, "top end reduced")
        assertTrue(after.peak > 0.2f, "the hit still hits")
    }

    @Test
    fun `tape wobbles the pitch but keeps the note`() {
        val note = tone(440.0, seconds = 1.5f)
        val aged = Eras.process("tape", note)
        val heard = Pitch.detect(aged)
        assertTrue(heard != null && abs(heard.hz - 440f) < 8f, "still an A: ${heard?.hz}")
        assertTrue(!aged.samples.contentEquals(note.samples), "but not the same tape it was")
    }

    @Test
    fun `amount interpolates toward transparent and recipes record the era`() {
        val src = noisyHit()
        val subtle = Eras.process("sp1200", src, amount = 0.2f)
        val full = Eras.process("sp1200", src, amount = 1f)
        fun rmsDiff(x: Snip): Double {
            var acc = 0.0
            val n = minOf(x.samples.size, src.samples.size)
            for (i in 0 until n) acc += (x.samples[i] - src.samples[i]).let { it * it.toDouble() }
            return Math.sqrt(acc / n)
        }
        assertTrue(rmsDiff(subtle) < rmsDiff(full), "less amount, less character")

        val aged = Eras.apply("tape", src, 0.7f)
        assertEquals("tape", (aged.recipe.entries.getValue("era") as JsonValue.Str).value)
        assertTrue(abs((aged.recipe.entries.getValue("amount") as JsonValue.Num).value - 0.7) < 1e-6)

        assertFailsWith<IllegalArgumentException> { Eras.process("victrola", src) }
        assertFailsWith<IllegalArgumentException> { Eras.process("tape", src, amount = 0f) }
    }
}
