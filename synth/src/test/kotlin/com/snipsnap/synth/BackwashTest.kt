package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackwashTest {
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from).coerceAtLeast(1))
    private fun difference(a: FloatArray, b: FloatArray): Double =
        sqrt((0 until maxOf(a.size, b.size)).sumOf { val d = a.getOrElse(it) { 0f } - b.getOrElse(it) { 0f }; d.toDouble() * d } / maxOf(a.size, b.size))

    @Test fun `every corner is finite bounded mono and shorter than a loop`() {
        for (voice in BackwashVoice.entries) {
            val names = Backwash.defaults(voice).keys.toList()
            for (bits in 0 until (1 shl names.size)) {
                val m = names.mapIndexed { k, n -> n to ((bits shr k) and 1).toFloat() }.toMap()
                val s = Backwash.render(voice, m)
                assertEquals(1, s.channels)
                assertEquals(44100, s.sampleRate)
                assertTrue(s.samples.size < 44100 * 1.5, "$voice $bits duration")
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice $bits bounds")
                assertTrue(s.peak() > .01f, "$voice $bits silence")
                assertTrue(abs(s.samples.average()) < .005, "$voice $bits DC")
                assertTrue(s.samples.last() == 0f, "tail must reach zero (either IEEE sign)")
            }
        }
    }

    @Test fun `controls move at low and middle settings and malformed inputs settle`() {
        for (voice in BackwashVoice.entries) {
            val d = Backwash.defaults(voice)
            val baseline = Backwash.render(voice).samples
            assertContentEquals(baseline, Backwash.render(voice, mapOf("UNKNOWN" to 1f)).samples)
            for (key in d.keys) {
                assertContentEquals(baseline, Backwash.render(voice, mapOf(key to Float.NaN)).samples)
                val a = Backwash.render(voice, mapOf(key to .1f)).samples
                val b = Backwash.render(voice, mapOf(key to .5f)).samples
                assertTrue(difference(a, b) > .0001, "$voice $key is inert below midpoint")
            }
            assertEquals(d.keys, Backwash.scramble(voice, Random(71), 1f).keys)
            assertTrue(Backwash.scramble(voice, Random(71), 1f).values.all { it in 0f..1f })
            assertFailsWith<IllegalArgumentException> { BackwashPatch("No hold", voice, mapOf("HOLD" to 1f)) }
        }
    }

    @Test fun `damping shortens source and touch changes timbre`() {
        for (voice in BackwashVoice.entries) {
            val open = Backwash.source(voice, mapOf("DAMP" to 0f))
            val shut = Backwash.source(voice, mapOf("DAMP" to 1f))
            assertTrue(shut.samples.size < open.samples.size * .8, "$voice damping")
            val soft = Backwash.source(voice, emptyMap(), .25f).samples
            val hard = Backwash.source(voice, emptyMap(), 1f).samples
            val softRms = rms(soft); val hardRms = rms(hard)
            val normalizedSoft = FloatArray(soft.size) { (soft[it] / softRms).toFloat() }
            val normalizedHard = FloatArray(hard.size) { (hard[it] / hardRms).toFloat() }
            assertTrue(difference(normalizedSoft, normalizedHard) > .005, "$voice velocity is only gain")
        }
    }

    @Test fun `regeneration dies without a limiter and trail raises the late response`() {
        val x = FloatArray(44100 * 2); x[0] = 1f
        val input = Snip(x, 1, 44100)
        for (voice in BackwashVoice.entries) for (sway in listOf(0f, 1f)) {
            val high = Backwash.chorus(input, sway, 1f, voice).samples
            val low = Backwash.chorus(input, sway, 0f, voice).samples
            assertTrue(high.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(rms(high, 2205, 8820) > rms(low, 2205, 8820) + 1e-5)
            assertTrue(rms(high, 44100, 88200) < 1e-7, "$voice feedback did not decay")
        }
    }

    @Test fun `chorus precedes compression and the pair interaction survives amp`() {
        val v = BackwashVoice.SNARE
        val m = Backwash.defaults(v) + mapOf("TRAIL" to .9f, "PRESS" to .9f)
        val s = Backwash.inspect(v, m)
        assertContentEquals(s.pressed.samples, Backwash.press(s.moving, .9f).samples)
        val reversed = Backwash.chorus(Backwash.press(s.dry, .9f), m.getValue("SWAY"), .9f, v)
        assertTrue(difference(s.pressed.samples, reversed.samples) > .001, "chain order is not observable")
        fun render(t: Float, p: Float) = Backwash.render(v, m + mapOf("TRAIL" to t, "PRESS" to p)).samples
        val a = render(.1f, .1f); val b = render(.9f, .1f)
        val c = render(.1f, .9f); val d = render(.9f, .9f)
        val interaction = FloatArray(a.size) { (d[it] - c[it]) - (b[it] - a[it]) }
        assertTrue(rms(interaction) > .0001)
    }

    @Test fun `default classifier decisions are recorded for audition`() {
        for (voice in BackwashVoice.entries) {
            val s = Backwash.render(voice)
            val measured = Classifier.classify(s).drumClass
            assertTrue(measured != com.snipsnap.audio.DrumClass.LOOP && measured != com.snipsnap.audio.DrumClass.UNKNOWN)
            println("BACKWASH $voice classifier=$measured seconds=${s.samples.size / 44100.0}")
        }
    }
}
