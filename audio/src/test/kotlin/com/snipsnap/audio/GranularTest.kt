package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GranularTest {

    private val rate = 44_100

    /** First half sings 220 Hz, second half 2 kHz — position is audible. */
    private fun twoTone(): Snip = Snip(
        FloatArray(rate) { i ->
            val hz = if (i < rate / 2) 220.0 else 2000.0
            (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
        },
        1, rate,
    )

    private fun mono(s: Snip): FloatArray =
        FloatArray(s.frameCount) { f -> (s.samples[f * 2] + s.samples[f * 2 + 1]) / 2f }

    private fun probe(samples: FloatArray, from: Int, to: Int, hz: Float): Float {
        val seg = samples.copyOfRange(from, to)
        return CaptureDoctor.goertzel(seg, seg.size, hz, rate)
    }

    @Test
    fun `a texture is a recipe - exact duration, stereo, deterministic per seed`() {
        val src = twoTone()
        val a = Granular.render(src, 2f, seed = 9)
        assertEquals(2 * rate, a.frameCount)
        assertEquals(2, a.channels)
        val b = Granular.render(src, 2f, seed = 9)
        assertTrue(a.samples.contentEquals(b.samples), "the same seed grows the same texture")
        val c = Granular.render(src, 2f, seed = 10)
        assertTrue(!a.samples.contentEquals(c.samples), "a different seed grows a different one")

        // The texture sits at the source's own level.
        val peak = a.samples.maxOf { Math.abs(it) }
        assertTrue(Math.abs(peak - 0.5f) < 0.01f, "level honest against the source peak: $peak")
    }

    @Test
    fun `a cloud renders where it is pointed and a scrub crawls`() {
        val src = twoTone()
        val low = mono(Granular.render(src, 2f, seed = 3, params = Granular.Params(positionStart = 0.25f, jitter = 0.02f)))
        assertTrue(
            probe(low, 0, low.size, 220f) > 5 * probe(low, 0, low.size, 2000f),
            "pointed at the low half, the cloud sings low",
        )
        val high = mono(Granular.render(src, 2f, seed = 3, params = Granular.Params(positionStart = 0.75f, jitter = 0.02f)))
        assertTrue(
            probe(high, 0, high.size, 2000f) > 5 * probe(high, 0, high.size, 220f),
            "pointed at the high half, it sings high",
        )

        val scrub = mono(
            Granular.render(
                src, 4f, seed = 3,
                params = Granular.Params(positionStart = 0f, positionEnd = 1f, jitter = 0.02f),
            ),
        )
        val quarter = scrub.size / 4
        assertTrue(
            probe(scrub, 0, quarter, 220f) > 5 * probe(scrub, 0, quarter, 2000f),
            "the scrub opens where the source opens",
        )
        assertTrue(
            probe(scrub, scrub.size - quarter, scrub.size, 2000f) > 5 * probe(scrub, scrub.size - quarter, scrub.size, 220f),
            "and closes where it closes",
        )
    }

    @Test
    fun `pitch spread widens a pure tone into a swarm`() {
        val tone = Snip(
            FloatArray(rate) { i -> (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / rate)).toFloat() },
            1, rate,
        )
        fun offOverIn(spread: Float): Float {
            val s = mono(
                Granular.render(
                    tone, 2f, seed = 4,
                    params = Granular.Params(positionStart = 0.5f, pitchSpreadSemis = spread),
                ),
            )
            val inBand = probe(s, 0, s.size, 1000f)
            val off = probe(s, 0, s.size, 840f) + probe(s, 0, s.size, 1190f)
            return off / inBand
        }
        val tight = offOverIn(0f)
        val wide = offOverIn(7f)
        assertTrue(wide > 3 * tight, "±7 semitones audibly widens the swarm: $tight -> $wide")
    }

    @Test
    fun `spray zero is center mono, spray one is a field`() {
        val src = twoTone()
        val centered = Granular.render(src, 1f, seed = 5, params = Granular.Params(spray = 0f))
        for (f in 0 until centered.frameCount) {
            assertEquals(centered.samples[f * 2], centered.samples[f * 2 + 1], "spray 0: channels identical")
        }
        val field = Granular.render(src, 1f, seed = 5, params = Granular.Params(spray = 1f))
        var differs = 0
        for (f in 0 until field.frameCount) {
            if (field.samples[f * 2] != field.samples[f * 2 + 1]) differs++
        }
        assertTrue(differs > field.frameCount / 4, "spray 1: the field is wide")
    }

    @Test
    fun `the engine refuses nonsense instead of rendering it`() {
        val src = twoTone()
        assertFailsWith<IllegalArgumentException> { Granular.render(src, 0f, 1) }
        assertFailsWith<IllegalArgumentException> { Granular.render(src, 1f, 1, Granular.Params(sizeSec = 5f)) }
        assertFailsWith<IllegalArgumentException> { Granular.render(src, 1f, 1, Granular.Params(density = 0f)) }
        assertFailsWith<IllegalArgumentException> { Granular.render(src, 1f, 1, Granular.Params(positionStart = 1.5f)) }
        assertFailsWith<IllegalArgumentException> { Granular.render(Snip(FloatArray(0), 1, rate), 1f, 1) }
    }
}
