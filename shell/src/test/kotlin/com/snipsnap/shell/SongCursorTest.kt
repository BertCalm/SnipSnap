package com.snipsnap.shell

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live play's transport over a mix whose every sample names its own frame
 * (left = frame, right = -frame), so what was read says where it was read
 * from. Section 0 plays twice, then 1 and 2 once each, 1000 frames a pass,
 * with 500 frames of ring-out after the last bar.
 */
class SongCursorTest {

    private val pass = 1000
    private val passes = listOf(
        Arranger.Pass(0, 0, 1000),
        Arranger.Pass(0, 1000, 2000),
        Arranger.Pass(1, 2000, 3000),
        Arranger.Pass(2, 3000, 4000),
    )
    private val total = 4500
    private val mix = FloatArray(total * 2) { i -> if (i % 2 == 0) (i / 2).toFloat() else -(i / 2).toFloat() }

    private fun cursor(from: Int = 0) = SongCursor(mix, passes, from)

    /** [frames] frames read in uneven blocks, as an audio thread would; the left channel. */
    private fun SongCursor.take(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        var n = 0
        val block = FloatArray(333 * 2)
        while (n < frames) {
            val got = read(block, minOf(333, frames - n))
            if (got == 0) break
            block.copyInto(out, n * 2, 0, got * 2)
            n += got
        }
        return FloatArray(n) { out[it * 2] }
    }

    @Test
    fun `played straight through it is the mix, sample for sample, ring-out and all`() {
        val c = cursor()
        val got = c.take(total + 100)
        assertEquals(total, got.size, "the song ends where the mix does")
        for (f in 0 until total) assertEquals(f.toFloat(), got[f], "frame $f")
        assertTrue(c.finished)
        assertEquals(listOf(0, 0, 1, 2), c.played())
    }

    @Test
    fun `play from a section starts on its first pass`() {
        val c = cursor(from = 1)
        val got = c.take(10)
        assertEquals(2000f, got[0])
        c.take(total)
        assertEquals(listOf(1, 2), c.played())
    }

    @Test
    fun `loop plays the whole section again, all its passes, until it is turned off`() {
        val c = cursor()
        c.loop = true
        val got = c.take(4 * pass)
        // After the crossfade, the third pass is the first again, frame for frame.
        for (k in SongCursor.FADE_FRAMES until pass) assertEquals(k.toFloat(), got[2 * pass + k], "looped frame $k")
        assertEquals(listOf(0, 0, 0, 0), c.played())
        c.loop = false
        c.take(2 * pass)
        assertEquals(listOf(0, 0, 0, 0, 1, 2), c.played(), "with the loop off it goes on through the song")
    }

    @Test
    fun `a queued section starts when the pass playing now ends, and wins over the loop`() {
        val c = cursor()
        c.loop = true
        c.queued = 2
        val got = c.take(2 * pass)
        for (k in 0 until pass) assertEquals(k.toFloat(), got[k], "the first pass plays out whole: frame $k")
        for (k in SongCursor.FADE_FRAMES until pass) assertEquals((3000 + k).toFloat(), got[pass + k], "then section 2: frame $k")
        assertNull(c.queued, "the queue is spent once it starts")
        assertEquals(listOf(0, 2), c.played())
    }

    @Test
    fun `a jump crossfades instead of clicking, and running on into the next pass does not fade at all`() {
        val c = cursor()
        c.queued = 2
        val jumped = c.take(2 * pass)
        val steepest = (1 until jumped.size).maxOf { abs(jumped[it] - jumped[it - 1]) }
        // Unfaded, the step from frame 999 to frame 3000 is 2001 in one sample.
        assertTrue(steepest < 20f, "the jump is spread over the fade: steepest step $steepest")

        val straight = cursor()
        straight.take(pass + 10) // into section 0's second, last pass
        straight.queued = 1 // which runs on into section 1 in the mix: no jump
        val on = straight.take(2 * pass)
        for (k in on.indices) assertEquals((pass + 10 + k).toFloat(), on[k], "frame ${pass + 10 + k} unfaded")
    }

    @Test
    fun `what was heard is what had started sounding, not what was written ahead`() {
        val c = cursor()
        c.take(total)
        assertEquals(listOf(0, 0), c.played(1500))
        assertEquals(listOf(0, 0, 1), c.played(2000))
        assertEquals(0, c.sectionAt(1999))
        assertEquals(1, c.sectionAt(2000))
        assertEquals(2, c.sectionAt(4400), "the ring-out belongs to the last section")
    }
}
