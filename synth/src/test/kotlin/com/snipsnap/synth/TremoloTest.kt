package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TREMOLO's own claims (the doc on [Tremolo]). The rack's shared contract (deterministic, finite, peak-matched,
 * stereo intact) is FxTest's and covers TREMOLO through FxChain.SECTIONS. What is pinned here is the spec's three
 * checks (the depth is exact, nothing clicks, DEPTH 0 is a bit-exact bypass) and that it starts open.
 */
class TremoloTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    /** A constant level, so the output is the gain itself (times the peak match). */
    private fun level(seconds: Float, value: Float = 0.5f): Snip =
        Snip(FloatArray((seconds * rate).toInt()) { value }, 1, rate)

    /** The gain the section applied to [level], scaled so the loudest moment is 1. */
    private fun gain(out: Snip): FloatArray {
        val top = out.samples.max()
        return FloatArray(out.frameCount) { out.samples[it] / top }
    }

    @Test
    fun `DEPTH 0 is a bit-exact bypass, and a degenerate pad never throws`() {
        for (pad in listOf(snare, choir)) {
            assertContentEquals(pad.samples, Tremolo.process(pad, mapOf("DEPTH" to 0f, "SHAPE" to 1f)).samples, "DEPTH 0 is not a copy")
        }
        assertEquals(0, Tremolo.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val nan = Tremolo.process(choir, mapOf("DEPTH" to Float.NaN, "RATE" to Float.NaN))
        assertContentEquals(Tremolo.process(choir).samples, nan.samples, "a NaN was not settled to the default")
        val silent = Snip(FloatArray(4410), 1, rate)
        assertContentEquals(silent.samples, Tremolo.process(silent).samples, "a silent pad is not left alone")
        val tiny = Tremolo.process(Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate), mapOf("DEPTH" to 1f))
        assertEquals(3, tiny.frameCount, "a three-frame pad changed length")
    }

    @Test
    fun `it starts open, so a hit's attack is never eaten`() {
        for (shape in listOf(0f, 0.5f, 1f)) for (r in listOf(0f, 0.5f, 1f)) {
            val m = mapOf("DEPTH" to 1f, "RATE" to r, "SHAPE" to shape)
            val g = gain(Tremolo.process(level(0.5f), m))
            assertEquals(1f, g[0], 1e-6f, "SHAPE $shape RATE $r does not start at full level")
            // The snare's peak is in its first few ms, so it comes through at its own level and needs no lift.
            val out = Tremolo.process(snare, m)
            val at = snare.samples.indices.maxBy { abs(snare.samples[it]) }
            assertEquals(snare.samples[at], out.samples[at], 0.01f, "SHAPE $shape RATE $r moved the snare's peak")
        }
    }

    @Test
    fun `the dip from loudest to quietest is exactly DEPTH, at every SHAPE and RATE`() {
        for (depth in listOf(0.3f, 0.6f, 1f)) for (shape in listOf(0f, 0.5f, 1f)) for (r in listOf(0f, 0.5f, 1f)) {
            val g = gain(Tremolo.process(level(2.2f), mapOf("DEPTH" to depth, "RATE" to r, "SHAPE" to shape)))
            assertEquals(1f - depth, g.min(), 1e-3f, "DEPTH $depth SHAPE $shape RATE $r: the trough is ${g.min()}")
            assertTrue(g.all { it >= 0f }, "the gain went negative: a tremolo never inverts")
        }
    }

    @Test
    fun `nothing clicks, SHAPE 1 is a square with the same edge at every RATE, and SHAPE 0 is a sine`() {
        val fastest = 1f / (Tremolo.EDGE_SEC * rate)
        for (shape in listOf(0f, 0.5f, 1f)) for (r in listOf(0f, 0.5f, 1f)) {
            val g = gain(Tremolo.process(level(2.2f), mapOf("DEPTH" to 1f, "RATE" to r, "SHAPE" to shape)))
            var step = 0f
            for (i in 1 until g.size) step = maxOf(step, abs(g[i] - g[i - 1]))
            println("TREMOLO SHAPE $shape RATE $r (${"%.2f".format(Tremolo.hz(r))} Hz): steepest step ${"%.5f".format(step)} a frame, limit ${"%.5f".format(fastest)}")
            assertTrue(step <= fastest * 1.01f, "SHAPE $shape RATE $r stepped $step in one frame: faster than the ${Tremolo.EDGE_SEC} s edge")
            // How long it sits fully open or fully closed (within 5 percent of either end).
            val parked = g.count { it > 0.95f || it < 0.05f }.toFloat() / g.size
            when (shape) {
                0f -> assertTrue(parked < 0.4f, "SHAPE 0 at RATE $r parks ${"%.2f".format(parked)} of the time: not a sine")
                1f -> assertTrue(parked > 0.85f,"SHAPE 1 at RATE $r parks only ${"%.2f".format(parked)} of the time: not a square")
            }
        }
        // The sine is the plain (1 - cos) / 2: SHAPE 0 is not a steepened shape.
        val omega = 2.0 * PI * Tremolo.hz(0.5f) / rate
        val g = gain(Tremolo.process(level(1f), mapOf("DEPTH" to 1f, "SHAPE" to 0f)))
        for (i in g.indices step 997) assertEquals((0.5 + 0.5 * kotlin.math.cos(omega * i)).toFloat(), g[i], 1e-4f, "SHAPE 0 is not a sine at $i")
    }

    @Test
    fun `RATE sets how many throbs a second, from 1 to 12`() {
        for ((r, hz) in listOf(0f to 1f, 0.5f to 3.4641f, 1f to 12f)) {
            assertEquals(hz, Tremolo.hz(r), 1e-3f)
            val g = gain(Tremolo.process(level(2f), mapOf("DEPTH" to 1f, "RATE" to r)))
            var dips = 0
            for (i in 1 until g.size) if (g[i - 1] >= 0.5f && g[i] < 0.5f) dips++
            assertEquals((2f * hz + 0.25f).toInt(), dips, "RATE $r: $dips dips in 2 s, not ${2 * hz}")
        }
    }

    @Test
    fun `the throb is in the level, not the pitch, so a steady tone keeps its sign`() {
        val tone = Snip(FloatArray(rate) { (0.6 * sin(2 * PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        val out = Tremolo.process(tone, mapOf("DEPTH" to 1f, "SHAPE" to 1f, "RATE" to 1f))
        for (i in tone.samples.indices) assertTrue(tone.samples[i] * out.samples[i] >= 0f, "the sign flipped at $i: RING, not a tremolo")
    }

    @Test
    fun `a tap of the TREM chip is the Gentle clip the owner chose, and AMT 0 is a copy`() {
        // The gate (2026-11-12): Gentle, DEPTH 0.5 at RATE 0.5 and SHAPE 0, heard through the rack. RATE and SHAPE are left
        // at their defaults, each its own neutral, so no AMT moves them.
        val character = Treatments.chain("tremoloed").tremolo!!
        assertEquals(setOf("DEPTH"), character.keys, "the tremoloed character set more than DEPTH")
        assertEquals(0.5f, Tremolo.MACROS.single { it.name == "RATE" }.default, "RATE's default is no longer Gentle's")
        assertEquals(0f, Tremolo.MACROS.single { it.name == "SHAPE" }.default, "SHAPE's default is no longer the sine")
        val tap = 0.7f
        assertEquals(0.5f, Treatments.chain("tremoloed", tap).tremolo!!.getValue("DEPTH"), 1e-6f, "a tap is not DEPTH 0.5")
        val heard = FxChain(tremolo = mapOf("DEPTH" to 0.5f, "RATE" to 0.5f, "SHAPE" to 0f)).process(snare).samples
        val got = Treatments.apply("tremoloed", snare, tap).snip.samples
        assertEquals(heard.size, got.size)
        var worst = 0f
        for (i in heard.indices) worst = maxOf(worst, abs(heard[i] - got[i]))
        assertTrue(worst < 1e-6f, "the tap is not the Gentle clip: worst sample difference $worst")
        assertContentEquals(snare.samples, Treatments.apply("tremoloed", snare, 0f).snip.samples, "AMT 0 is not a copy")
    }
}
