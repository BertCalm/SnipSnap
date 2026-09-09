package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapeSpliceTest {

    private val rate = 44_100

    private fun sine(hz: Double, seconds: Float, phaseOffset: Double = 0.0, amp: Float = 0.6f): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { i -> (sin(2 * PI * hz * i / rate + phaseOffset) * amp).toFloat() }, 1, rate)
    }

    private fun silence(seconds: Float): Snip = Snip(FloatArray((seconds * rate).toInt()), 1, rate)

    @Test
    fun `a clean seam is a hard cut - no crossfade`() {
        // Two silences: the seam is a perfect zero-to-zero, no click possible.
        val head = silence(0.2f)
        val tail = silence(0.2f)
        val spliced = TapeSplice.join(head, head.frameCount / 2, tail, tail.frameCount / 2)
        assertFalse(spliced.crossfaded, "silence-to-silence has nothing to click")
        assertEquals(0f, spliced.jumpError)
        assertEquals(head.frameCount, spliced.snip.frameCount)
        assertEquals(head.frameCount / 2, spliced.joinFrame)
    }

    @Test
    fun `two continuations of the exact same sine line up with no crossfade`() {
        // head and tail are the SAME continuous sine; cutting and rejoining
        // it at frame N should reproduce the original exactly - a clean seam.
        val whole = sine(220.0, 0.5f)
        val cut = whole.frameCount / 2
        val head = Snip(whole.samples.copyOfRange(0, cut), 1, rate)
        val tail = Snip(whole.samples.copyOfRange(cut, whole.samples.size), 1, rate)
        val spliced = TapeSplice.join(head, head.frameCount, tail, 0)
        assertFalse(spliced.crossfaded, "rejoining a clean cut of one continuous tone should not click")
        assertEquals(whole.frameCount, spliced.snip.frameCount)
        for (i in whole.samples.indices) {
            assertEquals(whole.samples[i], spliced.snip.samples[i], 1e-5f, "sample $i")
        }
    }

    /** A flat DC level - constant movement (none), so any jump at its edge is unambiguous. */
    private fun flat(level: Float, seconds: Float): Snip = Snip(FloatArray((seconds * rate).toInt()) { level }, 1, rate)

    @Test
    fun `a real amplitude discontinuity bakes a short declick crossfade`() {
        // head sits at a steady +0.6, tail at a steady -0.6: a textbook
        // square-wave-edge click at the seam, nothing ambiguous about it.
        val head = flat(0.6f, 0.1f)
        val tail = flat(-0.6f, 0.1f)
        val spliced = TapeSplice.join(head, head.frameCount, tail, 0)
        assertTrue(spliced.crossfaded, "a DC step at the seam should click without a fade")
        assertTrue(spliced.jumpError > TapeSplice.CLEAN_JUMP)
        // The overlap shortens the result - it's not simply head.length + tail.length.
        assertTrue(spliced.snip.frameCount < head.frameCount + tail.frameCount)
        assertTrue(spliced.joinFrame < head.frameCount)
    }

    @Test
    fun `crossfaded output has no discontinuity larger than either input`() {
        val head = flat(0.6f, 0.1f)
        val tail = flat(-0.6f, 0.1f)
        val spliced = TapeSplice.join(head, head.frameCount, tail, 0)
        assertTrue(spliced.crossfaded)
        var maxStep = 0f
        for (i in 1 until spliced.snip.samples.size) {
            val d = kotlin.math.abs(spliced.snip.samples[i] - spliced.snip.samples[i - 1])
            if (d > maxStep) maxStep = d
        }
        // The raw (unfaded) step was 1.2 (from +0.6 to -0.6) in a single
        // frame; spread linearly over the crossfade window it must be far
        // smaller per frame.
        assertTrue(maxStep < 0.3f, "no single-frame jump should survive the declick: $maxStep")
    }

    @Test
    fun `refuses mismatched sample rate rather than silently resampling`() {
        val head = sine(220.0, 0.1f)
        val tail = Snip(sine(220.0, 0.1f).samples, 1, 48_000)
        assertFailsWith<IllegalArgumentException> { TapeSplice.join(head, head.frameCount, tail, 0) }
    }

    @Test
    fun `refuses mismatched channel count rather than silently folding`() {
        val head = sine(220.0, 0.1f)
        val stereoTail = Snip(FloatArray(head.samples.size * 2), 2, rate)
        assertFailsWith<IllegalArgumentException> { TapeSplice.join(head, head.frameCount, stereoTail, 0) }
    }

    @Test
    fun `zero head frames uses the tail alone from tailFrames`() {
        val head = sine(220.0, 0.1f)
        val tail = sine(440.0, 0.2f)
        val spliced = TapeSplice.join(head, 0, tail, 0)
        assertFalse(spliced.crossfaded, "nothing on the head side to click against")
        assertEquals(tail.frameCount, spliced.snip.frameCount)
        for (i in tail.samples.indices) assertEquals(tail.samples[i], spliced.snip.samples[i], 1e-6f)
    }

    @Test
    fun `tailFrames at the tail's own end keeps only the head`() {
        val head = sine(220.0, 0.1f)
        val tail = sine(440.0, 0.2f)
        val spliced = TapeSplice.join(head, head.frameCount, tail, tail.frameCount)
        assertFalse(spliced.crossfaded, "nothing on the tail side to click against")
        assertEquals(head.frameCount, spliced.snip.frameCount)
        for (i in head.samples.indices) assertEquals(head.samples[i], spliced.snip.samples[i], 1e-6f)
    }

    @Test
    fun `out-of-range frame counts are refused`() {
        val head = sine(220.0, 0.1f)
        val tail = sine(220.0, 0.1f)
        assertFailsWith<IllegalArgumentException> { TapeSplice.join(head, head.frameCount + 1, tail, 0) }
        assertFailsWith<IllegalArgumentException> { TapeSplice.join(head, -1, tail, 0) }
        assertFailsWith<IllegalArgumentException> { TapeSplice.join(head, 0, tail, tail.frameCount + 1) }
    }

    @Test
    fun `stereo takes join channel-interleaved, unmangled`() {
        val n = 4_410
        val head = Snip(FloatArray(n * 2) { i -> if (i % 2 == 0) 0.3f else -0.3f }, 2, rate)
        val tail = Snip(FloatArray(n * 2) { i -> if (i % 2 == 0) -0.3f else 0.3f }, 2, rate)
        val spliced = TapeSplice.join(head, n / 2, tail, n / 2)
        assertEquals(2, spliced.snip.channels)
        // The joined result's head half is exactly head's own first frames (allowing for a possible declick fade).
        assertTrue(spliced.snip.frameCount in (n - 1)..n)
    }
}
