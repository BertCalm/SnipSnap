package com.snipsnap.shell

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtCanvasTest {

    private fun render(size: Int, draw: (ArtCanvas) -> Unit): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            draw(KitArt.AwtCanvas(g))
        } finally {
            g.dispose()
        }
        return img
    }

    private fun bytesOf(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun inkColumns(img: BufferedImage): List<Int> =
        (0 until img.width).filter { x -> (0 until img.height).any { y -> img.getRGB(x, y) and 0xFFFFFF != 0 } }

    @Test
    fun `the bundled font loads through the class loader`() {
        assertTrue(ArtFont.UNBOUNDED_EXTRABOLD.bytes().size > 50_000)
    }

    @Test
    fun `the font loads headless`() {
        System.setProperty("java.awt.headless", "true")
        val font = AwtFonts.font(ArtFont.UNBOUNDED_EXTRABOLD)
        assertTrue(font.fontName.contains("Unbounded"), font.fontName)
    }

    @Test
    fun `fillPolygon paints a diamond and nothing outside it`() {
        val img = render(100) {
            it.fillRect(0, 0, 100, 100, 0x000000)
            it.fillPolygon(floatArrayOf(50f, 90f, 50f, 10f), floatArrayOf(10f, 50f, 90f, 50f), 0xFF0000)
        }
        assertEquals(0xFF0000, img.getRGB(50, 50) and 0xFFFFFF)
        assertEquals(0x000000, img.getRGB(5, 5) and 0xFFFFFF)
    }

    @Test
    fun `drawText paints with the bundled face, deterministic, anchored`() {
        val draw: (ArtCanvas) -> Unit = {
            it.fillRect(0, 0, 200, 200, 0x000000)
            it.drawText("SNAP", ArtFont.UNBOUNDED_EXTRABOLD, 40f, 100f, 120f, TextAnchor.MIDDLE, 0xFFFFFF)
        }
        val a = render(200, draw)
        val b = render(200, draw)
        assertTrue(bytesOf(a).contentEquals(bytesOf(b)), "same call, same bytes")
        val lit = (0 until 200).sumOf { y -> (0 until 200).count { x -> a.getRGB(x, y) and 0xFFFFFF != 0 } }
        assertTrue(lit > 300, "text was painted ($lit lit pixels)")
        val columns = inkColumns(a)
        assertTrue(columns.first() < 100 && columns.last() > 100, "MIDDLE anchor straddles x=100: ${columns.first()}..${columns.last()}")
    }

    @Test
    fun `the cap ratio is the face's own`() {
        // Mark sizes type so caps match the symbol's height; CAP_RATIO must
        // be what the bundled face really draws, not a guess. H has flat
        // top and bottom, so its lit rows are exactly the cap height.
        val em = 400f
        val img = render(600) {
            it.fillRect(0, 0, 600, 600, 0x000000)
            it.drawText("H", ArtFont.UNBOUNDED_EXTRABOLD, em, 100f, 500f, TextAnchor.START, 0xFFFFFF)
        }
        val rows = (0 until 600).filter { y -> (0 until 600).any { x -> img.getRGB(x, y) and 0xFFFFFF != 0 } }
        val cap = (rows.last() - rows.first() + 1).toFloat()
        assertTrue(kotlin.math.abs(cap / em - Mark.CAP_RATIO) < 0.02f, "drawn cap ${cap / em} of the em, CAP_RATIO ${Mark.CAP_RATIO}")
    }

    @Test
    fun `textWidth grows with size and matches drawn extent`() {
        var w20 = 0f
        var w40 = 0f
        val img = render(400) {
            w20 = it.textWidth("SNIP", ArtFont.UNBOUNDED_EXTRABOLD, 20f)
            w40 = it.textWidth("SNIP", ArtFont.UNBOUNDED_EXTRABOLD, 40f)
            it.fillRect(0, 0, 400, 400, 0x000000)
            it.drawText("SNIP", ArtFont.UNBOUNDED_EXTRABOLD, 40f, 20f, 200f, TextAnchor.START, 0xFFFFFF)
        }
        assertTrue(w40 > w20 * 1.8f && w40 < w20 * 2.2f, "w20=$w20 w40=$w40")
        val columns = inkColumns(img)
        val drawn = (columns.last() - columns.first() + 1).toFloat()
        assertTrue(kotlin.math.abs(drawn - w40) < w40 * 0.15f, "measured $w40, drawn $drawn")
    }
}
