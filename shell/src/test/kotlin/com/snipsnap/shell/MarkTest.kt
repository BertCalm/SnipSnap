package com.snipsnap.shell

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkTest {

    /** A text call as the mark issued it, with the width the face would really take. */
    private class TextCall(val text: String, val x: Float, val baselineY: Float, val anchor: TextAnchor, val colorRgb: Int, val measuredWidth: Float) {
        val minX get() = when (anchor) { TextAnchor.START -> x; TextAnchor.MIDDLE -> x - measuredWidth / 2; TextAnchor.END -> x - measuredWidth }
        val maxX get() = minX + measuredWidth
    }

    private class Polygon(val xs: FloatArray, val ys: FloatArray, val colorRgb: Int) {
        val minX get() = xs.min()
        val maxX get() = xs.max()
        val minY get() = ys.min()
        val maxY get() = ys.max()
    }

    /** Records what the mark draws; text is measured through a real AWT canvas so the layout is honest. */
    private class RecordingCanvas : ArtCanvas {
        val polygons = ArrayList<Polygon>()
        val texts = ArrayList<TextCall>()
        private val measure = KitArt.AwtCanvas(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).createGraphics())

        override fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int) = error("the mark fills no rectangles")
        override fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int) = error("the mark draws no arcs")
        override fun fillPolygon(xs: FloatArray, ys: FloatArray, colorRgb: Int) { polygons += Polygon(xs.copyOf(), ys.copyOf(), colorRgb) }
        override fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int) {
            texts += TextCall(text, x, baselineY, anchor, colorRgb, measure.textWidth(text, font, sizePx))
        }
        override fun textWidth(text: String, font: ArtFont, sizePx: Float): Float = measure.textWidth(text, font, sizePx)
    }

    private fun png(variant: Mark.Variant, height: Float, ink: Int, accent: Int): ByteArray {
        val margin = ceil(height / 4).toInt()
        val probe = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).createGraphics()
        val width = ceil(Mark.width(KitArt.AwtCanvas(probe), variant, height)).toInt()
        val img = BufferedImage(width + 2 * margin, ceil(height).toInt() + 2 * margin, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val c = KitArt.AwtCanvas(g)
        c.fillRect(0, 0, img.width, img.height, 0x101010)
        Mark.draw(c, variant, margin.toFloat(), margin.toFloat(), height, ink, accent)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun hasColour(png: ByteArray, rgb: Int): Boolean {
        val img = ImageIO.read(png.inputStream())
        for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) and 0xFFFFFF == rgb) return true
        return false
    }

    @Test
    fun `the symbol is four touching pads filling its box`() {
        val d = Mark.symbol(10f, 20f, 40f)
        assertEquals(4, d.size)
        assertEquals(listOf(10f, 20f), listOf(d.minOf { it.cx - it.r }, d.minOf { it.cy - it.r }))
        assertEquals(listOf(50f, 60f), listOf(d.maxOf { it.cx + it.r }, d.maxOf { it.cy + it.r }))
        assertEquals(d[0].cx + d[0].r, d[1].cx - d[1].r, "TL's right point is TR's left point")
        assertEquals(d[0].cy + d[0].r, d[2].cy - d[2].r, "TL's bottom point is BL's top point")
        assertTrue(d.all { it.r == 10f }, "pads touch: r = height / 4")
    }

    @Test
    fun `every variant renders deterministic with both tones present`() {
        for (v in Mark.Variant.entries) for (h in listOf(16f, 120f)) {
            val a = png(v, h, ink = 0xF2F2F2, accent = 0xFF7A3D)
            val b = png(v, h, ink = 0xF2F2F2, accent = 0xFF7A3D)
            assertTrue(a.contentEquals(b), "$v@$h")
            assertTrue(hasColour(a, 0xF2F2F2) && hasColour(a, 0xFF7A3D), "$v@$h shows ink and accent")
        }
    }

    @Test
    fun `the stacked symbol is as tall as the two lines and sits after the wider word`() {
        val rec = RecordingCanvas()
        Mark.draw(rec, Mark.Variant.STACKED, 0f, 0f, 100f, 1, 2)
        assertEquals(4, rec.polygons.size)
        assertEquals(0f, rec.polygons.minOf { it.minY })
        assertEquals(100f, rec.polygons.maxOf { it.maxY })
        assertEquals(listOf("SNIP", "SNAP"), rec.texts.map { it.text })
        val wider = rec.texts.maxOf { it.measuredWidth }
        assertEquals(wider + Mark.STACKED_GAP * 100f, rec.polygons.minOf { it.minX }, 0.01f)
        assertEquals(listOf(0f, 0f), rec.texts.map { it.x }, "both words flush left")
        assertTrue(rec.texts.all { it.anchor == TextAnchor.START })
        assertEquals(100f, rec.texts[1].baselineY, "SNAP's baseline is the box bottom")
        assertTrue(rec.texts[0].baselineY < rec.texts[1].baselineY, "SNIP sits above SNAP")
    }

    @Test
    fun `the lockup puts the symbol at cap height between the words`() {
        val rec = RecordingCanvas()
        Mark.draw(rec, Mark.Variant.LOCKUP, 0f, 0f, 100f, ink = 1, accent = 2)
        assertEquals(4, rec.polygons.size)
        assertEquals(0f, rec.polygons.minOf { it.minY })
        assertEquals(100f, rec.polygons.maxOf { it.maxY })
        val symbolMinX = rec.polygons.minOf { it.minX }
        val symbolMaxX = rec.polygons.maxOf { it.maxX }
        val snip = rec.texts.single { it.text == "SNIP" }
        val snap = rec.texts.single { it.text == "SNAP" }
        assertEquals(0f, snip.minX, 0.01f, "SNIP starts the mark")
        assertEquals(symbolMinX - Mark.LOCKUP_GAP * 100f, snip.maxX, 0.01f, "SNIP ends a gap before the symbol")
        assertEquals(symbolMaxX + Mark.LOCKUP_GAP * 100f, snap.minX, 0.01f, "SNAP starts a gap after the symbol")
        assertEquals(1, snip.colorRgb, "SNIP in ink")
        assertEquals(2, snap.colorRgb, "SNAP in accent")
        assertEquals(listOf(100f, 100f), rec.texts.map { it.baselineY }, "both words on the box's baseline")
    }

    @Test
    fun `width matches the drawn extent`() {
        for (v in Mark.Variant.entries) {
            val rec = RecordingCanvas()
            Mark.draw(rec, v, 0f, 0f, 100f, 1, 2)
            val drawn = maxOf(rec.polygons.maxOf { it.maxX }, rec.texts.maxOfOrNull { it.maxX } ?: 0f)
            val reported = Mark.width(RecordingCanvas(), v, 100f)
            assertTrue(abs(drawn - reported) <= 0.5f, "$v: drawn $drawn, reported $reported")
        }
        assertEquals(100f, Mark.width(RecordingCanvas(), Mark.Variant.SYMBOL, 100f), "the symbol is square")
    }

    @Test
    fun `the symbol keeps two tones on every scheme`() {
        for (s in Schemes.ALL) {
            val (ink, accent) = Mark.tones(s)
            assertTrue(ink != accent, s.id.toString())
        }
        val flat = Schemes.DEFAULT.copy(accent = Schemes.DEFAULT.ink)
        val (ink, accent) = Mark.tones(flat)
        assertEquals(flat.ink, ink)
        assertEquals(flat.amber, accent, "a scheme whose accent equals its ink falls back to amber")
    }

    @Test
    fun `a mark under eight pixels is refused`() {
        assertFailsWith<IllegalArgumentException> { Mark.draw(RecordingCanvas(), Mark.Variant.SYMBOL, 0f, 0f, 7.9f, 1, 2) }
        Mark.draw(RecordingCanvas(), Mark.Variant.SYMBOL, 0f, 0f, Mark.MIN_HEIGHT, 1, 2)
    }

    @Test
    fun `variants are found by their id`() {
        assertEquals(Mark.Variant.STACKED, Mark.Variant.byId("stacked"))
        assertEquals(null, Mark.Variant.byId("wordmark"))
    }
}
