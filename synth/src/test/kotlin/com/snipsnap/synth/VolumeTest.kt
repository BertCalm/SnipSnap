package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VOLUME's own claims (the doc on [Volume]). The rack's shared contract (finite, deterministic,
 * stereo intact) is FxTest's and covers VOLUME through FxChain.SECTIONS; the peak-match clause
 * excuses it there, and `a fade that takes the peak is lifted only up to the cap` is the replacement.
 */
class VolumeTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)
    private val jangle = Magnet.render(MagnetVoice.JANGLE)

    /** A held note: a constant-level sine for [seconds], so its loudest moment is nowhere near the start. */
    private fun held(seconds: Float, level: Float = 0.5f): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { (level * kotlin.math.sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }, 1, rate)
    }

    /** A flat line at [level]: every output sample is then exactly the gain curve times the level. */
    private fun flat(seconds: Float, level: Float = 0.5f): Snip =
        Snip(FloatArray((seconds * rate).toInt()) { level }, 1, rate)

    @Test
    fun `RISE at zero is a copy, and a degenerate pad never throws`() {
        for (pad in listOf(snare, choir, jangle)) {
            assertContentEquals(pad.samples, Volume.process(pad, mapOf("RISE" to 0f)).samples, "RISE 0 is not a copy")
        }
        val empty = Snip(FloatArray(0), 1, rate)
        assertEquals(0, Volume.process(empty).frameCount, "an empty pad grew")
        // A pad too short for a ramp (a rise under two frames) is a copy; a few frames more is a short ramp, still finite.
        val one = Snip(floatArrayOf(0.5f), 1, rate)
        assertContentEquals(one.samples, Volume.process(one, mapOf("RISE" to 1f)).samples, "a one-frame pad was changed")
        val three = Snip(floatArrayOf(0.5f, -0.5f, 0.25f), 1, rate)
        val shortRamp = Volume.process(three, mapOf("RISE" to 1f))
        assertEquals(3, shortRamp.frameCount, "a three-frame pad changed length")
        assertTrue(shortRamp.samples.all { it.isFinite() }, "a three-frame pad broke the render")
        val nan = Volume.process(snare, mapOf("RISE" to Float.NaN))
        assertTrue(nan.samples.all { it.isFinite() }, "a NaN RISE broke the render")
        assertContentEquals(snare.samples, nan.samples, "a NaN RISE was not settled")
    }

    @Test
    fun `the rise starts at silence, climbs smoothly and never overshoots, then the pad is untouched`() {
        val pad = flat(1f)
        val out = Volume.process(pad, mapOf("RISE" to 0.5f))
        val rise = Volume.riseFrames(0.5f, rate, pad.frameCount)
        assertEquals((0.5f * Volume.RISE_MAX_SEC * rate).toInt(), rise, "the rise is not RISE x RISE_MAX_SEC")
        assertEquals(0f, out.samples[0], "the pad does not start from silence")
        var worstStep = 0f
        for (f in 1 until rise) {
            assertTrue(out.samples[f] >= out.samples[f - 1], "the rise fell back at frame $f")
            worstStep = maxOf(worstStep, out.samples[f] - out.samples[f - 1])
        }
        // A raised cosine has no corner: its steepest step is pi/2 of the average step, far from a ramp's jump.
        val averageStep = 0.5f / rise
        assertTrue(worstStep < 1.6f * averageStep, "the rise has a corner: steepest step $worstStep vs average $averageStep")
        for (f in rise until pad.frameCount) {
            assertEquals(0.5f, out.samples[f], 1e-3f, "frame $f after the rise is not at full level")
        }
    }

    @Test
    fun `the rise takes at most 70 percent of a short pad`() {
        val pad = flat(0.5f)
        val rise = Volume.riseFrames(1f, rate, pad.frameCount)
        assertEquals((pad.frameCount * Volume.RISE_CAP).toInt(), rise, "the cap is not RISE_CAP of the pad")
        assertTrue(rise < (Volume.RISE_MAX_SEC * rate).toInt(), "the cap did not bind on a half-second pad")
        val out = Volume.process(pad, mapOf("RISE" to 1f))
        assertEquals(0.5f, out.samples[pad.frameCount - 1], 1e-3f, "the last frame is not at full level")
    }

    @Test
    fun `a held note keeps its peak, because its loudest moment comes after the rise`() {
        val note = held(2f)
        for (rise in listOf(0.25f, 0.56f, 0.8f, 1f)) {
            val out = Volume.process(note, mapOf("RISE" to rise))
            assertEquals(note.peak(), out.peak(), 1e-3f, "RISE $rise moved a held note's peak")
        }
    }

    @Test
    fun `a fade that takes the peak is lifted only up to the cap`() {
        val out = Volume.process(snare, mapOf("RISE" to 1f))
        // Find a frame well after the rise where the dry pad still has signal, and read the lift off it.
        val rise = Volume.riseFrames(1f, rate, snare.frameCount)
        var at = -1
        var best = 0f
        for (f in rise until snare.frameCount) if (abs(snare.samples[f]) > best) { best = abs(snare.samples[f]); at = f }
        assertTrue(at >= 0 && best > 0f, "the snare has no signal after the rise to measure the lift on")
        val lift = out.samples[at] / snare.samples[at]
        println("VOLUME snare at RISE 1: rise $rise frames, lift x${"%.2f".format(lift)}, peak ${"%.3f".format(out.peak())} vs ${"%.3f".format(snare.peak())}")
        assertTrue(lift >= 1f - 1e-4f, "the fade made the pad quieter after the rise")
        assertTrue(lift <= Volume.MAX_LIFT + 1e-3f, "the lift passed the cap: x$lift")
        assertTrue(out.peak() <= snare.peak() + 1e-4f, "the fade made the pad louder than it was")
        assertTrue(out.peak() < snare.peak() - 0.05f, "the snare's peak survived a full fade: the cap is not what holds it")
    }

    @Test
    fun `RISE is not a dead knob, and the section is deterministic and keeps stereo intact`() {
        for (pad in listOf(choir, jangle)) {
            val short = Volume.process(pad, mapOf("RISE" to 0.3f)).samples
            val long = Volume.process(pad, mapOf("RISE" to 0.8f)).samples
            assertTrue(!short.contentEquals(long), "RISE 0.3 and 0.8 sound the same")
            assertContentEquals(long, Volume.process(pad, mapOf("RISE" to 0.8f)).samples, "VOLUME is not deterministic")
        }
        val stereo = Snip(FloatArray(choir.frameCount * 2) { choir.samples[it / 2] }, 2, choir.sampleRate)
        val out = Volume.process(stereo, mapOf("RISE" to 0.6f))
        assertEquals(2, out.channels, "the channel count changed")
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
    }

    @Test
    fun `a tap of the FADE IN chip rises over 448 ms, and AMT walks the rise`() {
        val tap = 0.7f
        val long = held(2f)
        assertEquals(setOf("RISE"), Treatments.chain("eased").volume!!.keys, "the eased character set more than RISE")
        // At AMT 0 the rack drops the section altogether, so no RISE is read: that is a zero rise.
        fun ms(amount: Float) = Volume.riseFrames(Treatments.chain("eased", amount).volume?.getValue("RISE") ?: 0f, rate, long.frameCount) * 1000f / rate
        println("VOLUME chip: tap ${"%.1f".format(ms(tap))} ms, AMT 1 ${"%.1f".format(ms(1f))} ms, AMT 0.35 ${"%.1f".format(ms(0.35f))} ms")
        assertEquals(448f, ms(tap), 0.1f, "a tap no longer rises over the 448 ms the owner chose")
        assertEquals(640f, ms(1f), 0.1f, "AMT 1 no longer rises over the 640 ms the owner also heard")
        assertTrue(ms(0.35f) < ms(tap), "a lower AMT did not shorten the rise")
        assertEquals(0f, ms(0f), "AMT 0 is not dry")
        // The tap is, sample for sample, the 448 ms clip the owner heard (RISE 0.56).
        for (pad in listOf(choir, jangle)) {
            assertContentEquals(Volume.process(pad, mapOf("RISE" to 0.56f)).samples, Treatments.apply("eased", pad, tap).snip.samples, "the tap is not the heard rise")
        }
        assertContentEquals(choir.samples, Treatments.apply("eased", choir, 0f).snip.samples, "AMT 0 is not a copy")
    }

    @Test
    fun `an unknown key is ignored and an out-of-range RISE is clamped`() {
        val base = Volume.process(choir, mapOf("RISE" to 0.5f)).samples
        assertContentEquals(base, Volume.process(choir, mapOf("RISE" to 0.5f, "SPEED" to 0.9f)).samples, "an unknown key moved the fade")
        assertContentEquals(Volume.process(choir, mapOf("RISE" to 1f)).samples, Volume.process(choir, mapOf("RISE" to 7f)).samples, "RISE 7 was not clamped to 1")
        assertContentEquals(choir.samples, Volume.process(choir, mapOf("RISE" to -3f)).samples, "RISE -3 was not clamped to 0")
    }
}
