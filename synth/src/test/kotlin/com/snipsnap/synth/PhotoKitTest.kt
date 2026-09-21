package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoKitTest {

    /** Sixteen flat colour blocks, 4x4, cell (column, row) at a distinct, decodable colour. */
    private fun colorOf(column: Int, row: Int): Int {
        val idx = row * PhotoKit.COLUMNS + column
        return Photo.rgb((idx * 17) % 256, (idx * 53 + 40) % 256, (idx * 97 + 80) % 256)
    }

    private fun grid16(cellSize: Int = 8): Photo {
        val side = cellSize * 4
        return Photo.of(side, side) { x, y -> colorOf(x / cellSize, y / cellSize) }
    }

    private fun hex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

    @Test
    fun `sixteen pads, each the colour of its own cell, at the photo's own layout`() {
        val photo = grid16()
        val pads = PhotoKit.build(photo, "Test Kit")
        assertEquals(PhotoKit.COLUMNS * PhotoKit.ROWS, pads.size)
        for (p in pads) assertNotNull(p, "every cell must land a pad")

        for (row in 0 until PhotoKit.ROWS) {
            for (column in 0 until PhotoKit.COLUMNS) {
                val slot = 13 - 4 * row + column
                val pad = pads[slot - 1]!!
                assertEquals(hex(colorOf(column, row)), pad.colorHex, "cell ($column, $row) at slot $slot")
            }
        }
    }

    @Test
    fun `the photo's top-left cell is A13, its bottom-left A01`() {
        val photo = grid16()
        val pads = PhotoKit.build(photo, "Layout")
        // Slot 13 (index 12) is A13, slot 1 (index 0) is A01 - PadBanks' own numbering.
        assertEquals(hex(colorOf(0, 0)), pads[12]?.colorHex, "top-left -> A13")
        assertEquals(hex(colorOf(0, 3)), pads[0]?.colorHex, "bottom-left -> A01")
        assertEquals(hex(colorOf(3, 0)), pads[15]?.colorHex, "top-right -> A16")
        assertEquals(hex(colorOf(3, 3)), pads[3]?.colorHex, "bottom-right -> A04")
    }

    @Test
    fun `every pad's recipe regenerates the same WAV`() {
        val pads = PhotoKit.build(grid16(), "Recipe")
        for ((i, pad) in pads.withIndex()) {
            val p = pad!!
            val recipe = PadRecipe.fromJsonValue(p.recipe!!)
            val regenerated = recipe.render()
            assertTrue(regenerated.samples.contentEquals(p.snip.samples), "pad ${i + 1} does not regenerate byte-identical audio")
        }
    }

    @Test
    fun `a kit from the same photo twice is byte-identical`() {
        val photo = grid16()
        val a = PhotoKit.build(photo, "Twice")
        val b = PhotoKit.build(photo, "Twice")
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            val pa = a[i]!!
            val pb = b[i]!!
            assertEquals(pa.colorHex, pb.colorHex, "pad ${i + 1} colour")
            assertEquals(pa.drumClass, pb.drumClass, "pad ${i + 1} class")
            assertTrue(pa.snip.samples.contentEquals(pb.snip.samples), "pad ${i + 1} audio")
            assertEquals(pa.recipe, pb.recipe, "pad ${i + 1} recipe")
        }
    }

    @Test
    fun `a photo smaller than the grid still fills every pad`() {
        // 3x3, one pixel short of the 4x4 grid each way: cells overlap
        // rather than a photo this small refusing to build a kit at all.
        val photo = Photo.of(3, 3) { x, y -> colorOf(x, y) }
        val pads = PhotoKit.build(photo, "Tiny")
        assertEquals(16, pads.size)
        for (p in pads) {
            assertNotNull(p)
            assertTrue(p.snip.frameCount > 0)
        }
    }

    @Test
    fun `AutoPlace never runs - a kit pad's colour is its cell's, not its class's`() {
        // A grid of near-black cells: every pad likely classifies the
        // same way (a dark, smooth tone), but the sixteen colours must
        // still differ - the layout is the photo's, not a drum kit's.
        val pads = PhotoKit.build(grid16(), "Distinct")
        val colours = pads.map { it!!.colorHex }
        assertEquals(16, colours.toSet().size, "every cell's own colour, none collapsed to a shared class colour")
    }
}
