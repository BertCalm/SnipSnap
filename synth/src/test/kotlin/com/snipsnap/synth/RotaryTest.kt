package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROTARY's own claims (the doc on [Rotary]). The rack's shared contract (deterministic, finite, peak-matched,
 * stereo intact) is FxTest's and covers ROTARY through FxChain.SECTIONS. What is pinned here: SPIN 0 is a bit-exact
 * bypass; the two halves sum back to the pad when nothing moves and the onset keeps its frame (no latency); it starts
 * open; the horn moves level and pitch together at SPEED; the drum moves level, and a low tone's pitch a fraction of the
 * horn's; RAMP spins up.
 */
class RotaryTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    private fun sine(hz: Float, seconds: Float, amp: Float = 0.5f): Snip =
        Snip(FloatArray((seconds * rate).toInt()) { amp * sin(2.0 * PI * hz * it / rate).toFloat() }, 1, rate)

    /** RMS in dB over windows of [ms], hop the same. */
    private fun envelopeDb(s: Snip, ms: Float): FloatArray {
        val w = (ms / 1000f * rate).toInt()
        return FloatArray(s.frameCount / w) { k ->
            var sum = 0.0
            for (i in k * w until (k + 1) * w) sum += s.samples[i].toDouble() * s.samples[i]
            (10.0 * log10(sum / w + 1e-20)).toFloat()
        }
    }

    /** How many times the envelope dips: falls from its top quarter to its bottom quarter (hysteresis rides over ripple). */
    private fun dips(env: FloatArray, from: Int = 0, until: Int = env.size, top: Float = env.max(), bottom: Float = env.min()): Int {
        val hi = bottom + 0.75f * (top - bottom)
        val lo = bottom + 0.25f * (top - bottom)
        var high = true
        var n = 0
        for (i in from until until) {
            if (high && env[i] < lo) { high = false; n++ } else if (!high && env[i] > hi) high = true
        }
        return n
    }

    /** The highest over the lowest local frequency, each measured over [cycles] cycles between upward zero crossings. */
    private fun pitchSwing(s: Snip, cycles: Int, skipSec: Float = 0.1f): Float {
        val ups = ArrayList<Double>()
        val x = s.samples
        for (i in (skipSec * rate).toInt() until x.size - 1) {
            if (x[i] <= 0f && x[i + 1] > 0f) ups.add(i + x[i] / (x[i] - x[i + 1]).toDouble())
        }
        var lo = Double.MAX_VALUE
        var hi = 0.0
        var k = 0
        while (k + cycles < ups.size - (0.1 * ups.size).toInt()) {
            val f = cycles / (ups[k + cycles] - ups[k])
            lo = minOf(lo, f)
            hi = maxOf(hi, f)
            k += cycles
        }
        return (hi / lo).toFloat()
    }

    @Test
    fun `SPIN 0 is a bit-exact bypass, and a degenerate pad never throws`() {
        for (pad in listOf(snare, choir)) {
            assertContentEquals(pad.samples, Rotary.process(pad, mapOf("SPIN" to 0f, "SPEED" to 1f, "RAMP" to 1f)).samples, "SPIN 0 is not a copy")
        }
        assertEquals(0, Rotary.process(Snip(FloatArray(0), 1, rate)).frameCount, "an empty pad grew")
        val nan = Rotary.process(choir, mapOf("SPIN" to Float.NaN, "SPEED" to Float.NaN, "RAMP" to Float.NaN))
        assertContentEquals(Rotary.process(choir).samples, nan.samples, "a NaN was not settled to the default")
        val silent = Snip(FloatArray(4410), 1, rate)
        assertContentEquals(silent.samples, Rotary.process(silent).samples, "a silent pad is not left alone")
        val tiny = Rotary.process(Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate), mapOf("SPIN" to 1f))
        assertEquals(3, tiny.frameCount, "a three-frame pad changed length")
        for (pad in listOf(snare, choir)) assertEquals(pad.frameCount, Rotary.process(pad, mapOf("SPIN" to 1f)).frameCount, "the pad changed length")
    }

    @Test
    fun `the two halves sum back to the pad when nothing moves, and the onset keeps its frame`() {
        // A spin too small to move anything: what is left is the crossover and the base delay taken back out.
        for (pad in listOf(snare, choir)) {
            val out = Rotary.process(pad, mapOf("SPIN" to 1e-4f, "SPEED" to 1f))
            var worst = 0f
            for (i in pad.samples.indices) worst = maxOf(worst, abs(out.samples[i] - pad.samples[i]))
            assertTrue(worst < 0.01f * pad.peak(), "a still rotor does not give the pad back: worst error $worst of ${pad.peak()}")
        }
        // A click at full spin lands within the horn's swing of where it was (23 frames at 44.1 kHz), not a base delay late.
        val click = Snip(FloatArray(rate).also { it[2000] = 1f }, 1, rate)
        for (speed in listOf(0f, 0.5f, 1f)) {
            val out = Rotary.process(click, mapOf("SPIN" to 1f, "SPEED" to speed))
            val at = out.samples.indices.maxBy { abs(out.samples[it]) }
            assertTrue(abs(at - 2000) <= 25, "SPEED $speed moved the click from frame 2000 to $at")
        }
    }

    @Test
    fun `it starts open, at the loudest point of the swirl`() {
        for (speed in listOf(0f, 0.5f, 1f)) for (hz in listOf(150f, 3000f)) {
            val env = envelopeDb(Rotary.process(sine(hz, 2f), mapOf("SPIN" to 1f, "SPEED" to speed, "RAMP" to 0f)), 20f)
            assertTrue(env[0] > env.max() - 1.5f, "SPEED $speed at $hz Hz starts ${env.max() - env[0]} dB under its loudest")
        }
    }

    @Test
    fun `the horn moves level at SPEED, deeper with SPIN`() {
        val tone = sine(8000f, 2f)
        val full = envelopeDb(Rotary.process(tone, mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 0f)), 5f)
        val light = envelopeDb(Rotary.process(tone, mapOf("SPIN" to 0.3f, "SPEED" to 1f, "RAMP" to 0f)), 5f)
        val fullDepth = full.max() - full.min()
        val lightDepth = light.max() - light.min()
        assertTrue(fullDepth > 6f, "the horn's level swing at SPIN 1 is only $fullDepth dB")
        assertTrue(lightDepth < 4f && lightDepth < fullDepth - 3f, "SPIN 0.3 swings $lightDepth dB against SPIN 1's $fullDepth")

        // FAST_HZ is 7, so 14 turns in 2 s; the chorale's 0.7 Hz turns once or twice.
        val fast = dips(full)
        val slow = dips(envelopeDb(Rotary.process(tone, mapOf("SPIN" to 1f, "SPEED" to 0f)), 5f))
        assertTrue(fast in 12..16, "the horn dipped $fast times in 2 s at SPEED 1")
        assertTrue(slow in 1..2, "the horn dipped $slow times in 2 s at SPEED 0")
    }

    @Test
    fun `the horn's pitch swings with its level, and a low tone's moves a fraction of it`() {
        // At 7 Hz a horn of 0.52 ms swings its pitch about 2.3 percent each way.
        val horn = pitchSwing(Rotary.process(sine(8000f, 2f), mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 0f)), 16)
        assertTrue(horn in 1.03f..1.07f, "the horn's pitch swing at SPIN 1, SPEED 1 is $horn")
        val light = pitchSwing(Rotary.process(sine(8000f, 2f), mapOf("SPIN" to 0.2f, "SPEED" to 1f, "RAMP" to 0f)), 16)
        assertTrue(light < 1.015f, "SPIN 0.2 swings the pitch $light")

        // The drum: its level moves (BASS_AM 0.4 is 4.4 dB) and it turns a little slower, but its path has no
        // Doppler, so a low tone's pitch moves a fraction of the horn's. What it does move (0.9 percent at 100 Hz,
        // measured) is the crossover's: the two halves rise and fall at different rates, so the one-pole's phase
        // between them wanders, about the rotor's speed over the crossover's, whatever the tone.
        val low = Rotary.process(sine(100f, 2f), mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 0f))
        val drum = pitchSwing(low, 4)
        assertTrue(drum < 1.015f && drum - 1f < (horn - 1f) / 3f, "the drum's pitch swings $drum against the horn's $horn")
        val env = envelopeDb(low, 20f)
        assertTrue(env.max() - env.min() > 3f, "the drum's level swing is only ${env.max() - env.min()} dB")
        assertTrue(dips(env) in 10..13, "the drum dipped ${dips(env)} times in 2 s at SPEED 1 (about 6.1 Hz)")
    }

    @Test
    fun `RAMP starts at the chorale and spins up to SPEED`() {
        val tone = sine(8000f, 3f)
        val steady = envelopeDb(Rotary.process(tone, mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 0f)), 5f)
        val ramped = envelopeDb(Rotary.process(tone, mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 1f)), 5f)
        val half = 100 // windows of 5 ms in 0.5 s
        val steadyEarly = dips(steady, 0, half, steady.max(), steady.min())
        val rampedEarly = dips(ramped, 0, half, ramped.max(), ramped.min())
        assertTrue(steadyEarly >= 3, "steady at SPEED 1 dipped $steadyEarly times in the first 0.5 s")
        assertTrue(rampedEarly <= steadyEarly - 2, "RAMP 1 dipped $rampedEarly times in the first 0.5 s against steady's $steadyEarly")
        // By the last second the horn has nearly reached 7 Hz.
        val late = dips(ramped, 400, 600, ramped.max(), ramped.min())
        assertTrue(late >= 6, "RAMP 1 dipped only $late times in its last second")

        assertEquals(Rotary.hz(1f), Rotary.startHz(Rotary.hz(1f), 0f), 1e-4f, "RAMP 0 does not start at SPEED")
        assertEquals(Rotary.SLOW_HZ, Rotary.startHz(Rotary.hz(1f), 1f), 1e-4f, "RAMP 1 does not start at the chorale")
    }

    @Test
    fun `a tap of the ROTARY chip is the Spin-up clip at SPIN 0_7, AMT 1 is the clip, and AMT 0 is a copy`() {
        // The gate (2026-11-13): Spin-up, SPIN 1 at SPEED 1 with RAMP 1, heard through the rack. SPEED and RAMP are left at
        // their defaults, each its own neutral, so no AMT moves them; SPIN fades toward none.
        val character = Treatments.chain("spun").rotary!!
        assertEquals(setOf("SPIN"), character.keys, "the spun character set more than SPIN")
        assertEquals(1f, Rotary.MACROS.single { it.name == "SPEED" }.default, "SPEED's default is no longer Spin-up's")
        assertEquals(1f, Rotary.MACROS.single { it.name == "RAMP" }.default, "RAMP's default is no longer Spin-up's")
        val clip = mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 1f)
        assertContentEquals(FxChain(rotary = clip).process(choir).samples, Treatments.apply("spun", choir, 1f).snip.samples, "AMT 1 is not the Spin-up clip")
        val tap = 0.7f
        assertEquals(0.7f, Treatments.chain("spun", tap).rotary!!.getValue("SPIN"), 1e-6f, "a tap is not SPIN 0.7")
        val heard = FxChain(rotary = mapOf("SPIN" to 0.7f, "SPEED" to 1f, "RAMP" to 1f)).process(choir).samples
        val got = Treatments.apply("spun", choir, tap).snip.samples
        assertEquals(heard.size, got.size)
        var worst = 0f
        for (i in heard.indices) worst = maxOf(worst, abs(heard[i] - got[i]))
        assertTrue(worst < 1e-6f, "the tap is not the Spin-up clip at SPIN 0.7: worst sample difference $worst")
        // How far under the clip the tap swirls: SPIN tops out at 1, so a tap cannot reach it. On the horn's own band, once
        // it has spun up, the tap's level swing is about four fifths of the clip's in dB (8.4 against 10.5, measured: the
        // horn's trough is a gain, so its dB do not scale with SPIN) and its pitch swing about three quarters (3.7 percent
        // peak to peak against 4.9).
        val tone = sine(8000f, 3f)
        val late = { spin: Float ->
            val out = Rotary.process(tone, mapOf("SPIN" to spin))
            val tail = Snip(out.samples.copyOfRange(2 * rate, 3 * rate), 1, rate)
            val env = envelopeDb(tail, 5f)
            Pair(env.max() - env.min(), pitchSwing(tail, 16, 0f))
        }
        val (clipLevel, clipPitch) = late(1f)
        val (tapLevel, tapPitch) = late(0.7f)
        println("ROTARY tap against the clip, last second at 8 kHz: level swing $tapLevel dB against $clipLevel, pitch swing $tapPitch against $clipPitch")
        assertTrue(tapLevel in 0.7f * clipLevel..0.9f * clipLevel, "the tap's level swing is $tapLevel dB against the clip's $clipLevel")
        assertTrue(tapPitch - 1f in 0.65f * (clipPitch - 1f)..0.85f * (clipPitch - 1f), "the tap's pitch swing is $tapPitch against the clip's $clipPitch")
        assertContentEquals(choir.samples, Treatments.apply("spun", choir, 0f).snip.samples, "AMT 0 is not a copy")
    }
}
