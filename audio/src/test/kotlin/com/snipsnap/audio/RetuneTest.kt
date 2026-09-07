package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetuneTest {

    private val rate = 44_100
    private val cMajor = KeySpec.parse("C")
    private val chromatic = KeySpec(0, Scale.CHROMATIC)

    /** Four inharmonic partials, each decaying at its own pace — a bell nobody tuned. Every one sits 38..70 cents off C major. */
    private fun clang(seconds: Float = 0.8f, channels: Int = 1): Snip {
        val parts = doubleArrayOf(227.0, 545.0, 1290.0, 2040.0)
        val amps = doubleArrayOf(0.5, 0.35, 0.2, 0.1)
        val frames = (seconds * rate).toInt()
        return Snip(
            FloatArray(frames * channels) { i ->
                val t = (i / channels).toDouble() / rate
                var s = 0.0
                for (k in parts.indices) s += amps[k] * Math.sin(2 * Math.PI * parts[k] * t) * Math.exp(-t * (1.5 + k))
                s.toFloat()
            },
            channels, rate,
        )
    }

    private fun cents(hz: Float, target: Float): Float = 1200f * log2(hz / target)

    /** Where a sound's partials are, by the retune's own ear against the chromatic grid. */
    private fun partialsHz(snip: Snip): List<Float> = Retune.analyze(snip, chromatic).partials.map { it.hz }

    @Test
    fun `an off-key clang's partials land on C major's notes, within a few cents`() {
        val before = Retune.analyze(clang(), cMajor)
        assertTrue(before.pitched, "a clang is pitched enough: ${before.refusal}")
        assertEquals(4, before.partials.size)
        assertTrue(before.partials.all { abs(it.cents) > 30f }, "the control: every partial starts well off key: ${before.partials.map { it.cents }}")
        assertEquals(listOf("A3", "C5", "E6", "C7"), before.partials.map { it.targetName })

        val out = assertNotNull(Retune.retune(clang(), cMajor, seed = 4))
        assertEquals(clang().frameCount, out.snip.frameCount, "the same length")
        val landed = partialsHz(out.snip)
        assertEquals(4, landed.size, "still four partials: $landed")
        for ((k, hz) in landed.withIndex()) {
            val target = before.partials[k].targetHz
            assertTrue(abs(cents(hz, target)) < 5f, "partial $k: ${hz} Hz is ${cents(hz, target)} cents from ${before.partials[k].targetName}")
        }
        assertTrue(abs(out.snip.peak() - clang().peak()) < 0.02f, "peak matched: ${out.snip.peak()} vs ${clang().peak()}")
        // The decay survives: the last tenth is quieter than the first tenth, as in the source.
        fun rms(s: Snip, from: Float, to: Float): Double {
            var acc = 0.0
            val a = (from * s.frameCount).toInt()
            val b = (to * s.frameCount).toInt()
            for (i in a until b) acc += s.samples[i].toDouble() * s.samples[i]
            return Math.sqrt(acc / (b - a))
        }
        assertTrue(rms(out.snip, 0.9f, 1f) < 0.5 * rms(out.snip, 0f, 0.1f), "the bell still decays")
    }

    @Test
    fun `a drum is refused as unpitched, a tom and a clang are not`() {
        for ((name, drum) in listOf("kick" to DrumSynth.kick(), "snare" to DrumSynth.snare(), "hat" to DrumSynth.closedHat(), "clap" to DrumSynth.clap())) {
            val a = Retune.analyze(drum, cMajor)
            assertTrue(!a.pitched, "$name should be refused")
            assertNotNull(a.refusal, "$name names its refusal")
            assertNull(Retune.retune(drum, cMajor), "$name is never corrected")
        }
        // Noise with no partial at all: nothing to snap, whatever the classifier calls it.
        val rnd = java.util.Random(9)
        val hiss = Snip(FloatArray(rate / 2) { (rnd.nextFloat() * 2f - 1f) * 0.5f }, 1, rate)
        val a = Retune.analyze(hiss, cMajor)
        assertTrue(!a.pitched, "hiss is refused: ${a.refusal}")
        assertTrue(a.tonalness < Retune.MIN_TONALNESS, "and would be on tonalness alone: ${a.tonalness}")
        assertTrue(Retune.analyze(DrumSynth.tom(), cMajor).pitched, "a tom is a tuned drum")
        assertTrue(Retune.analyze(clang(), cMajor).pitched)
    }

    @Test
    fun `AMOUNT is how far - zero is the input itself, half is halfway, in key is untouched`() {
        val src = clang()
        val zero = assertNotNull(Retune.retune(src, cMajor, amount = 0f))
        assertSame(src, zero.snip, "amount 0 is transparent")

        val half = assertNotNull(Retune.retune(src, cMajor, amount = 0.5f, seed = 1))
        val before = Retune.analyze(src, cMajor).partials
        val landed = partialsHz(half.snip)
        for (k in before.indices) {
            val moved = cents(landed[k], before[k].hz)
            assertTrue(abs(moved - before[k].cents / 2f) < 6f, "partial $k moved $moved cents, asked ${before[k].cents / 2f}")
        }

        // A tone already on the grid is returned as it is.
        val a3 = Snip(FloatArray(rate / 2) { (0.5 * Math.sin(2 * Math.PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        val kept = assertNotNull(Retune.retune(a3, cMajor))
        assertSame(a3, kept.snip, "already in key: nothing rewritten")
        assertTrue(kept.analysis.inKey)

        assertFailsWith<IllegalArgumentException> { Retune.retune(src, cMajor, amount = 1.5f) }
        assertFailsWith<IllegalArgumentException> { Retune.retune(Snip(FloatArray(0), 1, rate), cMajor) }
    }

    @Test
    fun `stereo stays stereo, and the same seed gives the same bytes`() {
        val src = clang(channels = 2)
        val a = assertNotNull(Retune.retune(src, cMajor, seed = 11)).snip
        val b = assertNotNull(Retune.retune(src, cMajor, seed = 11)).snip
        assertEquals(2, a.channels)
        assertEquals(src.frameCount, a.frameCount)
        assertTrue(a.samples.contentEquals(b.samples), "deterministic per seed")
        val c = assertNotNull(Retune.retune(src, cMajor, seed = 12)).snip
        assertTrue(!a.samples.contentEquals(c.samples), "another seed, other phases")
        val landed = partialsHz(a)
        assertTrue(abs(cents(landed[0], 220f)) < 5f, "the left+right fold still lands on A3: ${landed[0]}")
    }

    @Test
    fun `the key matters - the same clang lands elsewhere in D minor pentatonic`() {
        val key = KeySpec.parse("Dminpent") // D F G A C
        val before = Retune.analyze(clang(), key)
        assertEquals(listOf("A3", "C5", "F6", "C7"), before.partials.map { it.targetName })
        val out = assertNotNull(Retune.retune(clang(), key, seed = 2))
        val landed = partialsHz(out.snip)
        assertTrue(abs(cents(landed[2], Scales.midiToHz(89))) < 5f, "1290 Hz went up to F6 (E6 is not in the scale): ${landed[2]}")
    }
}
