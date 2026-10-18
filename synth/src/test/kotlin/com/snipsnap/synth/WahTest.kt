package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WahTest {

    private val rate = WavWriter.MPC_SAMPLE_RATE

    /** The other rate a pad can come in at: the rack must not assume the MPC's. */
    private val hiRate = 48_000

    /** The hop of [centroidTrack], in frames: about 5 ms. */
    private val hop = rate / 200

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
        val out = mutableListOf<Double>()
        var at = 0
        while (at * 1000 / rate < ms && at + 1024 <= x.size) {
            out += centroidHz(x, at, 1024)
            at += hop
        }
        return out
    }

    private fun octaves(track: List<Double>) = ln(track.max() / track.min()) / ln(2.0)

    /** Seeded white noise at 0.3: a broadband source whose own spectrum is flat, so what comes out is the wah's. */
    private fun noise(seed: Long, frames: Int): FloatArray {
        val random = java.util.Random(seed)
        return FloatArray(frames) { (random.nextGaussian() * 0.3).toFloat() }
    }

    /** The rate, in Hz, at which [track] (one value per [hopS] seconds) has the most energy once its mean is removed: a 1 percent log grid from 0.25 to 16 Hz. */
    private fun dominantHz(track: List<Double>, hopS: Double): Double {
        val mean = track.average()
        var best = 0.0
        var bestPower = -1.0
        var g = 0.25
        while (g <= 16.0) {
            var re = 0.0
            var im = 0.0
            for ((i, v) in track.withIndex()) {
                val phase = 2.0 * PI * g * i * hopS
                re += (v - mean) * cos(phase)
                im += (v - mean) * sin(phase)
            }
            val power = re * re + im * im
            if (power > bestPower) { bestPower = power; best = g }
            g *= 1.01
        }
        return best
    }

    /** The power of [x] from frame [from] on at each of [freqs]: Goertzel probes on Hann windows of 8192 frames, half overlapped, averaged. */
    private fun welchPower(x: FloatArray, from: Int, sampleRate: Int, freqs: DoubleArray): DoubleArray {
        val n = 8192
        val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        val power = DoubleArray(freqs.size)
        var segments = 0
        var at = from
        while (at + n <= x.size) {
            for ((b, f) in freqs.withIndex()) {
                val c = 2.0 * cos(2.0 * PI * f / sampleRate)
                var s1 = 0.0
                var s2 = 0.0
                for (i in 0 until n) {
                    val s = x[at + i] * hann[i] + c * s1 - s2
                    s2 = s1
                    s1 = s
                }
                power[b] += s1 * s1 + s2 * s2 - c * s1 * s2
            }
            segments++
            at += n / 2
        }
        return DoubleArray(freqs.size) { power[it] / segments }
    }

    /** [count] probes from [lowHz] up, each [step] times the last. */
    private fun logGrid(lowHz: Double, step: Double, count: Int) = DoubleArray(count) { lowHz * step.pow(it) }

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
     * octaves, where the same pad dry covers under 1.5 (the negative control: a pad's own decay moves
     * its centroid a little, so the bar alone could be met without a wah; measured at 0.53 octave on the
     * choir and 1.13 on JANGLE, channel 0). The shipped CONTOUR recipe this section replaces settled in 22 to
     * 37 ms on the guitars. The spike measured 2.56 octaves on the choir and 2.82 on JANGLE after the
     * landing amp. Whether it keeps moving is the period test's job.
     */
    @Test
    fun `AUTO sweeps the centroid by at least one and a half octaves, which the dry pad does not`() {
        val jangle = Magnet.render(MagnetVoice.JANGLE)
        val sources = mapOf(
            "choir" to Vox.render(VoxVoice.CHOIR),
            "jangle after its amp" to Magnet.landingChain(MagnetVoice.JANGLE).process(jangle),
        )
        for ((name, src) in sources) {
            val dry = octaves(centroidTrack(src, 600))
            assertTrue(dry < 1.5, "$name: the dry pad already covers $dry octaves of centroid in 600 ms, so the 1.5 bar would prove nothing")
            val oct = octaves(centroidTrack(Wah.process(src, mapOf("AUTO" to 1f)), 600))
            assertTrue(oct >= 1.5, "$name: AUTO covers only $oct octaves of centroid in the first 600 ms (the bar is 1.5, the dry pad covers $dry)")
        }
    }

    /**
     * AUTO's period is 1 / f, and SPEED sets f: noise through AUTO at weight 0.5 (reach 0.75, so the
     * position never reaches the toe and the swept centre is a clean sine), the log-centroid track
     * of the output, and the rate at which that track repeats, at SPEED 0, 0.5 and 1.
     */
    @Test
    fun `AUTO's period is one over the rate SPEED sets, at both ends and the default`() {
        for ((speed, hz) in listOf(0f to 0.5, 0.5f to 2.0, 1f to 8.0)) {
            val seconds = 4.0 / hz
            val src = Snip(noise(21, (seconds * rate).toInt()), 1, rate)
            val out = Wah.process(src, mapOf("AUTO" to 0.5f, "SPEED" to speed))
            val track = centroidTrack(out, (seconds * 1000).toInt()).map { ln(it) }
            val measured = dominantHz(track, hop.toDouble() / rate)
            assertTrue(abs(measured / hz - 1.0) <= 0.05, "SPEED $speed: the swept centre repeats at $measured Hz, not within 5 percent of $hz Hz")
        }
    }

    /**
     * Q is a real resonance, the right way round, and the band sits where it should on any pad's
     * rate: ROCK parked at the heel (after its 750 ms gesture) on seeded noise, the Welch-averaged
     * spectrum of the last four seconds. At Q 1 the peak is within 3 percent of 280 Hz at 44.1 and
     * 48 kHz (a filter built at its default 44.1 kHz would sit about 9 percent high, near 305 Hz, at 48 kHz); at Q 1 the peak
     * stands well clear of the surround, and at Q 0 it does not.
     */
    @Test
    fun `Q narrows the band the right way round, and the heel stays at 280 Hz at both sample rates`() {
        val peakGrid = logGrid(200.0, 1.005, 140)
        val surroundGrid = logGrid(1_120.0, 1.05, 15)
        val contrastDb = mutableMapOf<Float, Double>()
        for (sampleRate in listOf(rate, hiRate)) {
            for (q in listOf(1f, 0f)) {
                val src = Snip(noise(33, sampleRate * 5), 1, sampleRate)
                val out = Wah.process(src, mapOf("ROCK" to 1f, "Q" to q)).samples
                val peak = welchPower(out, sampleRate, sampleRate, peakGrid)
                val at = peakGrid[peak.indices.maxBy { peak[it] }]
                val surround = welchPower(out, sampleRate, sampleRate, surroundGrid).average()
                val db = 10.0 * log10(peak.max() / surround)
                if (sampleRate == rate) contrastDb[q] = db
                if (q == 1f) {
                    assertTrue(abs(at / Wah.HEEL_HZ - 1.0) <= 0.03, "$sampleRate Hz, Q 1: the band peaks at $at Hz, not within 3 percent of ${Wah.HEEL_HZ} Hz")
                }
            }
        }
        val tight = contrastDb.getValue(1f)
        val open = contrastDb.getValue(0f)
        assertTrue(tight - open >= 6.0, "Q 1 stands $tight dB over the surround and Q 0 $open dB: Q 1 should be at least 6 dB tighter")
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
