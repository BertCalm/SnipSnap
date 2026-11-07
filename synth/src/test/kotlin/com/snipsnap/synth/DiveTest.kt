package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIVE's own claims (the doc on [Dive]). The rack's shared contract (deterministic, finite, peak-matched,
 * stereo intact) is FxTest's and covers DIVE through FxChain.SECTIONS. What is pinned here is the one thing
 * the pedal is for: the pitch falls DROP semitones, fastest at the hit, over OVER, and holds there.
 */
class DiveTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val tom = Thump.render(ThumpVoice.TOM)
    private val rim = Thump.render(ThumpVoice.RIM)

    /** A steady 220 Hz sine, so any pitch movement is the section's. */
    private fun tone(seconds: Float): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { (0.5 * sin(2.0 * PI * 220.0 * it / rate)).toFloat() }, 1, rate)
    }

    /** The pitch of [x] over time, in cents against [hz], from the spacing of its upward zero crossings: (time, cents). */
    private fun centsTrack(x: FloatArray, hz: Double): List<Pair<Double, Double>> {
        val crossings = ArrayList<Double>()
        for (i in 1 until x.size) {
            if (x[i - 1] < 0f && x[i] >= 0f) crossings += (i - 1) + (-x[i - 1]).toDouble() / (x[i] - x[i - 1])
        }
        return (1 until crossings.size).map { k ->
            val period = (crossings[k] - crossings[k - 1]) / rate
            val mid = (crossings[k] + crossings[k - 1]) / 2 / rate
            mid to 1200.0 * ln(1.0 / period / hz) / ln(2.0)
        }
    }

    /**
     * The measured pitch at [seconds], interpolated between the two crossings either side of it. A pitch
     * that is falling several cents a millisecond must be read at the instant asked for, not at the nearest
     * crossing, which can be a few milliseconds away.
     */
    private fun centsAt(track: List<Pair<Double, Double>>, seconds: Double): Double {
        val after = track.indexOfFirst { it.first >= seconds }
        assertTrue(after >= 1, "no pitch measurement either side of $seconds s")
        val (t0, c0) = track[after - 1]
        val (t1, c1) = track[after]
        return c0 + (c1 - c0) * (seconds - t0) / (t1 - t0)
    }

    /** The mean measured pitch of the crossings between [from] and [to] seconds. */
    private fun meanCents(track: List<Pair<Double, Double>>, from: Double, to: Double): Double =
        track.filter { it.first in from..to }.also { assertTrue(it.isNotEmpty(), "no pitch measurement in $from..$to s") }.map { it.second }.average()

    @Test
    fun `DROP at zero is a copy, and a degenerate pad never throws`() {
        for (pad in listOf(snare, tom, rim)) {
            assertContentEquals(pad.samples, Dive.process(pad, mapOf("DROP" to 0f)).samples, "DROP 0 is not a copy")
        }
        assertEquals(0, Dive.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val one = Snip(floatArrayOf(0.5f), 1, rate)
        assertContentEquals(one.samples, Dive.process(one, mapOf("DROP" to 1f)).samples, "a one-frame pad was changed")
        val three = Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate)
        val tiny = Dive.process(three, mapOf("DROP" to 1f, "OVER" to 0f))
        assertTrue(tiny.samples.all { it.isFinite() }, "a three-frame pad broke the render")
        assertTrue(tiny.frameCount >= 1 && tiny.frameCount <= 4 * three.frameCount + 2, "a three-frame pad came out ${tiny.frameCount} frames")
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val out = Dive.process(snare, mapOf("DROP" to bad, "OVER" to bad))
            assertTrue(out.samples.all { it.isFinite() }, "a non-finite macro broke the render")
            assertContentEquals(Dive.process(snare).samples, out.samples, "a non-finite macro was not settled to the default")
        }
    }

    @Test
    fun `the pitch falls on the exponential law, fastest at the hit, and holds at DROP semitones`() {
        val pad = tone(4f)
        val drop = Dive.dropFor(6f)
        val over = 0.5f
        val out = Dive.process(pad, mapOf("DROP" to drop, "OVER" to over))
        val track = centsTrack(out.samples, 220.0)
        val st = Dive.semitones(drop)
        val sec = Dive.overSec(over)
        for (t in listOf(0.02, 0.05, 0.1, 0.17, 0.3, 0.6)) {
            val expected = -100.0 * Dive.fallenAt(t, st, sec)
            val got = centsAt(track, t)
            println("DIVE law: t=${"%.3f".format(t)} s measured ${"%.1f".format(got)} cents, expected ${"%.1f".format(expected)}")
            assertEquals(expected, got, 6.0, "the pitch at $t s is not on the exponential fall")
        }
        val settled = meanCents(track, 1.3, 1.7)
        println("DIVE settled: ${"%.1f".format(settled)} cents against -${st * 100}")
        assertEquals(-st * 100.0, settled, 3.0, "the pitch did not settle DROP semitones down")
        // Fastest at the hit: the first 50 ms fall further than the next 50.
        val firstFall = centsAt(track, 0.01) - centsAt(track, 0.05)
        val nextFall = centsAt(track, 0.05) - centsAt(track, 0.1)
        assertTrue(firstFall > nextFall, "the fall is not fastest at the hit: $firstFall vs $nextFall")
    }

    @Test
    fun `OVER sets how long the fall takes`() {
        val pad = tone(5f)
        for (over in listOf(0.2f, 0.5f, 0.8f)) {
            val track = centsTrack(Dive.process(pad, mapOf("DROP" to Dive.dropFor(12f), "OVER" to over)).samples, 220.0)
            val target = -0.95 * 1200.0
            val reached = track.first { it.second <= target }.first
            val sec = Dive.overSec(over)
            println("DIVE OVER $over: 95 percent of a 12 semitone fall reached at ${"%.3f".format(reached)} s, asked ${"%.3f".format(sec)} s")
            assertEquals(sec.toDouble(), reached, 0.15 * sec + 0.01, "OVER $over did not take its time")
        }
        assertEquals(Dive.FAST_SEC, Dive.overSec(0f), 1e-6f)
        assertEquals(Dive.SLOW_SEC, Dive.overSec(1f), 1e-6f)
        assertEquals(0.37f, Dive.overFor(Dive.overSec(0.37f)), 1e-5f, "overFor is not the inverse of overSec")
    }

    @Test
    fun `the attack is read at its own pitch, and the pad grows as a lower pitch does`() {
        val pad = tone(2f)
        val out = Dive.process(pad, mapOf("DROP" to Dive.dropFor(12f), "OVER" to 0.5f))
        for (f in 0 until 8) assertEquals(pad.samples[f], out.samples[f], 5e-3f, "frame $f of the attack moved")
        // After a 12 semitone dive the head reads at half speed, so the pad is nearly twice as long, never more than twice.
        val ratio = out.frameCount.toDouble() / pad.frameCount
        println("DIVE 12 semitones: ${pad.frameCount} -> ${out.frameCount} frames, x${"%.3f".format(ratio)}")
        assertTrue(ratio in 1.85..2.0, "a 12 semitone dive made the pad x$ratio")
        val half = Dive.process(pad, mapOf("DROP" to Dive.dropFor(6f), "OVER" to 0.5f))
        val halfRatio = half.frameCount.toDouble() / pad.frameCount
        assertTrue(halfRatio in 1.3..1.4143, "a 6 semitone dive made the pad x$halfRatio")
        // The top of DROP is two octaves: a quarter speed, so up to four times as long.
        val full = Dive.process(pad, mapOf("DROP" to 1f, "OVER" to 0.5f))
        val fullRatio = full.frameCount.toDouble() / pad.frameCount
        assertTrue(fullRatio in 3.6..4.0, "a 24 semitone dive made the pad x$fullRatio")
    }

    @Test
    fun `it is shape, not loudness, so the level does not move`() {
        for (pad in listOf(snare, tom, rim, tone(1f))) {
            val out = Dive.process(pad, mapOf("DROP" to 1f, "OVER" to 0.3f))
            assertEquals(pad.peak(), out.peak(), 0.02f, "the dive changed the loudness")
            assertTrue(out.frameCount > pad.frameCount, "the pad did not grow")
        }
    }

    @Test
    fun `a tap of the DIVE chip drops the pitch the 12 semitones the owner chose, and AMT walks the depth alone`() {
        val tap = 0.7f
        val pad = tone(3f)
        assertEquals(setOf("DROP"), Treatments.chain("dived").dive!!.keys, "the dived character set more than DROP")
        fun settled(amount: Float): Double = meanCents(centsTrack(Treatments.apply("dived", pad, amount).snip.samples, 220.0), 1.3, 1.7)
        println("DIVE chip: tap ${"%.1f".format(settled(tap))} cents, AMT 1 ${"%.1f".format(settled(1f))}, AMT 0.35 ${"%.1f".format(settled(0.35f))}")
        assertEquals(-1200.0, settled(tap), 3.0, "a tap no longer drops the 12 semitones the owner chose")
        assertEquals(-100.0 * Dive.MAX_SEMITONES * (0.5 / 0.7), settled(1f), 4.0, "AMT 1 is not the full character")
        assertTrue(settled(0.35f) > settled(tap), "a lower AMT did not shrink the dive")
        // The tap is the 12 semitone clip the owner heard (DROP 0.5 at the default fall), up to the float rounding of 0.5 / 0.7 x 0.7.
        assertEquals(0.5f, Treatments.chain("dived", tap).dive!!.getValue("DROP"), 1e-6f, "the tap's DROP is not the heard 12 semitones")
        val guitar = Magnet.render(MagnetVoice.CHUG)
        val heard = Dive.process(guitar, mapOf("DROP" to Dive.dropFor(12f))).samples
        val tapped = Treatments.apply("dived", guitar, tap).snip.samples
        assertTrue(abs(heard.size - tapped.size) <= 2, "the tap's length differs from the heard clip: ${tapped.size} vs ${heard.size}")
        var worst = 0f
        for (i in 0 until minOf(heard.size, tapped.size)) worst = maxOf(worst, abs(heard[i] - tapped[i]))
        assertTrue(worst < 1e-3f, "the tap is not the heard dive: worst sample difference $worst")
        assertContentEquals(guitar.samples, Treatments.apply("dived", guitar, 0f).snip.samples, "AMT 0 is not a copy")
    }

    @Test
    fun `the section is deterministic, keeps stereo intact, and ignores unknown keys`() {
        val a = Dive.process(tom, mapOf("DROP" to 0.8f))
        assertContentEquals(a.samples, Dive.process(tom, mapOf("DROP" to 0.8f)).samples, "DIVE is not deterministic")
        assertContentEquals(a.samples, Dive.process(tom, mapOf("DROP" to 0.8f, "SPEED" to 0.9f)).samples, "an unknown key moved the dive")
        assertContentEquals(Dive.process(tom, mapOf("DROP" to 1f)).samples, Dive.process(tom, mapOf("DROP" to 7f)).samples, "DROP 7 was not clamped to 1")
        val stereo = Snip(FloatArray(tom.frameCount * 2) { tom.samples[it / 2] }, 2, tom.sampleRate)
        val out = Dive.process(stereo, mapOf("DROP" to 0.8f))
        assertEquals(2, out.channels)
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
    }
}
