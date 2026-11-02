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
 * VIBRATO's own claims (the doc on [Vibrato]). The rack's shared contract (deterministic, finite,
 * peak-matched, stereo intact) is FxTest's and covers VIBRATO through FxChain.SECTIONS. What is pinned
 * here is the one thing the pedal is for: DEPTH is the pitch swing in cents, whatever the RATE.
 */
class VibratoTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    /** A steady 220 Hz sine, so any pitch movement is the section's. */
    private fun tone(seconds: Float): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { (0.5 * sin(2.0 * PI * 220.0 * it / rate)).toFloat() }, 1, rate)
    }

    /** The pitch of [x] over time, in cents against [hz], from the spacing of its upward zero crossings. */
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

    /** The peak upward swing and the peak downward swing over the track after [from] seconds. */
    private fun swing(track: List<Pair<Double, Double>>, from: Double = 0.2): Pair<Double, Double> {
        val c = track.filter { it.first >= from }.map { it.second }
        return c.max() to c.min()
    }

    @Test
    fun `DEPTH at zero is a copy, and a degenerate pad never throws`() {
        for (pad in listOf(snare, choir)) {
            assertContentEquals(pad.samples, Vibrato.process(pad, mapOf("DEPTH" to 0f)).samples, "DEPTH 0 is not a copy")
        }
        assertEquals(0, Vibrato.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val nan = Vibrato.process(choir, mapOf("DEPTH" to Float.NaN))
        assertTrue(nan.samples.all { it.isFinite() }, "a NaN DEPTH broke the render")
        assertContentEquals(Vibrato.process(choir).samples, nan.samples, "a NaN DEPTH was not settled to the default")
        val tiny = Vibrato.process(Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate), mapOf("DEPTH" to 1f))
        assertEquals(3, tiny.frameCount, "a three-frame pad changed length")
        assertTrue(tiny.samples.all { it.isFinite() }, "a three-frame pad broke the render")
    }

    @Test
    fun `DEPTH is the pitch swing in cents, the same at every RATE`() {
        val pad = tone(2.5f)
        for ((depth, expected) in listOf(0.35f to 21.0, 0.5f to 30.0, 1f to 60.0)) {
            for (r in listOf(0f, 0.5f, 1f)) {
                val out = Vibrato.process(pad, mapOf("DEPTH" to depth, "RATE" to r))
                val (up, down) = swing(centsTrack(out.samples, 220.0))
                println("VIBRATO DEPTH $depth RATE $r (${Vibrato.hz(r)} Hz): swing +${"%.1f".format(up)} / ${"%.1f".format(down)} cents, asked +$expected")
                assertEquals(expected, up, 3.0, "DEPTH $depth at RATE $r swung up $up cents, not about $expected")
                assertTrue(down < 0 && abs(down) > expected - 3.0, "DEPTH $depth at RATE $r did not swing down: $down")
            }
        }
    }

    @Test
    fun `RATE sets how quickly it sings`() {
        val pad = tone(3f)
        for (r in listOf(0f, 0.5f, 1f)) {
            val track = centsTrack(Vibrato.process(pad, mapOf("DEPTH" to 1f, "RATE" to r)).samples, 220.0).filter { it.first > 0.2 }
            // Count the downward crossings of zero cents: one per cycle.
            var cycles = 0
            for (i in 1 until track.size) if (track[i - 1].second > 0 && track[i].second <= 0) cycles++
            val measured = cycles / (track.last().first - track.first().first)
            assertEquals(Vibrato.hz(r).toDouble(), measured, 0.4, "RATE $r sang at $measured Hz, not ${Vibrato.hz(r)}")
        }
    }

    @Test
    fun `it starts on the first frame with no latency, and LATE brings the swing in`() {
        val pad = tone(3f)
        val now = Vibrato.process(pad, mapOf("DEPTH" to 1f))
        // The delay is zero at the first frame and grows from there, so the opening frames are the input's own.
        for (f in 0 until 8) assertEquals(pad.samples[f], now.samples[f], 5e-3f, "frame $f of the opening moved")
        val late = Vibrato.process(pad, mapOf("DEPTH" to 1f, "LATE" to 1f))
        val track = centsTrack(late.samples, 220.0)
        val early = swing(track.filter { it.first < 0.15 }, from = 0.0).first
        val settled = swing(track, from = Vibrato.LATE_MAX_SEC.toDouble() + 0.3).first
        println("VIBRATO LATE 1: swing +${"%.1f".format(early)} cents in the first 150 ms, +${"%.1f".format(settled)} once arrived")
        assertTrue(early < 0.4 * settled, "LATE did not hold the vibrato back: $early vs $settled")
        assertEquals(60.0, settled, 4.0, "the vibrato did not arrive at full depth")
    }

    @Test
    fun `it is wet only, so the level does not move and the pad keeps its length`() {
        for (pad in listOf(snare, choir)) {
            val out = Vibrato.process(pad, mapOf("DEPTH" to 1f, "RATE" to 0.5f))
            assertEquals(pad.frameCount, out.frameCount, "the pad changed length")
            assertEquals(pad.peak(), out.peak(), 0.02f, "the vibrato changed the loudness")
        }
    }

    @Test
    fun `the section is deterministic, keeps stereo intact, and ignores unknown keys`() {
        val a = Vibrato.process(choir, mapOf("DEPTH" to 0.8f))
        assertContentEquals(a.samples, Vibrato.process(choir, mapOf("DEPTH" to 0.8f)).samples, "VIBRATO is not deterministic")
        assertContentEquals(a.samples, Vibrato.process(choir, mapOf("DEPTH" to 0.8f, "SPEED" to 0.9f)).samples, "an unknown key moved the vibrato")
        val stereo = Snip(FloatArray(choir.frameCount * 2) { choir.samples[it / 2] }, 2, choir.sampleRate)
        val out = Vibrato.process(stereo, mapOf("DEPTH" to 0.8f))
        assertEquals(2, out.channels)
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
    }
}
