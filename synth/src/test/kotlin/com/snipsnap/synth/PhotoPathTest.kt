package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoPathTest {

    @Test
    fun `arc-length resampling agrees whether the path was drawn as 3 points or 300`() {
        val coarse = listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)
        val fine = (0..300).map { it / 300f }.map { it to it }
        val stepsA = PhotoPath.sample(coarse, 16)
        val stepsB = PhotoPath.sample(fine, 16)
        for (i in stepsA.indices) {
            assertTrue(abs(stepsA[i].first - stepsB[i].first) < 0.01f, "step $i x differs: ${stepsA[i]} vs ${stepsB[i]}")
            assertTrue(abs(stepsA[i].second - stepsB[i].second) < 0.01f, "step $i y differs: ${stepsA[i]} vs ${stepsB[i]}")
        }
    }

    @Test
    fun `sample covers the path's own ends, first and last`() {
        val path = listOf(0.1f to 0.2f, 0.9f to 0.8f)
        val out = PhotoPath.sample(path, 8)
        assertEquals(0.1f, out.first().first, 1e-5f)
        assertEquals(0.2f, out.first().second, 1e-5f)
        assertEquals(0.9f, out.last().first, 1e-5f)
        assertEquals(0.8f, out.last().second, 1e-5f)
    }

    @Test
    fun `a single point repeats for every step`() {
        val out = PhotoPath.sample(listOf(0.3f to 0.4f), 5)
        assertEquals(5, out.size)
        assertTrue(out.all { it == (0.3f to 0.4f) })
    }

    @Test
    fun `an empty path resamples to nothing`() {
        assertTrue(PhotoPath.sample(emptyList(), 16).isEmpty())
    }

    @Test
    fun `a path that lingers in one cell gives that cell repeated steps`() {
        // A scribble entirely inside cell (0, 0) of a 4x4 grid (x, y both
        // in 0.05..0.15, well under the 0.25 boundary), long in arc
        // length from the loops, then one short hop to the far corner.
        val scribble = (0 until 40).map { i ->
            val t = i / 39f
            (0.1f + 0.05f * sin(t * 20f)) to (0.1f + 0.05f * cos(t * 20f))
        }
        val path = scribble + listOf(0.95f to 0.95f)
        val points = PhotoPath.sample(path, 16)
        val cells = PhotoPath.cellsFor(points, columns = 4, rows = 4)
        val zeroZero = cells.count { it == (0 to 0) }
        assertTrue(zeroZero >= cells.size / 2, "expected the scribbled cell to dominate the walk, got $cells")
    }

    @Test
    fun `cellsFor clamps to the grid, even at the very edges`() {
        val cells = PhotoPath.cellsFor(listOf(0f to 0f, 1f to 1f, 0.999999f to 0.999999f), columns = 4, rows = 4)
        assertEquals(0 to 0, cells[0])
        assertEquals(3 to 3, cells[1])
        assertEquals(3 to 3, cells[2])
    }

    @Test
    fun `render is steps times stepFrames long, and pitch follows TUNE`() {
        // A strong left-right hue split, the same shape Snap's own hue
        // tests use: left half red (low TUNE), right half blue (high TUNE).
        val photo = Photo.of(80, 80) { x, _ -> if (x < 40) Photo.rgb(230, 30, 30) else Photo.rgb(30, 30, 230) }
        val bpm = 120f
        val stepFrames = (Dsp.RATE * 60f / bpm / 4f).toInt()
        val cells = listOf(0 to 0, PhotoKit.COLUMNS - 1 to 0)
        val snip = PhotoPath.render(photo, cells, bpm)
        assertEquals(stepFrames * cells.size, snip.frameCount)

        val leftMacros = PhotoKit.cellAt(photo, 0, 0, "t").patch.macros
        val rightMacros = PhotoKit.cellAt(photo, PhotoKit.COLUMNS - 1, 0, "t").patch.macros
        assertTrue(leftMacros.getValue("TUNE") < 0.3f, "left cell should read low TUNE (red): ${leftMacros["TUNE"]}")
        assertTrue(rightMacros.getValue("TUNE") > 0.6f, "right cell should read high TUNE (blue): ${rightMacros["TUNE"]}")
    }

    @Test
    fun `render refuses an empty cell list and a non-positive bpm`() {
        val photo = Photo.grey(40, 40) { _, _ -> 0.5f }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PhotoPath.render(photo, emptyList(), 120f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PhotoPath.render(photo, listOf(0 to 0), 0f) }
    }
}
