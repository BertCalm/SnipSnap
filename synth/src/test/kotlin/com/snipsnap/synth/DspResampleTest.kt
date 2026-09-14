package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspResampleTest {

    private val ramp = Snip(FloatArray(1_000) { it / 1_000f }, 1, 44_100)

    @Test
    fun `a head that walks one frame at a time is the source back again`() {
        val out = Dsp.readAt(ramp, ramp.frameCount) { k -> k.toDouble() to 1f }
        assertEquals(ramp.frameCount, out.frameCount)
        // The last frame has no neighbour to interpolate toward, so the head stops short of it.
        for (i in 0 until ramp.frameCount - 1) {
            assertTrue(abs(out.samples[i] - ramp.samples[i]) < 1e-5f, "frame $i moved")
        }
    }

    @Test
    fun `a half-speed head reads the source twice as slowly`() {
        val out = Dsp.readAt(ramp, ramp.frameCount * 2) { k -> k * 0.5 to 1f }
        assertEquals(ramp.frameCount * 2, out.frameCount)
        assertTrue(abs(out.samples[ramp.frameCount] - ramp.samples[ramp.frameCount / 2]) < 0.01f)
        assertTrue(abs(out.samples[500] - ramp.samples[250]) < 0.01f)
    }

    @Test
    fun `reading past the end is silence, not a crash`() {
        val out = Dsp.readAt(ramp, ramp.frameCount * 3) { k -> k.toDouble() to 1f }
        assertEquals(0f, out.samples[out.frameCount - 1])
    }

    @Test
    fun `the head's gain rides the output`() {
        val out = Dsp.readAt(ramp, ramp.frameCount) { k -> k.toDouble() to 0.5f }
        assertTrue(abs(out.samples[500] - ramp.samples[500] * 0.5f) < 1e-5f, "gain was not applied")
    }

    @Test
    fun `a stereo read at a non-identity rate keeps both channels identical`() {
        // Both callers (MOTION at STOP 0/START 0, SPEED at SEMITONES 0.5) take
        // identity paths at their defaults, so the shared "stereo stays
        // stereo" contract test never actually drives the interpolator at
        // stereo. This exercises it directly, off the identity path.
        val stereo = Snip(FloatArray(ramp.frameCount * 2) { ramp.samples[it / 2] }, 2, 44_100)
        val out = Dsp.readAt(stereo, stereo.frameCount * 2) { k -> k * 0.5 to 1f }
        for (f in 0 until out.frameCount) {
            assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at frame $f")
        }
    }
}
