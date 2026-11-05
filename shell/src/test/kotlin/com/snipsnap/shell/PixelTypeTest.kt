package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertTrue

class PixelTypeTest {

    @Test
    fun `pixel type fits long names instead of overflowing`() {
        // Every char the MPC-safe alphabet allows, plus one it doesn't:
        // the box glyph renders visibly rather than crashing.
        val name = "The Quick-Brown_Fox & 0123456789.é"
        val px = PixelType.fit(name, maxWidthPx = 300, maxHeightPx = 70)
        assertTrue(PixelType.width(name, px) <= 300, "shrunk to fit")

        // Every glyph in the face draws without error, box fallback included.
        val img = java.awt.image.BufferedImage(600, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        PixelType.draw(
            KitArt.AwtCanvas(g), "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789-_.'&é", 0, 0, 14,
            0xFFFFFF, maxWidthPx = 600,
        )
        g.dispose()
    }
}
