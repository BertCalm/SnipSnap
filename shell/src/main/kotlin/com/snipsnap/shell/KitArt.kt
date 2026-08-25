package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural cover art — the expansion tile drawn from the kit itself:
 * its waveforms, its class colours, its name. Deterministic: same kit,
 * same parameters, same bytes, so re-exporting never churns a card.
 *
 * Everything renders on the scheme's dark LCD surface — the two-surface
 * rule says that's where sound lives, and a browser tile is a picture of
 * sound. Text is a built-in 5×7 pixel font drawn as rectangles: no
 * `java.awt.Font`, no fontconfig, identical on every machine — and chunky
 * pixel type is TapeOS's accent anyway.
 *
 * The look is a taste call, so everything is a parameter — [Style],
 * scheme, seed, size — and the CLI `art` verb regenerates in one command.
 * Once a direction wins the prototyping loop it becomes the export
 * default (Z6.3).
 */
object KitArt {

    /** Four starting directions to react against, not one take. */
    enum class Style(val id: String, val blurb: String) {
        /** The kit laid on tape: every pad's audio, joined, class-coloured. */
        WAVEFORM("waveform", "all the pads end to end, each in its class colour"),

        /** The instrument itself: the 4x4 bank-A grid, lit by class. */
        GRID("grid", "the 4x4 pad grid, lit by class colour"),

        /** A chopped break as a bar chart: one bar per pad. */
        SLICES("slices", "one bar per pad, height from length"),

        /** Seeded orbits: one arc per pad, sized by its sound. */
        RINGS("rings", "seeded arcs, one per pad, sized by the sound"),
        ;

        companion object {
            fun byId(id: String): Style? = entries.firstOrNull { it.id == id.lowercase() }
        }
    }

    const val DEFAULT_SIZE = 600

