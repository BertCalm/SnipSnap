package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StretchTest {

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Float, amp: Double = 0.5): Snip = Snip(
        FloatArray((seconds * rate).toInt()) { i -> (amp * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat() },
        1, rate,
    )

    private fun mono(s: Snip): FloatArray =
        FloatArray(s.frameCount) { f -> (s.samples[f * 2] + s.samples[f * 2 + 1]) / 2f }

    private fun probe(samples: FloatArray, hz: Float): Float =
        CaptureDoctor.goertzel(samples, samples.size, hz, rate)

    @Test
    fun `a stretch is long, stays in tune, and holds steady`() {
        val src = tone(440.0, 0.5f)
        val out = Stretch.stretch(src, factor = 8f, seed = 3)
        assertEquals(2, out.channels)
        assertEquals((src.frameCount * 8L).toInt(), out.frameCount, "eight times longer")

        val m = mono(out)
        assertTrue(
            probe(m, 440f) > 5 * (probe(m, 330f) + probe(m, 587f)),
            "the tone stays at its own frequency",
        )

        // Steady wash, not pulsing: window RMS over the middle stays tight.
        val win = rate / 10
        val rmses = mutableListOf<Double>()
        var at = out.frameCount / 10
        while (at + win < out.frameCount * 9 / 10) {
            var acc = 0.0
            for (i in at until at + win) acc += m[i] * m[i].toDouble()
            rmses += Math.sqrt(acc / win)
            at += win
        }
        val mean = rmses.average()
        val cov = Math.sqrt(rmses.sumOf { (it - mean) * (it - mean) } / rmses.size) / mean
        assertTrue(cov < 0.3, "a wash, not a pulse: CoV $cov")

        // The level comes home to the source's own peak.
        val peak = out.samples.maxOf { Math.abs(it) }
        assertTrue(Math.abs(peak - 0.5f) < 0.01f, "peak honest: $peak")

        // Deterministic per seed; the channels are a decorrelated field.
        val again = Stretch.stretch(src, factor = 8f, seed = 3)
        assertTrue(out.samples.contentEquals(again.samples), "same seed, same wash")
        val other = Stretch.stretch(src, factor = 8f, seed = 4)
        assertTrue(!out.samples.contentEquals(other.samples), "a different seed drifts differently")
        var differs = 0
        for (f in 0 until out.frameCount) {
            if (out.samples[f * 2] != out.samples[f * 2 + 1]) differs++
        }
        assertTrue(differs > out.frameCount / 2, "stereo by decorrelation")
    }

    @Test
    fun `a freeze holds one instant forever - and finds the loudest on its own`() {
        // First half sings 300 Hz, second half 3 kHz.
        val n = rate
        val src = Snip(
            FloatArray(n) { i ->
                val hz = if (i < n / 2) 300.0 else 3000.0
                (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
            },
            1, rate,
        )
        val early = mono(Stretch.freeze(src, seconds = 3f, seed = 7, atSec = 0.2f))
        assertTrue(probe(early, 300f) > 5 * probe(early, 3000f), "frozen in the low half, it sings low")
        val late = mono(Stretch.freeze(src, seconds = 3f, seed = 7, atSec = 0.8f))
        assertTrue(probe(late, 3000f) > 5 * probe(late, 300f), "frozen in the high half, it sings high")

        // The instant holds: first and last quarter carry the same spectrum.
        val q = early.size / 4
        val head = early.copyOfRange(0, q)
        val tail = early.copyOfRange(early.size - q, early.size)
        assertTrue(
            probe(head, 300f) > 5 * probe(head, 3000f) && probe(tail, 300f) > 5 * probe(tail, 3000f),
            "the frozen instant never drifts",
        )

        // No --at: the freeze points itself at the loudest moment.
        val quietLoud = Snip(
            FloatArray(n) { i ->
                val amp = if (i < n / 2) 0.05 else 0.5
                val hz = if (i < n / 2) 300.0 else 3000.0
                (amp * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
            },
            1, rate,
        )
        val auto = mono(Stretch.freeze(quietLoud, seconds = 2f, seed = 7))
        assertTrue(probe(auto, 3000f) > 5 * probe(auto, 300f), "the default freeze finds the loud half")
    }

    @Test
    fun `nonsense is refused, not rendered`() {
        val src = tone(440.0, 0.5f)
        assertFailsWith<IllegalArgumentException> { Stretch.stretch(src, factor = 1f, seed = 1) }
        assertFailsWith<IllegalArgumentException> { Stretch.stretch(src, factor = 1000f, seed = 1) }
        assertFailsWith<IllegalArgumentException> { Stretch.freeze(src, seconds = 0f, seed = 1) }
        assertFailsWith<IllegalArgumentException> { Stretch.freeze(src, seconds = 2f, seed = 1, atSec = 9f) }
        assertFailsWith<IllegalArgumentException> { Stretch.stretch(Snip(FloatArray(0), 1, rate), 8f, 1) }
    }
}
