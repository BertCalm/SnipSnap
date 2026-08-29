package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TapeWearTest {

    private val rate = 44_100

    /** Loud hits on a quiet bed — the shape dropout protection must read. */
    private fun beat(seconds: Float = 30f, hitEvery: Float = 0.5f, hitLen: Float = 0.06f): Snip {
        val rnd = kotlin.random.Random(11)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val tInHit = (i / rate.toFloat()) % hitEvery
            val amp = if (tInHit < hitLen) 0.9f else 0.04f
            out[i] = (rnd.nextFloat() * 2 - 1) * amp
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a new tape is bit-identical - w zero is a true passthrough`() {
        val src = beat(2f)
        val out = TapeWear.process(src, 0f, seed = 42)
        assertTrue(out.samples.contentEquals(src.samples), "w=0 must not touch a sample")
        assertEquals(src.sampleRate, out.sampleRate)
    }

    @Test
    fun `wear is deterministic and duration-preserving`() {
        val src = beat(3f)
        val a = TapeWear.process(src, 0.7f, seed = 5)
        val b = TapeWear.process(src, 0.7f, seed = 5)
        assertTrue(a.samples.contentEquals(b.samples), "same tape, same scratches")
        assertEquals(src.frameCount, a.frameCount, "wear never changes the length")
        assertTrue(a.samples.all { it.isFinite() })
        assertTrue(!a.samples.contentEquals(src.samples), "worn is not pristine")
        val other = TapeWear.process(src, 0.7f, seed = 6)
        assertTrue(!a.samples.contentEquals(other.samples), "a different tape wears differently")
    }

    @Test
    fun `full wear renders inside every cap`() {
        // Hiss: a worn silent tape breathes, but never above -48 dBFS.
        val silence = Snip(FloatArray(2 * rate), 1, rate)
        val breathed = TapeWear.process(silence, 1f, seed = 3)
        val hissCap = Math.pow(10.0, TapeWear.MAX_HISS_DB / 20.0).toFloat()
        val peak = breathed.samples.maxOf { abs(it) }
        assertTrue(peak > 0f, "a worn tape is not perfectly silent")
        assertTrue(peak <= hissCap * 1.001f, "hiss capped at ${TapeWear.MAX_HISS_DB} dBFS, peaked at $peak")

        // Flutter: +/-6 cents wobbles the note, it does not detune it.
        val tone = Snip(
            FloatArray(2 * rate) { i -> (0.6 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat() },
            1, rate,
        )
        val worn = TapeWear.process(tone, 1f, seed = 3)
        val heard = Pitch.detect(worn)
        assertTrue(heard != null && abs(heard.hz - 440f) < 3f, "still an A at full wear: ${heard?.hz}")

        // The parameter curves honour their caps - override included.
        assertTrue(abs(TapeWear.flutterCents(1f) - TapeWear.MAX_FLUTTER_CENTS) < 1e-5f)
        assertTrue(TapeWear.flutterCents(0.5f) < TapeWear.flutterCents(1f))
        assertEquals(TapeWear.MIN_SHELF_HZ, TapeWear.shelfHz(1f))
        assertEquals(TapeWear.MIN_SHELF_HZ, TapeWear.shelfHz(TapeWear.MAX_OVERRIDE_W), "the shelf floor holds even past the ceiling")
        assertTrue(abs(TapeWear.shelfGain(1f) - TapeWear.SHELF_FLOOR_GAIN) < 1e-5f)
        assertTrue(abs(TapeWear.hissAmp(1f) - hissCap) < 1e-7f)
    }

    @Test
    fun `dropouts land in the pockets, never on a strong hit`() {
        val src = beat(30f)
        var peak = 0f
        for (s in src.samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        val spans = TapeWear.dropoutSpans(src, 1f, seed = 7)
        assertTrue(spans.isNotEmpty(), "thirty worn seconds drop out somewhere")
        for (span in spans) {
            var localMax = 0f
            for (f in span) {
                val a = abs(src.samples[f])
                if (a > localMax) localMax = a
            }
            assertTrue(
                localMax < TapeWear.HIT_PROTECT * peak,
                "a dropout at ${span.first} sits on audio at $localMax of peak $peak - that's a hit",
            )
        }
    }

    @Test
    fun `more mileage, more character - and nonsense wear is refused`() {
        val src = beat(3f)
        fun rmsDiff(x: Snip): Double {
            var acc = 0.0
            for (i in src.samples.indices) {
                val d = x.samples[i] - src.samples[i]
                acc += d * d.toDouble()
            }
            return Math.sqrt(acc / src.samples.size)
        }
        val light = rmsDiff(TapeWear.process(src, 0.25f, seed = 9))
        val heavy = rmsDiff(TapeWear.process(src, 1f, seed = 9))
        assertTrue(light > 0 && heavy > light, "wear deepens with w: $light vs $heavy")

        assertFailsWith<IllegalArgumentException> { TapeWear.process(src, -0.1f) }
        assertFailsWith<IllegalArgumentException> { TapeWear.process(src, TapeWear.MAX_OVERRIDE_W + 0.1f) }
        assertFailsWith<IllegalArgumentException> { TapeWear.process(src, Float.NaN) }
    }
}
