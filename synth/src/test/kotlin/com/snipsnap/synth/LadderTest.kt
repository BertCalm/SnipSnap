package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ladder's signature, pinned from the design spec's prototype
 * (docs/superpowers/specs/2026-09-24-resin-ladder-engine-design.md,
 * "Measured, not guessed"): 24 dB/oct, a 1/(1+r) passband loss as
 * resonance rises, and bounded self-oscillation past r = 4. All at the
 * rate every engine synthesises at, because that is where the filter runs.
 */
class LadderTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun db(v: Float) = 20f * log10(maxOf(v, 1e-12f))

    /** Steady-state gain of a small sine through the filter, as a ratio. */
    private fun steadyGain(fc: Float, r: Float, toneHz: Double, amp: Float = 0.02f): Float {
        val ladder = Dsp.Ladder(rate)
        val settle = (0.15f * rate).toInt()
        val measure = (0.15f * rate).toInt()
        var sumSq = 0.0
        for (n in 0 until settle + measure) {
            val x = amp * sin(2.0 * PI * toneHz * n / rate).toFloat()
            val y = ladder.process(x, fc, r)
            if (n >= settle) sumSq += (y * y).toDouble()
        }
        val outRms = sqrt(sumSq / measure).toFloat()
        return outRms / (amp / sqrt(2f))
    }

    /** The tail after a tick: zero input for a second, the last half kept. */
    private fun tailAfterTick(fc: Float, r: Float): FloatArray {
        val ladder = Dsp.Ladder(rate)
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ladder.process(if (i == 0) 1e-3f else 0f, fc, r)
        return out.copyOfRange(n / 2, n)
    }

    private fun rms(xs: FloatArray): Float = sqrt(xs.sumOf { (it * it).toDouble() } / xs.size).toFloat()

    private fun zeroCrossHz(xs: FloatArray): Float {
        var c = 0
        for (i in 1 until xs.size) if ((xs[i - 1] < 0f) != (xs[i] < 0f)) c++
        return c / 2f / (xs.size.toFloat() / rate)
    }

    @Test
    fun `passband is flat and the slope is 24 dB per octave`() {
        // Measured: -0.24 dB at 125 Hz; -49.19 at 4 kHz, -72.40 at 8 kHz (23.2 dB/oct).
        val pass = db(steadyGain(1_000f, 0f, 125.0))
        val at4k = db(steadyGain(1_000f, 0f, 4_000.0))
        val at8k = db(steadyGain(1_000f, 0f, 8_000.0))
        assertTrue(pass > -1f, "passband should be flat: $pass dB at 125 Hz")
        assertTrue(at4k - at8k > 20f, "one octave above the knee should cost > 20 dB: $at4k -> $at8k")
        assertTrue(at8k < -60f, "three octaves up should be gone: $at8k dB")
    }

    @Test
    fun `resonance peaks at the cutoff`() {
        // Measured: +6.85 dB at 975 Hz for fc = 1 kHz, r = 3.5; -12.04 dB there at r = 0.
        var bestHz = 0
        var bestDb = -1e9f
        for (f in 600..1400 step 25) {
            val g = db(steadyGain(1_000f, 3.5f, f.toDouble()))
            if (g > bestDb) { bestDb = g; bestHz = f }
        }
        assertTrue(abs(bestHz - 1_000) <= 50, "peak should sit within 5% of fc, found $bestHz Hz")
        val flat = db(steadyGain(1_000f, 0f, 1_000.0))
        assertTrue(bestDb - flat > 12f, "resonance should lift fc by > 12 dB: $flat -> $bestDb")
    }

    @Test
    fun `resonance thins the bass - the passband drops by about 1 over 1 plus r`() {
        // Measured: -0.17 dB at r = 0, -12.98 dB at r = 3.5 (100 Hz tone, fc = 1 kHz).
        val loss = db(steadyGain(1_000f, 0f, 100.0)) - db(steadyGain(1_000f, 3.5f, 100.0))
        assertTrue(loss in 10f..16f, "r = 3.5 should cost 10-16 dB of passband, cost $loss")
    }

    @Test
    fun `DC gain is exactly 1 over 1 plus r`() {
        // Measured: 0.5000 at r = 0, 0.1111 at r = 3.5 for 0.5 DC in.
        for ((r, expected) in listOf(0f to 0.5f, 3.5f to 0.5f / 4.5f)) {
            val ladder = Dsp.Ladder(rate)
            var y = 0f
            for (i in 0 until (0.3f * rate).toInt()) y = ladder.process(0.5f, 1_000f, r)
            assertTrue(abs(y - expected) < expected * 0.01f, "DC at r=$r: expected $expected, got $y")
        }
    }

    @Test
    fun `past r = 4 the filter sings at the cutoff, bounded`() {
        // Measured: 498 Hz at fc = 500, 1009 Hz at fc = 1000; peak 0.09-0.11.
        for (fc in listOf(500f, 1_000f)) {
            val tail = tailAfterTick(fc, 4.3f)
            assertTrue(tail.all { it.isFinite() }, "fc=$fc blew up")
            assertTrue(rms(tail) > 0.01f, "fc=$fc should self-oscillate, tail rms ${rms(tail)}")
            assertTrue(tail.maxOf { abs(it) } < 1f, "fc=$fc self-oscillation must stay bounded")
            val hz = zeroCrossHz(tail)
            assertTrue(abs(hz - fc) < fc * 0.03f, "fc=$fc should sing within 3%, sang at $hz Hz")
        }
    }

    @Test
    fun `below r = 4 a tick dies`() {
        val tail = tailAfterTick(1_000f, 3.5f)
        assertTrue(rms(tail) < 1e-3f, "r = 3.5 must not self-oscillate, tail rms ${rms(tail)}")
    }

    @Test
    fun `stable under a full-scale saw with the cutoff swept to 16 kHz at full resonance`() {
        // Measured: finite, peak 0.419.
        val ladder = Dsp.Ladder(rate)
        var ph = 0.0
        var peak = 0f
        for (n in 0 until rate) {
            ph += 55.0 / rate
            val saw = (2.0 * (ph - Math.floor(ph)) - 1.0).toFloat()
            val fc = 20f * Math.pow(800.0, n.toDouble() / rate).toFloat()
            val y = ladder.process(saw, fc, 4.3f)
            assertTrue(y.isFinite(), "blew up at sample $n (fc=$fc)")
            if (abs(y) > peak) peak = abs(y)
        }
        assertTrue(peak < 1f, "saturating loop should keep the output inside unity, peak $peak")
    }
}
