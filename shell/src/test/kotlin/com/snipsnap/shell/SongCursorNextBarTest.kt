package com.snipsnap.shell

import com.snipsnap.shell.SongCursor.QueueMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NEXT BAR beside END OF PASS, over a mix whose every sample names its own
 * frame (left = frame). Section 0 is a four-bar clip of 1000 frames a pass
 * (bar lines at 250, 500, 750 of the pass) played twice; section 1 is the
 * same, once; section 2 is a three-bar clip of 900 frames (a different
 * meter, lines at 3300 and 3600), once. The frame counts are made up to
 * check the maths, not a sample rate.
 */
class SongCursorNextBarTest {

    private val passes = listOf(
        Arranger.Pass(0, 0, 1000, listOf(250, 500, 750)),
        Arranger.Pass(0, 1000, 2000, listOf(1250, 1500, 1750)),
        Arranger.Pass(1, 2000, 3000, listOf(2250, 2500, 2750)),
        Arranger.Pass(2, 3000, 3900, listOf(3300, 3600)),
    )
    private val total = 4200
    private val mix = FloatArray(total * 2) { i -> (i / 2).toFloat() }

    private fun cursor(mode: QueueMode, from: Int = 0) = SongCursor(mix, passes, from).also { it.queueMode = mode }

    /** Reads [frames] frames in one go, left channel. */
    private fun SongCursor.take(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val got = read(out, frames)
        return FloatArray(got) { out[it * 2] }
    }

    @Test
    fun `queued mid-bar, section 2 starts at the next bar line, not at the pass end`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.take(300) // 50 frames into bar two
        c.queued = 2
        val got = c.take(600)
        // Bar line at 500: 300 read already, so 200 more frames of the old pass.
        for (k in 0 until 200) assertEquals((300 + k).toFloat(), got[k], "old pass runs to the line")
        for (k in SongCursor.FADE_FRAMES until 300) assertEquals((3000 + k).toFloat(), got[200 + k], "then section 2 from its first frame")
        assertNull(c.queued)
        assertEquals(listOf(0, 2), c.played())
    }

    @Test
    fun `queued exactly on a bar line switches on that line`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.take(500) // the read position is exactly on the line at 500
        c.queued = 1
        val got = c.take(400)
        for (k in SongCursor.FADE_FRAMES until 400) assertEquals((2000 + k).toFloat(), got[k], "section 1 from frame 500 on")
        assertEquals(listOf(0, 1), c.played())
    }

    @Test
    fun `queued before the first bar line goes to that first line, not the start of the pass it just reached`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.queued = 2
        val got = c.take(400)
        for (k in 0 until 250) assertEquals(k.toFloat(), got[k], "bar one plays out")
        for (k in SongCursor.FADE_FRAMES until 150) assertEquals((3000 + k).toFloat(), got[250 + k])
    }

    @Test
    fun `queued in the last bar of a pass is the same as END OF PASS`() {
        val bar = cursor(QueueMode.NEXT_BAR)
        val pass = cursor(QueueMode.END_OF_PASS)
        for (c in listOf(bar, pass)) {
            c.take(800) // inside the last bar of pass one
            c.queued = 2
        }
        val a = bar.take(1500)
        val b = pass.take(1500)
        assertEquals(b.toList(), a.toList())
        assertEquals(pass.played(), bar.played())
    }

    @Test
    fun `a cut at a bar line of the three bar clip uses its own bar`() {
        val c = cursor(QueueMode.NEXT_BAR, from = 2)
        c.take(100)
        c.queued = 0
        val got = c.take(700)
        for (k in 0 until 200) assertEquals((3100 + k).toFloat(), got[k], "plays to the line at 3300")
        for (k in SongCursor.FADE_FRAMES until 500) assertEquals(k.toFloat(), got[200 + k], "section 0 from its first frame")
        assertEquals(listOf(2, 0), c.played())
    }

    @Test
    fun `NEXT BAR with nothing queued plays the song through unchanged`() {
        val c = cursor(QueueMode.NEXT_BAR)
        val got = c.take(total + 50)
        assertEquals(total, got.size)
        for (f in 0 until total) assertEquals(f.toFloat(), got[f], "frame $f")
        assertEquals(listOf(0, 0, 1, 2), c.played())
    }

    @Test
    fun `loop on, a NEXT BAR queue still wins at the bar line, and the loop goes on after`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.loop = true
        c.take(600)
        c.queued = 1
        val got = c.take(1000)
        for (k in 0 until 150) assertEquals((600 + k).toFloat(), got[k], "to the line at 750")
        for (k in SongCursor.FADE_FRAMES until 800) assertEquals((2000 + k).toFloat(), got[150 + k])
        assertEquals(listOf(0, 1), c.played())
        c.queued = null
        // Section 1 is the last of its run, so the loop restarts it at its pass end.
        c.take(1000)
        assertEquals(listOf(0, 1, 1), c.played())
    }

    @Test
    fun `repeats over one, a queue cuts the second pass of the section, on its own bar line`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.take(1250) // second pass of section 0, exactly on its first bar line
        c.queued = 1
        val got = c.take(300)
        for (k in SongCursor.FADE_FRAMES until 300) assertEquals((2000 + k).toFloat(), got[k], "section 1 from frame 1250 on")
        assertEquals(listOf(0, 0, 1), c.played())
    }

    @Test
    fun `END OF PASS is unchanged, the queued section waits for the pass end`() {
        val c = cursor(QueueMode.END_OF_PASS)
        c.take(300)
        c.queued = 2
        val got = c.take(1000)
        for (k in 0 until 700) assertEquals((300 + k).toFloat(), got[k], "plays the pass out whole")
        for (k in SongCursor.FADE_FRAMES until 300) assertEquals((3000 + k).toFloat(), got[700 + k])
        assertEquals(listOf(0, 2), c.played())
    }

    @Test
    fun `the mode can change while playing, and a switch to NEXT BAR takes the next line`() {
        val c = cursor(QueueMode.END_OF_PASS)
        c.take(300)
        c.queued = 2
        c.queueMode = QueueMode.NEXT_BAR
        val got = c.take(400)
        for (k in 0 until 200) assertEquals((300 + k).toFloat(), got[k])
        assertEquals(listOf(0, 2), c.played())
    }

    @Test
    fun `a queue naming no pass is dropped and never cuts a pass`() {
        val c = cursor(QueueMode.NEXT_BAR)
        c.take(100)
        c.queued = 9
        val got = c.take(1000)
        for (k in got.indices) assertEquals((100 + k).toFloat(), got[k], "frame ${100 + k}")
        assertEquals(listOf(0, 0), c.played())
    }
}
