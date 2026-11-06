package com.snipsnap.shell

import com.snipsnap.audio.Snip
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RobinVariationTest {
    private val hit = Snip(FloatArray(22_050) { 0.5f * sin(2.0 * PI * 200.0 * it / 44_100.0).toFloat() }, 1, 44_100)

    @Test fun `the points are the ear-tested table`() {
        assertEquals(Spreads(0.14f, 18f, 0.003f, 0.15f), RobinVariation.spreadsAt(0f))
        assertEquals(Spreads(0.40f, 50f, 0.006f, 0.56f), RobinVariation.spreadsAt(0.6f))
        assertEquals(Spreads(0.60f, 80f, 0.010f, 0.88f), RobinVariation.spreadsAt(1f))
    }

    @Test fun `between points the dial is linear`() {
        val mid = RobinVariation.spreadsAt(0.7f)
        assertEquals(0.45f, mid.level, 1e-5f)
        assertEquals(57.5f, mid.cents, 1e-4f)
        assertEquals(0.007f, mid.trimSec, 1e-6f)
        assertEquals(0.64f, mid.tilt, 1e-5f)
    }

    @Test fun `the dial clamps outside zero to one`() {
        assertEquals(RobinVariation.spreadsAt(0f), RobinVariation.spreadsAt(-3f))
        assertEquals(RobinVariation.spreadsAt(1f), RobinVariation.spreadsAt(9f))
    }

    @Test fun `take one is the original and the rest differ`() {
        val t = RobinVariation.takes(hit, 6, 0.6f, Random(5))
        assertSame(hit, t[0])
        assertEquals(6, t.size)
        assertTrue(t.drop(1).all { !it.samples.contentEquals(hit.samples) })
    }

    @Test fun `same seed same takes`() {
        val a = RobinVariation.takes(hit, 5, 0.6f, Random(5))
        val b = RobinVariation.takes(hit, 5, 0.6f, Random(5))
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) -> assertTrue(x.samples.contentEquals(y.samples)) }
    }

    private fun peak(s: Snip) = s.samples.maxOf { abs(it) }

    @Test fun `the dial scales the same takes`() {
        val lo = RobinVariation.takes(hit, 4, 0.2f, Random(5))
        val hi = RobinVariation.takes(hit, 4, 1f, Random(5))
        for (i in 1..3) {
            val dLo = peak(lo[i]) / peak(hit) - 1f
            val dHi = peak(hi[i]) / peak(hit) - 1f
            assertTrue(dLo * dHi > 0f, "take $i gain moves the same way at both ends ($dLo, $dHi)")
            assertTrue(abs(dHi) > abs(dLo), "take $i moves further at H ($dLo, $dHi)")
        }
    }

    private fun hfRatio(s: Snip): Double {
        var d = 0.0
        var e = 0.0
        for (i in 1 until s.samples.size) {
            val x = s.samples[i].toDouble()
            val dx = x - s.samples[i - 1]
            d += dx * dx
            e += x * x
        }
        return sqrt(d / e)
    }

    @Test fun `darker takes lose top end and brighter ones gain it`() {
        val noise = Random(99)
        val white = Snip(FloatArray(44_100) { (noise.nextFloat() * 2f - 1f) * 0.3f }, 1, 44_100)
        val takes = RobinVariation.takes(white, 8, 1f, Random(5))
        val draws = Random(5)
        val base = hfRatio(white)
        var checked = 0
        for (i in 1 until 8) {
            draws.nextFloat(); draws.nextFloat(); draws.nextFloat()
            val t = (draws.nextFloat() * 2f - 1f) * 0.88f
            if (abs(t) < 0.25f) continue
            checked++
            val r = hfRatio(takes[i])
            if (t < 0) assertTrue(r < base, "take $i tilt $t darker: $r vs $base")
            else assertTrue(r > base, "take $i tilt $t brighter: $r vs $base")
        }
        assertTrue(checked >= 3, "need enough decisive tilts, got $checked")
    }

    @Test fun `a very short hit keeps audio in every take at H`() {
        val click = Snip(FloatArray(882) { if (it < 40) 0.9f else 0f }, 1, 44_100)
        RobinVariation.takes(click, 8, 1f, Random(1)).forEach {
            assertTrue(it.frameCount > 0 && it.samples.any { s -> s != 0f })
        }
    }

    @Test fun `a 48 kHz hit keeps its rate`() {
        val h = Snip(FloatArray(9_600) { 0.5f * sin(it * 0.05f) }, 1, 48_000)
        RobinVariation.takes(h, 4, 0.6f, Random(2)).forEach { assertEquals(48_000, it.sampleRate) }
    }

    @Test fun `stereo keeps two channels and whole frames`() {
        val st = Snip(FloatArray(8_820) { 0.4f * sin(it * 0.01f) }, 2, 44_100)
        RobinVariation.takes(st, 4, 1f, Random(3)).forEach {
            assertEquals(2, it.channels)
            assertTrue(it.samples.all { s -> s in -1f..1f })
        }
    }

    private fun peakAfter(s: Snip, frames: Int): Float {
        var p = 0f
        for (i in frames * s.channels until s.samples.size) p = maxOf(p, abs(s.samples[i]))
        return p
    }

    /** A 20 ms click, exponential decay to a nonzero tail: most of its energy is in the first few ms. */
    private val click = Snip(FloatArray(882) { (0.9 * kotlin.math.exp(-it / 40.0)).toFloat() }, 1, 44_100)

    @Test fun `the front trim never shaves a short hit's peak away`() {
        val peak = peakAfter(click, 0)
        val floor = peak * Math.pow(10.0, -6.0 / 20.0).toFloat()
        // Every trim the dial can draw, up to H's 10 ms (441 frames).
        for (drawn in 0..441) {
            val t = RobinVariation.trimFor(click, drawn)
            assertTrue(t in 0..drawn, "trim $t for a draw of $drawn")
            assertTrue(peakAfter(click, t) >= floor, "a draw of $drawn trims $t and leaves peak ${peakAfter(click, t)} under $floor")
        }
    }

    @Test fun `the front trim keeps the ear-tested amount on the ear-tested drums`() {
        // The five sounds the owner picked F on; H's longest trim, 10 ms, stays whole on every one.
        val drums = mapOf(
            "kick" to com.snipsnap.audio.DrumSynth.kick(),
            "snare" to com.snipsnap.audio.DrumSynth.snare(),
            "hat" to com.snipsnap.audio.DrumSynth.hat(0.1f, 60.0),
            "clap" to com.snipsnap.audio.DrumSynth.clap(),
            "tom" to com.snipsnap.audio.DrumSynth.tom(),
        )
        for ((name, hit) in drums) {
            val drawn = (0.010f * hit.sampleRate).toInt()
            assertEquals(drawn, RobinVariation.trimFor(hit, drawn), name)
        }
    }

    @Test fun `every take of a decaying click keeps the click`() {
        val peak = peakAfter(click, 0)
        RobinVariation.takes(click, 8, 1f, Random(1)).drop(1).forEachIndexed { i, take ->
            // Level (down to x0.4 at H) and tilt move the peak too, so this only asks that the click is
            // still there: a trim past it left 0.003 of 0.9 (-50 dB); [trimFor] is checked exactly above.
            assertTrue(peakAfter(take, 0) >= peak * 0.1f, "take ${i + 2} peak ${peakAfter(take, 0)} vs $peak")
        }
    }

    @Test fun `louder takes of a full-scale hit are limited, never clipped`() {
        val full = Snip(FloatArray(22_050) { sin(2.0 * PI * 200.0 * it / 44_100.0).toFloat() }, 1, 44_100)
        val takes = RobinVariation.takes(full, 8, 0.6f, Random(5))
        takes.drop(1).forEachIndexed { i, take ->
            val p = take.samples.maxOf { abs(it) }
            assertTrue(p <= 0.999f, "take ${i + 2} peaks at $p - clamped at full scale")
        }
        val distinct = takes.drop(1).map { it.samples.toList() }.toSet()
        assertEquals(7, distinct.size, "the limited takes still differ from one another")
    }
}
