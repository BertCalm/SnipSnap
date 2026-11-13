package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FLANGER's own claims (the doc on [Flanger]). The rack's shared contract (deterministic, finite, peak-matched,
 * stereo intact) is FxTest's and covers FLANGER through FxChain.SECTIONS. What is pinned here is that it is a
 * comb (notches at the odd multiples of 1 / (2d)), that DEPTH sets how far down it sweeps, that FEEDBACK turns it
 * both ways, and that its ring dies inside the pad, so it needs no tail.
 */
class FlangerTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    /** The slowest sweep (0.1 Hz) turns at 5 s, so a short window there hears a comb that is all but still. */
    private val turn = 5.0

    /** Equal sines at [freqs], [sec] long. */
    private fun chord(freqs: List<Double>, sec: Double): Snip {
        val n = (sec * rate).toInt()
        val amp = 0.9 / freqs.size
        return Snip(FloatArray(n) { i -> freqs.sumOf { amp * sin(2 * PI * it * i / rate) }.toFloat() }, 1, rate)
    }

    /** The amplitude of [hz] in [x] over [from, until) seconds (a single DFT bin). */
    private fun amp(x: FloatArray, hz: Double, from: Double, until: Double): Double {
        val a = (from * rate).toInt()
        val b = (until * rate).toInt()
        var re = 0.0
        var im = 0.0
        for (i in a until b) {
            re += x[i] * cos(2 * PI * hz * i / rate)
            im += x[i] * sin(2 * PI * hz * i / rate)
        }
        return 2 * sqrt(re * re + im * im) / (b - a)
    }

    private fun db(x: Double) = 20 * log10(x)

    @Test
    fun `DEPTH 0 is an exact copy, and a degenerate pad never throws`() {
        for (pad in listOf(snare, choir)) {
            assertContentEquals(pad.samples, Flanger.process(pad, mapOf("DEPTH" to 0f, "FEEDBACK" to 1f)).samples, "DEPTH 0 is not a copy")
        }
        assertEquals(0, Flanger.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val nan = Flanger.process(choir, mapOf("DEPTH" to Float.NaN, "FEEDBACK" to Float.NaN))
        assertContentEquals(Flanger.process(choir).samples, nan.samples, "a NaN was not settled to the default")
        val silent = Snip(FloatArray(4410), 1, rate)
        assertContentEquals(silent.samples, Flanger.process(silent).samples, "a silent pad is not left alone")
        val tiny = Flanger.process(Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate), mapOf("DEPTH" to 1f, "FEEDBACK" to 1f))
        assertEquals(3, tiny.frameCount, "a three-frame pad changed length")
        assertTrue(tiny.samples.all { it.isFinite() }, "a three-frame pad broke the render")
    }

    @Test
    fun `it is a comb, and DEPTH sets how far down the notches sweep`() {
        // At the bottom of the sweep the delay is DEPTH's top: 10 ms at DEPTH 1 (notches at 50, 150, 250 Hz ...)
        // and 1 ms at DEPTH 0.5 (notches at 500, 1500, 2500 Hz). Between the notches, the peaks.
        for ((depth, d) in listOf(1f to 0.010, 0.5f to 0.001)) {
            assertEquals(d * 1000, Flanger.topMs(depth).toDouble(), 1e-4)
            val step = 1.0 / d
            val peaks = (1..4).map { it * step }
            val notches = peaks.map { it + step / 2 }
            val pad = chord(peaks + notches, turn + 0.1)
            val out = Flanger.process(pad, mapOf("DEPTH" to depth, "RATE" to 0f, "FEEDBACK" to 0.5f)).samples
            val p = peaks.map { db(amp(out, it, turn - 0.05, turn + 0.05)) }.average()
            val n = notches.map { db(amp(out, it, turn - 0.05, turn + 0.05)) }.average()
            println("FLANGER DEPTH $depth at the bottom of the sweep: peaks ${"%.1f".format(p)} dB, notches ${"%.1f".format(n)} dB")
            assertTrue(p - n > 20.0, "DEPTH $depth: the notches at the odd multiples of ${step / 2} Hz are only ${p - n} dB down")
        }
        // At the start the delay is MIN_DELAY_MS: the first notch is at 5 kHz, so the hit comes through nearly whole.
        val low = listOf(200.0, 400.0, 800.0, 1600.0)
        val pad = chord(low, 0.2)
        val out = Flanger.process(pad, mapOf("DEPTH" to 1f, "RATE" to 0f, "FEEDBACK" to 0.5f)).samples
        val spread = low.map { db(amp(out, it, 0.0, 0.05)) }
        println("FLANGER first 50 ms, 200 Hz to 1.6 kHz: ${spread.map { "%.1f".format(it) }}")
        assertTrue(spread.max() - spread.min() < 3.0, "the start already sweeps: $spread")
    }

    @Test
    fun `FEEDBACK turns it both ways, positive sharpens the peaks, negative swaps them with the notches`() {
        val d = 0.010
        val evens = (11..14).map { it / d }            // multiples of 1 / d: the peaks with no feedback
        val odds = evens.map { it + 0.5 / d }           // odd multiples of 1 / (2d): the notches with no feedback
        fun contrast(feedback: Float): Double {
            val out = Flanger.process(chord(evens + odds, turn + 0.1), mapOf("DEPTH" to 1f, "RATE" to 0f, "FEEDBACK" to feedback)).samples
            val e = evens.map { db(amp(out, it, turn - 0.05, turn + 0.05)) }.average()
            val o = odds.map { db(amp(out, it, turn - 0.05, turn + 0.05)) }.average()
            return e - o
        }
        val none = contrast(0.5f)
        val metal = contrast(1f)
        val hollow = contrast(0f)
        println("FLANGER peaks over notches: none ${"%.1f".format(none)} dB, FEEDBACK 1 ${"%.1f".format(metal)} dB, FEEDBACK 0 ${"%.1f".format(hollow)} dB")
        assertTrue(metal > 15.0, "positive feedback does not leave peaks at the multiples of 1 / d: $metal dB")
        assertTrue(hollow < -5.0, "negative feedback does not move the peaks to the old notches: $hollow dB")
        assertEquals(0f, Flanger.feedback(0.5f), 0f, "the middle of FEEDBACK is not none")
        assertEquals(Flanger.MAX_FEEDBACK, Flanger.feedback(1f), 1e-6f)
        assertEquals(-Flanger.MAX_FEEDBACK, Flanger.feedback(0f), 1e-6f)
    }

    @Test
    fun `at the top FEEDBACK and the longest delay the ring dies inside a third of a second, so the pad keeps its length`() {
        // A click at the sweep's turn, where the delay is longest (10 ms) and the ring lasts longest.
        val at = (turn * rate).toInt()
        val click = Snip(FloatArray(at + rate) { if (it == at) 0.9f else 0f }, 1, rate)
        for (feedback in listOf(0f, 1f)) {
            val out = Flanger.process(click, mapOf("DEPTH" to 1f, "RATE" to 0f, "FEEDBACK" to feedback))
            assertEquals(click.frameCount, out.frameCount, "the pad grew")
            val head = (at until at + rate / 20).maxOf { abs(out.samples[it]) }
            val late = (at + (0.35 * rate).toInt() until out.frameCount).maxOf { abs(out.samples[it]) }
            println("FLANGER ring at FEEDBACK $feedback: ${"%.1f".format(db((late / head).toDouble()))} dB after 0.35 s")
            assertTrue(late < head * 1e-3f, "FEEDBACK $feedback still rings ${db((late / head).toDouble())} dB after 0.35 s")
        }
    }

    @Test
    fun `a tap of the FLANGE chip is the Plain clip the owner chose, and AMT 0 is a copy`() {
        // The gate (2026-11-12): Plain, DEPTH 0.7 at RATE 0.4 with no feedback (FEEDBACK 0.5), heard through the rack.
        // RATE and FEEDBACK are left at their defaults, each its own neutral, so no AMT moves either.
        val character = Treatments.chain("flanged").flanger!!
        assertEquals(setOf("DEPTH"), character.keys, "the flanged character set more than DEPTH")
        assertEquals(0.4f, Flanger.MACROS.single { it.name == "RATE" }.default, "RATE's default is no longer Plain's")
        assertEquals(0.5f, Flanger.MACROS.single { it.name == "FEEDBACK" }.default, "FEEDBACK's default is no longer Plain's none")
        val tap = 0.7f
        val tapped = Treatments.chain("flanged", tap).flanger!!
        assertEquals(0.7f, tapped.getValue("DEPTH"), 1e-6f, "a tap is not DEPTH 0.7")
        val snare = Onset.render(OnsetVoice.SNARE)
        val heard = FxChain(flanger = mapOf("DEPTH" to 0.7f, "RATE" to 0.4f, "FEEDBACK" to 0.5f)).process(snare).samples
        val got = Treatments.apply("flanged", snare, tap).snip.samples
        assertEquals(heard.size, got.size)
        var worst = 0f
        for (i in heard.indices) worst = maxOf(worst, abs(heard[i] - got[i]))
        assertTrue(worst < 1e-5f, "the tap is not the Plain clip: worst sample difference $worst")
        assertContentEquals(snare.samples, Treatments.apply("flanged", snare, 0f).snip.samples, "AMT 0 is not a copy")
    }
}
