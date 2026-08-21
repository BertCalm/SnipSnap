package com.snipsnap.synth

import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.ExpansionWriter
import com.snipsnap.kit.KitAssembler
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the acceptance *expansion* under testkit/ — the tier-2 export,
 * browsable in the MPC's Expansion tab with a TapeOS tile. Run via
 * `./gradlew :synth:generateExpansionPack`.
 *
 * This is the hardware check for `ExpansionWriter`'s unverified XML shape:
 * copy `testkit/Expansions/` onto the card; if the pack tiles up in the
 * browser, the element names are right. If the browser ignores it, grab a
 * real pack's `Expansion.xml` (see reference/README.md) and the writer gets
 * corrected in minutes.
 *
 * The tile is AWT-drawn here in test sources on purpose: the shipping
 * modules stay free of JVM-only imaging so Android can hand the writer a
 * bitmap from its own canvas.
 */
object ExpansionPackGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".expansion-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Factory Kit", ThumpKits.classic(), work)
        val meta = ExpansionMeta(
            title = "SnipSnap Factory",
            manufacturer = "SnipSnap",
            version = 1,
            identifier = "app.snipsnap.factory",
            description = "Sixteen synthesized pads from the THUMP and TINES engines.",
        )
        val result = ExpansionWriter.write(
            kit, work, root, meta,
            artworkPng = tile(meta.title),
            overwrite = true,
        )
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples, xml, tile)")
    }

    /** A 1000×1000 TapeOS tile: gray bevel chrome around a green LCD. */
    private fun tile(title: String): ByteArray {
        val size = 1000
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val gray = Color(0xC3, 0xC7, 0xCB)
        val hi = Color.WHITE
        val dark = Color(0x3F, 0x43, 0x47)
        val lcd = Color(0x0C, 0x13, 0x0B)
        val ink = Color(0x49, 0xE8, 0x3E)
        val dim = Color(0x2F, 0x7C, 0x2A)
        val amber = Color(0xFF, 0xB0, 0x00)

        // Chrome with a raised bevel.
        g.color = gray
        g.fillRect(0, 0, size, size)
        g.stroke = BasicStroke(8f)
        g.color = hi
        g.drawLine(4, 4, size - 4, 4); g.drawLine(4, 4, 4, size - 4)
        g.color = dark
        g.drawLine(size - 4, 4, size - 4, size - 4); g.drawLine(4, size - 4, size - 4, size - 4)

        // Sunken LCD panel.
        val m = 70
        g.color = dark
        g.fillRect(m - 6, m - 6, size - 2 * m + 12, size - 2 * m + 12)
        g.color = lcd
        g.fillRect(m, m, size - 2 * m, size - 2 * m)

        // Deterministic waveform bars from the title.
        var seed = title.fold(1) { acc, c -> acc * 31 + c.code }
        fun rng(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return seed / 0x7fffffff.toFloat() }
        g.color = dim
        val bars = 46
        val barW = (size - 2 * m - 40) / bars
        for (i in 0 until bars) {
            val h = (60 + rng() * 420).toInt()
            g.color = if (i % 7 == 0) ink else dim
            g.fillRect(m + 20 + i * barW, size / 2 - h / 2 + 60, barW - 6, h)
        }

        // Cassette badge.
        val cw = 300; val ch = 180
        val cx = (size - cw) / 2; val cy = 150
        g.color = Color(0xFF, 0x7A, 0x1A)
        g.fillRoundRect(cx, cy, cw, ch, 28, 28)
        g.color = Color(0x3F, 0x22, 0x05)
        g.stroke = BasicStroke(6f)
        g.drawRoundRect(cx, cy, cw, ch, 28, 28)
        g.color = Color(0xF4, 0xEF, 0xE4)
        g.fillOval(cx + 60, cy + 60, 60, 60)
        g.fillOval(cx + cw - 120, cy + 60, 60, 60)
        g.color = Color(0x3F, 0x22, 0x05)
        g.drawOval(cx + 60, cy + 60, 60, 60)
        g.drawOval(cx + cw - 120, cy + 60, 60, 60)

        // Wordmark and title, on a cleared band so the bars never collide.
        g.color = lcd
        g.fillRect(m + 8, 480 - 96, size - 2 * m - 16, 128)
        g.color = ink
        g.font = Font(Font.MONOSPACED, Font.BOLD, 92)
        drawCentered(g, "SNIPSNAP", size, 480)
        g.color = amber
        g.font = Font(Font.MONOSPACED, Font.BOLD, 58)
        drawCentered(g, title.uppercase(), size, 850)

        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun drawCentered(g: java.awt.Graphics2D, text: String, width: Int, baseline: Int) {
        val w = g.fontMetrics.stringWidth(text)
        g.drawString(text, (width - w) / 2, baseline)
    }
}
