package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransientsTest {

    private val rate = 44_100

    /**
     * A decaying sine burst — a rough but honest stand-in for a drum hit:
     * sharp attack, exponential decay.
     */
    private fun hit(
        into: FloatArray,
        atFrame: Int,
        amplitude: Float = 0.8f,
        freq: Double = 180.0,
        decay: Double = 40.0,
    ) {
        val length = (rate * 0.25).toInt()
        for (i in 0 until length) {
            val f = atFrame + i
            if (f >= into.size) break
            val t = i.toDouble() / rate
            into[f] += (amplitude * exp(-decay * t) * sin(2.0 * Math.PI * freq * t)).toFloat()
        }
    }

    private fun snipOf(frames: Int, build: (FloatArray) -> Unit): Snip {
        val buf = FloatArray(frames)
        build(buf)
        return Snip(buf, 1, rate)
    }

    /**
     * Onsets should land just before the true attack — the deliberate backoff,
     * about 3 ms, and not much more. 300 frames is ~7 ms of slack.
     */
    private fun assertNear(expected: Int, actual: Int, toleranceFrames: Int = 300) {
        assertTrue(
            abs(expected - actual) <= toleranceFrames,
            "expected an onset near $expected, got $actual (off by ${abs(expected - actual)} frames)",
        )
    }

    @Test
    fun `finds evenly spaced hits`() {
        val positions = listOf(5_000, 30_000, 55_000, 80_000)
        val snip = snipOf(110_000) { buf -> positions.forEach { hit(buf, it) } }

        val onsets = Transients.detect(snip)

        assertEquals(positions.size, onsets.size, "found ${onsets.size} onsets, expected ${positions.size}")
        positions.forEachIndexed { i, expected -> assertNear(expected, onsets[i].frame) }
    }

    @Test
    fun `returns onsets in time order`() {
        val snip = snipOf(110_000) { buf ->
            listOf(5_000, 30_000, 55_000, 80_000).forEach { hit(buf, it) }
        }
        val frames = Transients.detect(snip).map { it.frame }
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun `finds hits of differing loudness`() {
        // A ghost note is still a note. A fixed threshold would miss the quiet
        // one or shred the loud one; the adaptive threshold should get both.
        val snip = snipOf(110_000) { buf ->
            hit(buf, 5_000, amplitude = 0.9f)
            hit(buf, 30_000, amplitude = 0.15f)
            hit(buf, 55_000, amplitude = 0.9f)
            hit(buf, 80_000, amplitude = 0.2f)
        }

        assertEquals(4, Transients.detect(snip).size)
    }

    @Test
    fun `does not fire on decay`() {
        // One hit must yield one onset, not a burst as the tail wobbles.
        val snip = snipOf(60_000) { buf -> hit(buf, 5_000, decay = 8.0) }
        assertEquals(1, Transients.detect(snip).size)
    }

    @Test
    fun `finds nothing in silence`() {
        assertTrue(Transients.detect(snipOf(50_000) {}).isEmpty())
    }

    @Test
    fun `finds nothing in steady tone`() {
        // A sustained tone has no attacks after the first. Slicing it would be
        // wrong, and this is the bias we want for a drum sampler.
        val snip = snipOf(80_000) { buf ->
            for (i in buf.indices) buf[i] = 0.5f * sin(2.0 * Math.PI * 220.0 * i / rate).toFloat()
        }
        assertTrue(Transients.detect(snip).size <= 1, "a steady tone should not be chopped up")
    }

    @Test
    fun `respects the minimum gap`() {
        // Two hits 10 ms apart, with a 50 ms minimum: the second is the first's
        // problem, not a new slice.
        val snip = snipOf(60_000) { buf ->
            hit(buf, 10_000)
            hit(buf, 10_000 + (rate * 0.010).toInt())
        }

        val onsets = Transients.detect(snip, Transients.Config(minSliceMs = 50f))
        assertEquals(1, onsets.size)
    }

    @Test
    fun `a smaller minimum gap separates fast hits`() {
        val gap = (rate * 0.060).toInt()
        val snip = snipOf(80_000) { buf ->
            hit(buf, 10_000, decay = 80.0)
            hit(buf, 10_000 + gap, decay = 80.0)
        }

        assertEquals(2, Transients.detect(snip, Transients.Config(minSliceMs = 30f)).size)
    }

    @Test
    fun `cuts land at or before the attack`() {
        // Cutting even fractionally late shaves the transient. Landing early is
        // recoverable; landing late is not.
        val at = 30_000
        val snip = snipOf(80_000) { buf -> hit(buf, at) }

        val onset = Transients.detect(snip).single()
        assertTrue(onset.frame <= at + 200, "onset at ${onset.frame} landed after the attack at $at")
    }

    @Test
    fun `strongest picks the biggest hits and keeps time order`() {
        val snip = snipOf(140_000) { buf ->
            hit(buf, 5_000, amplitude = 0.3f)
            hit(buf, 30_000, amplitude = 0.9f)
            hit(buf, 55_000, amplitude = 0.25f)
            hit(buf, 80_000, amplitude = 0.95f)
        }

        val strongest = Transients.detectStrongest(snip, 2)

        assertEquals(2, strongest.size)
        assertTrue(strongest[0].frame < strongest[1].frame, "results must be back in time order")
        assertNear(30_000, strongest[0].frame)
        assertNear(80_000, strongest[1].frame)
    }

    @Test
    fun `strongest returns everything when asked for more than exists`() {
        val snip = snipOf(80_000) { buf ->
            hit(buf, 5_000)
            hit(buf, 40_000)
        }
        assertEquals(2, Transients.detectStrongest(snip, 16).size)
    }

    @Test
    fun `handles stereo`() {
        val positions = listOf(5_000, 40_000)
        val mono = snipOf(90_000) { buf -> positions.forEach { hit(buf, it) } }
        val stereo = Snip(
            FloatArray(mono.frameCount * 2) { mono.samples[it / 2] },
            channels = 2,
            sampleRate = rate,
        )

        assertEquals(positions.size, Transients.detect(stereo).size)
    }

    @Test
    fun `handles a snip shorter than the analysis window`() {
        assertTrue(Transients.detect(Snip(FloatArray(100), 1, rate)).isEmpty())
    }

    @Test
    fun `finds a zero crossing before a cut`() {
        val snip = snipOf(1000) { buf ->
            for (i in buf.indices) buf[i] = sin(2.0 * Math.PI * 100.0 * i / rate).toFloat()
        }
        val crossing = Transients.zeroCrossingBefore(snip, 500)

        assertTrue(crossing <= 500)
        assertTrue(abs(snip.samples[crossing]) < 0.05f, "should land near zero, got ${snip.samples[crossing]}")
    }

    @Test
    fun `zero crossing search gives up gracefully`() {
        // Nothing but positive samples: no crossing exists, so return the frame
        // asked for rather than wandering off.
        val snip = Snip(FloatArray(1000) { 0.5f }, 1, rate)
        assertEquals(500, Transients.zeroCrossingBefore(snip, 500))
    }

    @Test
    fun `rejects nonsense config`() {
        assertFailsWith<IllegalArgumentException> { Transients.Config(hopFrames = 0) }
        assertFailsWith<IllegalArgumentException> { Transients.Config(windowFrames = 10, hopFrames = 100) }
        assertFailsWith<IllegalArgumentException> { Transients.Config(backoffFrames = -1) }
        assertFailsWith<IllegalArgumentException> {
            Transients.detectStrongest(Snip(FloatArray(0), 1, rate), -1)
        }
    }
}
