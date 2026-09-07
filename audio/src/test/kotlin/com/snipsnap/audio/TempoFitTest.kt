package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TempoFitTest {

    private fun tone(hz: Double, seconds: Float = 1.0f): Snip {
        val n = (seconds * 44_100).toInt()
        return Snip(
            FloatArray(n) { i -> (0.6 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
            1, 44_100,
        )
    }

    @Test
    fun `duration scales by the tempo ratio and pitch rides along`() {
        val loop = tone(440.0, 1.9f)
        val fitted = TempoFit.repitch(loop, fromBpm = 95f, toBpm = 92f)

        assertEquals(44_100, fitted.sampleRate, "the rate is reinterpreted, not converted")
        val expectedFrames = (loop.frameCount * 95.0 / 92.0)
        assertTrue(
            abs(fitted.frameCount - expectedFrames) < 3,
            "95 -> 92 stretches by the ratio: ${fitted.frameCount} vs $expectedFrames",
        )
        // Slower playback = lower pitch: 440 * 92/95 = 426.1 Hz.
        val heard = Pitch.detect(fitted)!!.hz
        assertTrue(abs(heard - 426.1f) < 4f, "pitch rode the repitch: $heard Hz")
    }

    @Test
    fun `a no-op fit is exactly the input`() {
        val loop = tone(220.0)
        assertTrue(TempoFit.repitch(loop, 92f, 92f) === loop)
    }

    @Test
    fun `fits past double or half speed are refused`() {
        val loop = tone(220.0)
        assertFailsWith<IllegalArgumentException> { TempoFit.repitch(loop, 60f, 150f) }
        assertFailsWith<IllegalArgumentException> { TempoFit.repitch(loop, 150f, 60f) }
        assertFailsWith<IllegalArgumentException> { TempoFit.repitch(loop, 0f, 92f) }
    }

    @Test
    fun `the semitone cost is honest`() {
        assertTrue(abs(TempoFit.semitones(100f, 50f) + 12f) < 1e-4f, "half speed is an octave down")
        assertTrue(abs(TempoFit.semitones(92f, 95f) - 0.555f) < 0.01f)
        assertEquals(0f, TempoFit.semitones(92f, 92f))
    }
}
