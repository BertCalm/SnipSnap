package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HUSH's own claims (the doc on [Hush]). The rack's shared contract (deterministic, finite, peak-matched,
 * stereo intact) is FxTest's and covers HUSH through FxChain.SECTIONS. What is pinned here is what a gate
 * is for: the pad sounds as it is until its level falls FLOOR under its peak, then it is shut, over CLOSE,
 * to true silence, without chatter, and it opens again for the next hit.
 */
class HushTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val tom = Thump.render(ThumpVoice.TOM)

    /** A 220 Hz sine falling [dbPerSec] from 0.8, so the time it crosses any level is known. */
    private fun decaying(seconds: Float, dbPerSec: Double = 60.0): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { (0.8 * sin(2.0 * PI * 220.0 * it / rate) * 10.0.pow(-dbPerSec * it / rate / 20.0)).toFloat() }, 1, rate)
    }

    /** The first frame from which [x] is exactly zero to the end, or -1 if it never is. */
    private fun silentFrom(x: FloatArray): Int {
        var i = x.size
        while (i > 0 && x[i - 1] == 0f) i--
        return if (i == x.size) -1 else i
    }

    @Test
    fun `FLOOR at zero is a copy, and a degenerate pad never throws`() {
        for (pad in listOf(snare, tom)) {
            assertContentEquals(pad.samples, Hush.process(pad, mapOf("FLOOR" to 0f)).samples, "FLOOR 0 is not a copy")
        }
        assertEquals(0, Hush.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val one = Snip(floatArrayOf(0.5f), 1, rate)
        assertContentEquals(one.samples, Hush.process(one, mapOf("FLOOR" to 1f)).samples, "a one-frame pad was changed")
        val silent = Snip(FloatArray(1000), 1, rate)
        assertContentEquals(silent.samples, Hush.process(silent, mapOf("FLOOR" to 1f)).samples, "a silent pad was changed")
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val out = Hush.process(snare, mapOf("FLOOR" to bad, "CLOSE" to bad))
            assertTrue(out.samples.all { it.isFinite() }, "a non-finite macro broke the render")
            assertContentEquals(Hush.process(snare).samples, out.samples, "a non-finite macro was not settled to the default")
        }
    }

    @Test
    fun `the gate shuts where the level falls FLOOR under the peak, and the sound before it is untouched`() {
        val pad = decaying(2f)
        for (db in listOf(12f, 24f, 36f)) {
            val close = 0.5f
            val out = Hush.process(pad, mapOf("FLOOR" to Hush.floorFor(db), "CLOSE" to close)).samples
            val crosses = db / 60.0
            // The detector looks half its window ahead, so it sees the fall half a window late; then it holds.
            val shutStarts = crosses + Hush.WINDOW_SEC / 2 + Hush.HOLD_SEC
            val first = (0 until out.size).first { out[it] != pad.samples[it] }
            val silent = silentFrom(out)
            println("HUSH ${db.toInt()} dB under: first change at ${"%.4f".format(first.toDouble() / rate)} s (expected ${"%.4f".format(shutStarts)}), silent from ${"%.4f".format(silent.toDouble() / rate)} s (expected ${"%.4f".format(shutStarts + Hush.closeSec(close))})")
            assertEquals(shutStarts, first.toDouble() / rate, 0.004, "the gate at $db dB did not start to shut where the level crossed")
            assertEquals(shutStarts + Hush.closeSec(close), silent.toDouble() / rate, 0.004, "the gate at $db dB did not reach silence over CLOSE")
            assertEquals(pad.frameCount, out.size, "the gate changed the length")
        }
    }

    @Test
    fun `CLOSE sets how long the shut takes, and it reaches true zero`() {
        val pad = decaying(2f)
        for (close in listOf(0f, 0.5f, 1f)) {
            val out = Hush.process(pad, mapOf("FLOOR" to Hush.floorFor(24f), "CLOSE" to close)).samples
            val first = (0 until out.size).first { out[it] != pad.samples[it] }
            val took = (silentFrom(out) - first).toDouble() / rate
            println("HUSH CLOSE $close: shut in ${"%.4f".format(took)} s, asked ${"%.4f".format(Hush.closeSec(close))} s")
            assertEquals(Hush.closeSec(close).toDouble(), took, 0.002, "CLOSE $close did not take its time")
        }
        assertEquals(Hush.CLOSE_SLOW_SEC, Hush.closeSec(0f), 1e-6f)
        assertEquals(Hush.CLOSE_FAST_SEC, Hush.closeSec(1f), 1e-6f)
        assertEquals(0.37f, Hush.closeFor(Hush.closeSec(0.37f)), 1e-5f, "closeFor is not the inverse of closeSec")
        assertEquals(0.37f, Hush.floorFor(Hush.floorDb(0.37f)), 1e-5f, "floorFor is not the inverse of floorDb")
    }

    @Test
    fun `a level hovering at the floor shuts once and does not chatter`() {
        // A tone held at the floor, swung 2 dB either side of it at 2 Hz: under the floor for 250 ms at a time,
        // never the 4 dB above it that reopens a shut gate. The opening hit makes the floor mean something.
        val db = 20f
        val floorGain = 10.0.pow(-db / 20.0)
        val n = 2 * rate
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val level = if (t < 0.05) 1.0 else floorGain * 10.0.pow(2.0 * sin(2.0 * PI * 2.0 * t) / 20.0)
            (0.9 * level * sin(2.0 * PI * 220.0 * t)).toFloat()
        }
        val pad = Snip(x, 1, rate)
        val g = Hush.gains(pad, Hush.floorFor(db), 1f)
        var reopenings = 0
        for (i in 1 until g.size) if (g[i] > g[i - 1]) reopenings++
        assertEquals(0, reopenings, "the gate chattered on a level hovering at its floor")
        assertTrue(g.last() == 0f, "the gate never shut on a level that spent 250 ms under its floor")
    }

    @Test
    fun `a second hit reopens the gate before it lands, and its attack is untouched`() {
        val one = decaying(0.5f, dbPerSec = 120.0).samples
        val gap = (0.6f * rate).toInt()
        val x = FloatArray(gap + one.size).also { for (i in one.indices) { it[i] = one[i]; it[gap + i] = one[i] } }
        val pad = Snip(x, 1, rate)
        val out = Hush.process(pad, mapOf("FLOOR" to Hush.floorFor(24f), "CLOSE" to 1f)).samples
        assertTrue(out.slice(gap - 200 until gap - 100).all { it == 0f }, "the gate was not shut between the hits")
        for (i in gap until gap + (0.1f * rate).toInt()) assertEquals(x[i], out[i], 1e-6f, "the second hit's frame ${i - gap} moved")
    }

    @Test
    fun `a pad that rises into its peak keeps its quiet head`() {
        // A FADE IN: 300 ms from silence to full, then held. Nothing has reached the opening level at the start,
        // so the gate cannot shut on the head it has not heard yet, and a held level never falls under the floor.
        val n = rate
        val x = FloatArray(n) { i -> val t = i.toDouble() / rate; (minOf(1.0, t / 0.3) * 0.8 * sin(2.0 * PI * 220.0 * t)).toFloat() }
        val pad = Snip(x, 1, rate)
        assertContentEquals(x, Hush.process(pad, mapOf("FLOOR" to 1f, "CLOSE" to 1f)).samples, "the gate shut a rising head")
    }

    @Test
    fun `on a snare through ROOM it is the gated reverb - the hit and the early room stay, the tail is cut`() {
        val roomed = Treatments.apply("roomed", snare, 1f).snip
        val out = Hush.process(roomed, mapOf("FLOOR" to Hush.floorFor(18f))).samples
        val silent = silentFrom(out)
        println("HUSH on the roomed snare: ${roomed.frameCount.toFloat() / rate} s long, silent from ${"%.3f".format(silent.toFloat() / rate)} s")
        assertTrue(silent > 0 && silent < roomed.frameCount * 0.6, "the room's tail was not cut: silent from $silent of ${roomed.frameCount}")
        for (i in 0 until (0.1f * rate).toInt()) assertEquals(roomed.samples[i], out[i], 1e-6f, "frame $i of the hit moved")
        assertEquals(roomed.peak(), Snip(out, roomed.channels, rate).peak(), 1e-6f, "the gate changed the loudness")
    }

    @Test
    fun `the section is deterministic, links its channels, and ignores unknown keys`() {
        val a = Hush.process(tom, mapOf("FLOOR" to 0.8f))
        assertContentEquals(a.samples, Hush.process(tom, mapOf("FLOOR" to 0.8f)).samples, "HUSH is not deterministic")
        assertContentEquals(a.samples, Hush.process(tom, mapOf("FLOOR" to 0.8f, "DROP" to 0.9f)).samples, "an unknown key moved the gate")
        assertContentEquals(Hush.process(tom, mapOf("FLOOR" to 1f)).samples, Hush.process(tom, mapOf("FLOOR" to 7f)).samples, "FLOOR 7 was not clamped to 1")
        // One side loud, the other a tenth of it: one detector, so both sides get the same gain and the quiet
        // side is not shut early on its own level.
        val stereo = Snip(FloatArray(tom.frameCount * 2) { if (it % 2 == 0) tom.samples[it / 2] else 0.1f * tom.samples[it / 2] }, 2, tom.sampleRate)
        val out = Hush.process(stereo, mapOf("FLOOR" to 0.8f))
        assertEquals(2, out.channels)
        assertTrue(out.samples.any { it == 0f } && out.samples.any { it != 0f }, "the gate did not shut the tom at FLOOR 0.8")
        for (f in 0 until out.frameCount) {
            assertEquals(0.1f * out.samples[f * 2], out.samples[f * 2 + 1], 1e-6f, "the sides were gated apart at $f")
        }
    }
}
