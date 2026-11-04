package com.snipsnap.cli

import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Mark
import com.snipsnap.shell.Schemes
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * `snipsnap mark` — the SnipSnap mark in its three forms, one PNG each,
 * on a scheme's LCD ground: the way to eyeball the symbol and the two
 * lockups at a size before they go on an icon, a title bar or a tile.
 */
object MarkCommand {

    const val DEFAULT_SIZE = 120
    private val SIZES = 16..1024

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out", "--scheme", "--size"), boolean = emptySet())
        if (opts.positional.isNotEmpty()) {
            throw CliError("mark takes no kit: snipsnap mark [--out DIR] [--scheme ID] [--size PX]")
        }
        val scheme = opts["--scheme"]?.let { ArtCommand.parseScheme(it) } ?: Schemes.DEFAULT
        val size = opts.int("--size") ?: DEFAULT_SIZE
        if (size !in SIZES) throw CliError("--size wants ${SIZES.first}..${SIZES.last}, got $size")
        val outDir = File(opts["--out"] ?: ".").apply { mkdirs() }
        val (ink, accent) = Mark.lcdTones(scheme)

        out.println("mark: scheme ${scheme.id.displayName}, ${size}px")
        for (variant in Mark.Variant.entries) {
            val file = File(outDir, "mark_${variant.id}.png")
            file.writeBytes(png(variant, size, scheme.lcd, ink, accent))
            out.println("  %-8s %s".format(variant.id, file.path))
        }
        return 0
    }

    /** The mark [size] px tall with a quarter-size margin all round, the canvas fitted to its width. */
    internal fun png(variant: Mark.Variant, size: Int, ground: Int, ink: Int, accent: Int): ByteArray {
        val margin = size / 4
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        val width = ceil(Mark.width(KitArt.AwtCanvas(probe), variant, size.toFloat())).toInt()
        probe.dispose()
        val img = BufferedImage(width + 2 * margin, size + 2 * margin, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val canvas = KitArt.AwtCanvas(g)
            canvas.fillRect(0, 0, img.width, img.height, ground)
            Mark.draw(canvas, variant, margin.toFloat(), margin.toFloat(), size.toFloat(), ink, accent)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }
}
