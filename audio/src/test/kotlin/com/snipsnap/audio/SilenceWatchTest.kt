package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceWatchTest {

    private val zeros = FloatArray(100)

    @Test
    fun `ticks once the silent run reaches the hold, then again a hold later`() {
        val watch = SilenceWatch(holdFrames = 250)
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.silent)
        assertTrue(watch.feed(zeros, 100), "300 silent frames crosses a 250 hold")
        assertEquals(0L, watch.silentFrames, "the run restarts after a tick")
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.feed(zeros, 100), "a second tick another hold later")
    }

    @Test
    fun `any sound resets the run, and dither counts as sound`() {
        val watch = SilenceWatch(holdFrames = 250)
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        val dither = FloatArray(100) { if (it == 57) 1e-6f else 0f }
        assertFalse(watch.feed(dither, 100))
        assertFalse(watch.silent, "one live sample ends the silent run")
        assertEquals(0L, watch.silentFrames)
        // Nowhere near a tick now: the old 200 frames don't carry over.
        assertFalse(watch.feed(zeros, 100))
        assertFalse(watch.feed(zeros, 100))
        assertTrue(watch.feed(zeros, 100))
    }

    @Test
    fun `only count samples are read, and NaN is not silence`() {
        val watch = SilenceWatch(holdFrames = 10)
        val tail = FloatArray(100) { if (it >= 5) 1f else 0f }
        assertFalse(watch.feed(tail, 5), "the loud tail past count is never looked at")
        assertEquals(5L, watch.silentFrames)
        val nan = floatArrayOf(0f, Float.NaN)
        assertFalse(watch.feed(nan, 2))
        assertFalse(watch.silent, "a NaN is a broken stream, not dead air")
    }

    @Test
    fun `forSeconds sizes the hold from the rate and refuses nonsense`() {
        assertEquals(88_200, SilenceWatch.forSeconds(2.0, 44_100).holdFrames)
        assertFailsWith<IllegalArgumentException> { SilenceWatch.forSeconds(0.0, 44_100) }
        assertFailsWith<IllegalArgumentException> { SilenceWatch(0) }
        assertFailsWith<IllegalArgumentException> { SilenceWatch(10, threshold = -1f) }
    }

    @Test
    fun `a negative count is refused, not subtracted from the run`() {
        val watch = SilenceWatch(holdFrames = 150)
        watch.feed(zeros, 100)
        assertFailsWith<IllegalArgumentException> { watch.feed(zeros, -1) }
        assertEquals(100L, watch.silentFrames, "the run is untouched by the refused call")
    }

    @Test
    fun `reset forgets a run in progress`() {
        val watch = SilenceWatch(holdFrames = 150)
        watch.feed(zeros, 100)
        watch.reset()
        assertFalse(watch.silent)
        assertFalse(watch.feed(zeros, 100), "after reset the hold starts over")
    }
}
