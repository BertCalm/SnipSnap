package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WahTest {

    private val rate = WavWriter.MPC_SAMPLE_RATE

    /** The power-weighted log-frequency centroid of one Hann window: 48 log-spaced Goertzel probes, 100 Hz to 8 kHz. */
    private fun centroidHz(x: FloatArray, from: Int, n: Int): Double {
        var num = 0.0
        var den = 0.0
        for (b in 0 until 48) {
            val f = 100.0 * 80.0.pow(b / 47.0)
            val c = 2.0 * cos(2.0 * PI * f / rate)
            var s1 = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
                val s = x[from + i] * hann + c * s1 - s2
                s2 = s1
                s1 = s
            }
            val p = s1 * s1 + s2 * s2 - c * s1 * s2
            num += p * ln(f)
            den += p
        }
        return if (den <= 0.0) 0.0 else exp(num / den)
    }

    /** The centroid of the first [ms] of [s]'s first channel, a 1024-sample window every 5 ms. */
    private fun centroidTrack(s: Snip, ms: Int): List<Double> {
        val x = FloatArray(s.frameCount) { s.samples[it * s.channels] }
        val hop = rate / 200
        val out = mutableListOf<Double>()
        var at = 0
        while (at * 1000 / rate < ms && at + 1024 <= x.size) {
            out += centroidHz(x, at, 1024)
            at += hop
        }
        return out
    }

    private fun octaves(track: List<Double>) = ln(track.max() / track.min()) / ln(2.0)

    /** How long, in ms, the track keeps differing from where it ends up (its median over the last 20 windows) by more than a quarter octave. */
    private fun movingMs(track: List<Double>): Int {
        val final = track.takeLast(20).sorted()[10]
        val last = track.indexOfLast { abs(ln(it / final) / ln(2.0)) > 0.25 }
        return (last + 1) * 5
    }

    private fun endDb(s: Snip): Double {
        val n = (0.02f * s.sampleRate).toInt() * s.channels
        var peak = 0f
        var end = 0f
        for (i in s.samples.indices) {
            val a = abs(s.samples[i])
            if (a > peak) peak = a
            if (i >= s.samples.size - n && a > end) end = a
        }
        return 20.0 * kotlin.math.log10(maxOf(end / peak, 1e-12f).toDouble())
    }

    @Test
    fun `the three moves have the shapes the design says`() {
        assertEquals(0f, Wah.autoAt(0f, 2f), 1e-6f)
        assertEquals(1f, Wah.autoAt(0.25f, 2f), 1e-5f)
        assertEquals(0f, Wah.rockAt(0f, 1f), 1e-6f)
        assertEquals(1f, Wah.rockAt(0.25f, 1f), 1e-5f)
        assertEquals(0.5f, Wah.rockAt(0.5f, 1f), 1e-5f)
        assertEquals(0f, Wah.rockAt(0.75f, 1f), 1e-5f)
        assertEquals(0f, Wah.rockAt(5f, 1f), 0f)
        assertEquals(1f, Wah.touchAt(0f), 1e-6f)
        assertEquals(0.5f, Wah.touchAt(-4f), 1e-6f)
        assertEquals(0f, Wah.touchAt(-8f), 1e-6f)
        assertEquals(0f, Wah.touchAt(-30f), 0f)
        assertEquals(280f, Wah.centreHz(0f, rate), 0.01f)
        assertEquals(2800f, Wah.centreHz(1f, rate), 0.1f)
        assertEquals(280f * kotlin.math.sqrt(10f), Wah.centreHz(0.5f, rate), 0.5f)
        assertEquals(0.45f * 4000f, Wah.centreHz(1f, 4000), 0.01f, "the centre is kept under 0.45 of the rate")
    }

    @Test
    fun `reach is the weights times one and a half, capped, so the pad sheet's default AMT is full strength`() {
        assertEquals(0f, Wah.reach(mapOf()), 0f)
        assertEquals(1f, Wah.reach(mapOf("AUTO" to 0.7f)), 0f)
        assertEquals(0.75f, Wah.reach(mapOf("AUTO" to 0.5f)), 1e-6f)
        assertEquals(1f, Wah.reach(mapOf("TOUCH" to 1f, "ROCK" to 1f)), 0f)
    }

    @Test
    fun `zero weights are a bit-exact copy, whatever SPEED and Q say`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val out = Wah.process(snare, mapOf("SPEED" to 0.9f, "Q" to 0.1f))
        assertTrue(out.samples.contentEquals(snare.samples), "no weight, no wah")
        assertTrue(out.samples !== snare.samples, "a copy, not the same array")
    }

    @Test
    fun `a wah is deterministic, finite, in range, peak-matched, and keeps a doubled pad stereo-identical`() {
        val choir = Vox.render(VoxVoice.CHOIR)
        val a = Wah.process(choir, mapOf("AUTO" to 1f, "TOUCH" to 0.4f))
        val b = Wah.process(choir, mapOf("AUTO" to 1f, "TOUCH" to 0.4f))
        assertTrue(a.samples.contentEquals(b.samples), "deterministic")
        assertTrue(a.samples.all { it.isFinite() && it in -1f..1f }, "finite and in range")
        assertTrue(abs(a.peak() - choir.peak()) < 0.05f, "peak ${a.peak()} against the input's ${choir.peak()}")
        val mono = Thump.render(ThumpVoice.SNARE)
        val doubled = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, mono.sampleRate)
        val out = Wah.process(doubled, mapOf("AUTO" to 1f, "TOUCH" to 1f, "ROCK" to 1f))
        for (f in 0 until doubled.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "frame $f: the two channels differ")
    }

    /**
     * What makes AUTO a wah and not a filter: the centroid of the first 600 ms covers at least 1.5
     * octaves and is still moving at least 100 ms in (the shipped CONTOUR recipe this section
     * replaces settled in 22 to 37 ms on the guitars). The spike measured 2.56 octaves on the choir
     * and 2.82 on JANGLE after the landing amp; measure the dry JANGLE note too and report it.
     */
    @Test
    fun `AUTO sweeps the centroid by at least one and a half octaves and keeps it moving`() {
        val jangle = Magnet.render(MagnetVoice.JANGLE)
        val sources = mapOf(
            "choir" to Vox.render(VoxVoice.CHOIR),
            "jangle after its amp" to Magnet.landingChain(MagnetVoice.JANGLE).process(jangle),
        )
        for ((name, src) in sources) {
            val track = centroidTrack(Wah.process(src, mapOf("AUTO" to 1f)), 600)
            val oct = octaves(track)
            val moving = movingMs(track)
            assertTrue(oct >= 1.5, "$name: AUTO covers only $oct octaves of centroid in the first 600 ms (the bar is 1.5)")
            assertTrue(moving >= 100, "$name: AUTO settles after only $moving ms (the bar is 100)")
        }
    }

    @Test
    fun `TOUCH opens on the hit and closes as the note dies`() {
        val random = java.util.Random(7)
        val x = FloatArray(rate) { i -> (random.nextGaussian() * 0.3 * exp(-i / (0.25 * rate))).toFloat() }
        val out = Wah.process(Snip(x, 1, rate), mapOf("TOUCH" to 1f))
        val early = centroidHz(out.samples, (0.03 * rate).toInt(), 1024)
        val late = centroidHz(out.samples, (0.5 * rate).toInt(), 1024)
        assertTrue(early / late >= 2.0, "TOUCH: the centroid is $early Hz at 30 ms and $late Hz at 500 ms, under one octave apart")
    }

    @Test
    fun `ROCK opens once and then waits at the heel`() {
        val random = java.util.Random(11)
        val x = FloatArray(rate * 2) { (random.nextGaussian() * 0.3).toFloat() }
        val out = Wah.process(Snip(x, 1, rate), mapOf("ROCK" to 1f))
        val atToe = centroidHz(out.samples, (0.22 * rate).toInt(), 1024)
        val parked = centroidHz(out.samples, (1.5 * rate).toInt(), 1024)
        assertTrue(atToe / parked >= 2.0, "ROCK: the centroid is $atToe Hz at the toe and $parked Hz parked, under one octave apart")
    }

    @Test
    fun `a wah in front of a guitar's landing amp still ends clean`() {
        for (voice in MagnetVoice.values()) {
            val landed = Magnet.landingChain(voice).withSection("wah", mapOf("AUTO" to 1f)).process(Magnet.render(voice))
            val end = endDb(landed)
            assertTrue(end <= -50.0, "$voice: the last 20 ms ends $end dB under the peak, over the -50 bar")
        }
    }
}
