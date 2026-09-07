package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WobbleTest {

    private val rate = 44_100

    /** Two seconds of white noise: brightness is then the filter's alone. */
    private fun noise(channels: Int = 1): Snip {
        val rnd = java.util.Random(11)
        return Snip(FloatArray(2 * rate * channels) { (rnd.nextFloat() * 2f - 1f) * 0.5f }, channels, rate)
    }

    /** Zero-crossing rate per 10 ms frame — brightness, for filtered noise. */
    private fun brightness(s: Snip): DoubleArray {
        val win = rate / 100
        return DoubleArray(s.frameCount / win) { f ->
            var crossings = 0
            for (i in f * win + 1 until (f + 1) * win) {
                if ((s.samples[i] >= 0f) != (s.samples[i - 1] >= 0f)) crossings++
            }
            crossings.toDouble()
        }
    }

    /** The period of the brightness swing, seconds: the median gap between its peaks. */
    private fun sweepPeriod(s: Snip): Double {
        val b = brightness(s)
        // Smooth over three frames, then find local maxima above the midline.
        val sm = DoubleArray(b.size) { i -> (b[(i - 1).coerceAtLeast(0)] + b[i] + b[(i + 1).coerceAtMost(b.size - 1)]) / 3 }
        val mid = (sm.max() + sm.min()) / 2
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < sm.size - 1) {
            if (sm[i] > mid && sm[i] >= sm[i - 1] && sm[i] >= sm[i + 1]) {
                peaks.add(i)
                i += 5 // one peak per crest
            }
            i++
        }
        val gaps = peaks.zipWithNext { a, c -> (c - a) * 0.01 }.sorted()
        return gaps[gaps.size / 2]
    }

    @Test
    fun `the sweep period equals the division at the kit's BPM`() {
        assertEquals(0.5f, Wobble.periodSec(120f, "1/4"), 1e-6f)
        assertEquals(0.25f, Wobble.periodSec(120f, "1/8"), 1e-6f)
        assertEquals(2f, Wobble.periodSec(120f, "1/1"), 1e-6f)
        val quarter = sweepPeriod(Wobble.sweep(noise(), 120f, "1/4"))
        assertTrue(abs(quarter - 0.5) < 0.05, "quarters at 120: a half-second sweep, measured $quarter")
        val eighth = sweepPeriod(Wobble.sweep(noise(), 120f, "1/8"))
        assertTrue(abs(eighth - 0.25) < 0.03, "eighths at 120: a quarter-second sweep, measured $eighth")
        val slow = sweepPeriod(Wobble.sweep(noise(), 90f, "1/4"))
        assertTrue(abs(slow - 60.0 / 90.0) < 0.06, "quarters at 90: ${60.0 / 90.0}, measured $slow")
    }

    @Test
    fun `the sweep opens on the onset and darkens half a division later`() {
        val out = Wobble.sweep(noise(), 120f, "1/2") // a second per sweep
        val b = brightness(out)
        val open = b.take(5).average()
        val closed = b.slice(48..52).average()
        assertTrue(open > 2 * closed, "bright at the onset ($open), dark half a division in ($closed)")
        assertTrue(abs(out.peak() - noise().peak()) < 0.02f, "peak matched: ${out.peak()}")
    }

    @Test
    fun `RATE snaps to divisions, AMOUNT 0 is the input itself, and the bounds hold`() {
        assertEquals("1/1", Wobble.divisionFor(0f))
        assertEquals("1/16", Wobble.divisionFor(1f))
        assertEquals("1/4", Wobble.divisionFor(0.5f))
        val n = noise()
        assertSame(n, Wobble.sweep(n, 120f, "1/8", amount = 0f))
        assertFailsWith<IllegalArgumentException> { Wobble.sweep(n, 120f, "1/3") }
        assertFailsWith<IllegalArgumentException> { Wobble.sweep(n, 20f, "1/4") }
        assertFailsWith<IllegalArgumentException> { Wobble.sweep(n, 120f, "1/4", amount = 2f) }
        assertFailsWith<IllegalArgumentException> { Wobble.sweep(Snip(FloatArray(0), 1, rate), 120f) }
        val st = Wobble.sweep(noise(2), 120f, "1/4", amount = 0.5f)
        assertEquals(2, st.channels)
        assertEquals(noise().frameCount, st.frameCount, "the hit's own length")
        assertTrue(st.samples.contentEquals(Wobble.sweep(noise(2), 120f, "1/4", amount = 0.5f).samples), "no seed: the same bytes")
    }
}
