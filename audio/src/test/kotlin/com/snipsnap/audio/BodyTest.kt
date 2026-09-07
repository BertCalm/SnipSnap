package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BodyTest {

    private val rate = 44_100

    /** A bare click: one sample up, one down, then a quarter second of nothing. */
    private fun click(): Snip = Snip(FloatArray(rate / 4).also { it[0] = 0.9f; it[1] = -0.6f }, 1, rate)

    /** Short-time RMS in dB, 5 ms frames. */
    private fun envelopeDb(s: Snip): DoubleArray {
        val win = rate / 200
        return DoubleArray(s.frameCount / win) { f ->
            var acc = 0.0
            for (i in f * win until (f + 1) * win) acc += s.samples[i].toDouble() * s.samples[i]
            10 * Math.log10(acc / win + 1e-20)
        }
    }

    @Test
    fun `a click through BODY rings at the root`() {
        val aMinor = KeySpec.parse("Am")
        val out = Body.ring(click(), aMinor.rootSemitone, aMinor.scale, amount = 1f, decaySec = 0.8f)
        val est = assertNotNull(Pitch.detect(out, fromSec = 0.02f), "the body has a pitch")
        assertTrue(est.confidence > 0.5f, "and is sure of it: ${est.confidence}")
        val a2 = Scales.midiToHz(45)
        val cents = 1200f * log2(est.hz / a2)
        assertTrue(abs(cents) < 15f || abs(cents - 1200f) < 15f, "rings at A (A2 or its octave): ${est.hz} Hz, $cents cents from A2")
        // The loudest partial is the root, by the retune's own ear.
        val partials = Retune.analyze(out, KeySpec(0, Scale.CHROMATIC)).partials
        val loudest = partials.maxByOrNull { it.level }!!
        assertEquals("A", loudest.targetName.dropLast(1), "the loudest mode is an A: ${loudest.targetName}")
        assertTrue(abs(out.peak() - click().peak()) < 0.02f, "peak matched: ${out.peak()}")
    }

    @Test
    fun `decay follows the knob`() {
        val c = KeySpec.parse("C")
        fun t60(decay: Float): Double {
            val out = Body.ring(click(), c.rootSemitone, c.scale, amount = 1f, decaySec = decay)
            val env = envelopeDb(out)
            val peakAt = env.indices.maxByOrNull { env[it] }!!
            val peak = env[peakAt]
            var f = peakAt
            while (f < env.size && env[f] > peak - 60.0) f++
            return (f - peakAt) * 0.005
        }
        val short = t60(0.3f)
        val long = t60(1.2f)
        assertTrue(abs(short - 0.3) < 0.1, "T60 near 0.3 s: $short")
        assertTrue(abs(long - 1.2) < 0.3, "T60 near 1.2 s: $long")
        assertTrue(long > 3 * short, "four times the knob, about four times the ring: $short -> $long")
        // The body rings past the hit: the output is the hit plus the decay.
        val out = Body.ring(click(), 0, Scale.MAJOR, decaySec = 1f)
        assertEquals(click().frameCount + rate, out.frameCount)
    }

    @Test
    fun `the modes are the key's chord tones, root first and loudest`() {
        val am = Body.modes(9, Scale.MINOR)
        assertEquals(Body.OCTAVES * 3, am.size)
        assertEquals("A2", am.first().name)
        assertTrue(am.first().weight >= am.maxOf { it.weight }, "the low root is the loudest mode")
        assertEquals(listOf("A2", "C3", "E3"), am.take(3).map { it.name }, "root, minor third, fifth")
        val cMaj = Body.modes(0, Scale.MAJOR)
        assertEquals(listOf("C2", "E2", "G2"), cMaj.take(3).map { it.name }, "root, major third, fifth")
        val chrom = Body.modes(0, Scale.CHROMATIC)
        assertEquals(listOf("C2", "G2"), chrom.take(2).map { it.name }, "no key: root and fifth only")
        assertFailsWith<IllegalArgumentException> { Body.modes(12, Scale.MAJOR) }
    }

    @Test
    fun `AMOUNT 0 is the input itself, and the bounds hold`() {
        val c = click()
        assertSame(c, Body.ring(c, 0, Scale.MAJOR, amount = 0f))
        assertFailsWith<IllegalArgumentException> { Body.ring(c, 0, Scale.MAJOR, amount = 1.1f) }
        assertFailsWith<IllegalArgumentException> { Body.ring(c, 0, Scale.MAJOR, decaySec = 5f) }
        assertFailsWith<IllegalArgumentException> { Body.ring(Snip(FloatArray(0), 1, rate), 0, Scale.MAJOR) }
        // Stereo stays stereo; the same inputs give the same bytes.
        val st = Snip(FloatArray(rate / 4 * 2).also { it[0] = 0.9f; it[1] = 0.5f }, 2, rate)
        val a = Body.ring(st, 0, Scale.MAJOR)
        val b = Body.ring(st, 0, Scale.MAJOR)
        assertEquals(2, a.channels)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `no key - the body rings at the hit's own note, or C`() {
        val a3 = Snip(FloatArray(rate / 2) { (0.5 * Math.sin(2 * Math.PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        assertEquals(9, Body.rootFor(a3, null), "a 220 Hz hit roots the body on A")
        assertEquals(0, Body.rootFor(click(), null), "a click has no note: C")
        assertEquals(2, Body.rootFor(click(), KeySpec.parse("Dm")), "the key wins")
    }
}