    /** Render to pixels. [size] is the square edge, 64..2048. */
    fun render(
        kit: Kit,
        kitDir: File,
        style: Style,
        scheme: Scheme = Schemes.CHROME,
        seed: Int = 0,
        size: Int = DEFAULT_SIZE,
    ): BufferedImage {
        require(size in 64..2048) { "size wants 64..2048, got $size" }
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = rgb(scheme.lcd)
            g.fillRect(0, 0, size, size)

            val m = (size * 0.08f).toInt()
            val nameBand = (size * 0.16f).toInt()
            val art = Box(m, m, size - 2 * m, size - 2 * m - nameBand)

            when (style) {
                Style.WAVEFORM -> waveform(g, art, kit, kitDir)
                Style.GRID -> grid(g, art, kit, scheme)
                Style.SLICES -> slices(g, art, kit, kitDir)
                Style.RINGS -> rings(g, art, kit, kitDir, seed)
            }

            // The name, pixel type, centred in the bottom band.
            val label = kit.name.uppercase()
            val bandTop = size - m - nameBand + (nameBand * 0.25f).toInt()
            PixelType.draw(
                g, label, size / 2, bandTop, nameBand / 2, rgb(scheme.lcdInk),
                centered = true, maxWidthPx = size - 2 * m,
            )

            // A hairline between sound and name — the LCD's readout rule.
            g.color = rgb(scheme.amber)
            g.fillRect(m, size - m - nameBand + (nameBand * 0.05f).toInt(), size - 2 * m, max(1, size / 300))
        } finally {
            g.dispose()
        }
        return img
    }

    /** Render straight to PNG bytes (what `ExpansionWriter` wants). */
    fun png(
        kit: Kit,
        kitDir: File,
        style: Style,
        scheme: Scheme = Schemes.CHROME,
        seed: Int = 0,
        size: Int = DEFAULT_SIZE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(render(kit, kitDir, style, scheme, seed, size), "png", out)
        return out.toByteArray()
    }

    // ---- styles ------------------------------------------------------------

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** A pad's audio and looks, loaded once per render. */
    private data class Voice(val mono: FloatArray, val color: Color, val peak: Float)

    private fun voices(kit: Kit, kitDir: File): List<Voice> =
        kit.pads.sortedBy { it.slot }.mapNotNull { pad ->
            val f = File(kitDir, pad.sampleFile)
            if (!f.isFile) return@mapNotNull null
            val snip = WavReader.read(f)
            val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
            var peak = 0f
            for (s in mono) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            Voice(mono, rgb(Schemes.classColor(pad.drumClass)), peak)
        }

    private fun waveform(g: Graphics2D, box: Box, kit: Kit, kitDir: File) {
        val vs = voices(kit, kitDir)
        if (vs.isEmpty()) return
        val total = vs.sumOf { it.mono.size }
        val joined = FloatArray(total)
        var at = 0
        for (v in vs) {
            v.mono.copyInto(joined, at)
            at += v.mono.size
        }
        val peaks = PeaksPyramid.build(joined)
        val norm = 1f / max(1e-6f, vs.maxOf { it.peak })
        val midY = box.y + box.h / 2
        val amp = box.h / 2f * 0.92f

        // Which pad owns which frame range, for per-column colour.
        val starts = IntArray(vs.size)
        var acc = 0
        vs.forEachIndexed { i, v ->
            starts[i] = acc
            acc += v.mono.size
        }

        val cols = peaks.columns(0, total, box.w)
        var owner = 0
        for (c in 0 until box.w) {
            val frame = (total.toLong() * c / box.w).toInt()
            while (owner + 1 < vs.size && frame >= starts[owner + 1]) owner++
            g.color = vs[owner].color
            val col = cols[c]
            val top = midY - (col.max * norm * amp).toInt()
            val bottom = midY - (col.min * norm * amp).toInt()
            g.fillRect(box.x + c, min(top, bottom), 1, max(1, abs(bottom - top)))
        }
    }

    private fun grid(g: Graphics2D, box: Box, kit: Kit, scheme: Scheme) {
        val side = min(box.w, box.h)
        val ox = box.x + (box.w - side) / 2
        val oy = box.y + (box.h - side) / 2
        val gap = (side * 0.04f).toInt().coerceAtLeast(2)
        val cell = (side - 3 * gap) / 4
        val bevel = max(2, cell / 24)

        for (row in 0 until 4) {
            for (col in 0 until 4) {
                // MPC layout: A01 bottom-left, A13 top-left.
                val slot = (3 - row) * 4 + col + 1
                val pad = kit.pad(slot)
                val x = ox + col * (cell + gap)
                val y = oy + row * (cell + gap)
                if (pad == null) {
                    // Empty pads sit dim on the LCD; the kit is the light.
                    g.color = lighten(rgb(scheme.lcd), 0.07f)
                    g.fillRect(x, y, cell, cell)
                } else {
                    val c = rgb(Schemes.classColor(pad.drumClass))
                    g.color = c
                    g.fillRect(x, y, cell, cell)
                    // The raised bevel every TapeOS pad wears.
                    g.color = lighten(c, 0.35f)
                    g.fillRect(x, y, cell, bevel)
                    g.fillRect(x, y, bevel, cell)
                    g.color = darken(c, 0.35f)
                    g.fillRect(x, y + cell - bevel, cell, bevel)
                    g.fillRect(x + cell - bevel, y, bevel, cell)
                }
            }
        }
    }

    private fun slices(g: Graphics2D, box: Box, kit: Kit, kitDir: File) {
        val vs = voices(kit, kitDir).take(32)
        if (vs.isEmpty()) return
        val maxLen = vs.maxOf { it.mono.size }.toFloat()
        val gap = max(1, (box.w * 0.012f).toInt())
        val barW = max(1, (box.w - gap * (vs.size - 1)) / vs.size)
        val used = barW * vs.size + gap * (vs.size - 1)
        var x = box.x + (box.w - used) / 2
        for (v in vs) {
            // sqrt keeps a long loop from flattening every one-shot.
            val h = max(2, (sqrt(v.mono.size / maxLen) * box.h).toInt())
            g.color = v.color
            g.fillRect(x, box.y + box.h - h, barW, h)
            x += barW + gap
        }
    }

    private fun rings(g: Graphics2D, box: Box, kit: Kit, kitDir: File, seed: Int) {
        val vs = voices(kit, kitDir)
        if (vs.isEmpty()) return
        val rnd = Random(seed)
        val cx = box.x + box.w / 2f
        val cy = box.y + box.h / 2f
        val maxR = min(box.w, box.h) / 2f * 0.95f
        val maxLen = vs.maxOf { it.mono.size }.toFloat()
        val maxPeak = max(1e-6f, vs.maxOf { it.peak })

        vs.forEachIndexed { i, v ->
            val r = maxR * (i + 1) / (vs.size + 1)
            val sweep = 30f + 300f * (v.mono.size / maxLen)
            val start = rnd.nextInt(360).toFloat()
            val stroke = 1.5f + (v.peak / maxPeak) * (maxR * 0.05f)
            g.color = v.color
            g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(
                Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, start, sweep, Arc2D.OPEN),
            )
        }
    }

    // ---- colour helpers ----------------------------------------------------

    private fun rgb(packed: Int) = Color((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)

    private fun lighten(c: Color, k: Float) = Color(
        (c.red + (255 - c.red) * k).toInt().coerceIn(0, 255),
        (c.green + (255 - c.green) * k).toInt().coerceIn(0, 255),
        (c.blue + (255 - c.blue) * k).toInt().coerceIn(0, 255),
    )

    private fun darken(c: Color, k: Float) = Color(
        (c.red * (1 - k)).toInt().coerceIn(0, 255),
        (c.green * (1 - k)).toInt().coerceIn(0, 255),
        (c.blue * (1 - k)).toInt().coerceIn(0, 255),
    )
}
