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
 * The pixel surface [KitArt.draw] paints onto — deliberately the smallest
 * surface both an AWT desktop/CLI renderer and an Android
 * (`android.graphics`) renderer can implement, so the art logic itself
 * never has to know which platform is asking. Color is a packed
 * 0xRRGGBB int, the same shape [Scheme]'s own fields already use, so no
 * conversion sits at the boundary either.
 *
 * Public, not internal: `:app` is a separate Gradle/Kotlin module from
 * `:shell` and implements this directly against `android.graphics` — see
 * [KitArt.draw]'s own doc for why that split exists.
 */
interface ArtCanvas {
    fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int)
    fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int)
}

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
 *
 * [draw] is the actual rendering — every style, the caption, the
 * hairline — expressed purely against [ArtCanvas]. Nothing it calls
 * touches `java.awt`/`javax.imageio`: those only enter through [render]/
 * [png] below, the AWT adapter this class, the CLI, and every test here
 * have always used. `:app`'s Android renderer calls [draw] directly with
 * its own `android.graphics`-backed [ArtCanvas] — `java.awt` and
 * `javax.imageio` do not exist on Android at any API level, so a
 * renderer that went through [render]/[png] instead would crash with
 * `NoClassDefFoundError` the instant it ran (this is exactly what DUB
 * did before this split existed, on EXPANSION/XPN writes).
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

    /**
     * The platform-agnostic half of rendering onto [canvas]: LCD
     * background, the style dispatch, the caption, the readout hairline.
     * See the class doc for why this exists apart from [render]/[png].
     * Public, not internal — see [ArtCanvas]'s own doc.
     */
    fun draw(
        canvas: ArtCanvas,
        kit: Kit,
        kitDir: File,
        style: Style,
        scheme: Scheme,
        seed: Int,
        size: Int,
        label: String,
    ) {
        require(size in 64..2048) { "size wants 64..2048, got $size" }
        canvas.fillRect(0, 0, size, size, scheme.lcd)

        val m = (size * 0.08f).toInt()
        val nameBand = (size * 0.16f).toInt()
        val art = Box(m, m, size - 2 * m, size - 2 * m - nameBand)

        when (style) {
            Style.WAVEFORM -> waveform(canvas, art, kit, kitDir)
            Style.GRID -> grid(canvas, art, kit, scheme)
            Style.SLICES -> slices(canvas, art, kit, kitDir)
            Style.RINGS -> rings(canvas, art, kit, kitDir, seed)
        }

        // The name, pixel type, centred in the bottom band.
        val caption = label.uppercase()
        val bandTop = size - m - nameBand + (nameBand * 0.25f).toInt()
        for (px in PixelType.rects(caption, size / 2, bandTop, nameBand / 2, centered = true, maxWidthPx = size - 2 * m)) {
            canvas.fillRect(px.x, px.y, px.w, px.h, scheme.lcdInk)
        }

        // A hairline between sound and name — the LCD's readout rule.
        canvas.fillRect(m, size - m - nameBand + (nameBand * 0.05f).toInt(), size - 2 * m, max(1, size / 300), scheme.amber)
    }

    /** Render to pixels. [size] is the square edge, 64..2048. */
    fun render(
        kit: Kit,
        kitDir: File,
        style: Style,
        scheme: Scheme = Schemes.DEFAULT,
        seed: Int = 0,
        size: Int = DEFAULT_SIZE,
        /** The tile's caption — a pack tile carries the pack's title, not one kit's. */
        label: String = kit.name,
    ): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            draw(AwtCanvas(g), kit, kitDir, style, scheme, seed, size, label)
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
        scheme: Scheme = Schemes.DEFAULT,
        seed: Int = 0,
        size: Int = DEFAULT_SIZE,
        label: String = kit.name,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(render(kit, kitDir, style, scheme, seed, size, label), "png", out)
        return out.toByteArray()
    }

    // ---- the AWT adapter — the only place in this file java.awt appears ----

    /** Internal, not private: [JCard] reuses [waveform] against its own [Graphics2D]. */
    internal class AwtCanvas(private val g: Graphics2D) : ArtCanvas {
        override fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int) {
            g.color = Color(colorRgb)
            g.fillRect(x, y, w, h)
        }

        override fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int) {
            g.color = Color(colorRgb)
            g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, startDeg, sweepDeg, Arc2D.OPEN))
        }
    }

    // ---- styles ------------------------------------------------------------

    internal data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** A pad's audio and looks, loaded once per render. [color] is packed 0xRRGGBB. */
    private data class Voice(val mono: FloatArray, val color: Int, val peak: Float)

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
            Voice(mono, Schemes.classColor(pad.drumClass), peak)
        }

    internal fun waveform(canvas: ArtCanvas, box: Box, kit: Kit, kitDir: File) {
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
            val col = cols[c]
            val top = midY - (col.max * norm * amp).toInt()
            val bottom = midY - (col.min * norm * amp).toInt()
            canvas.fillRect(box.x + c, min(top, bottom), 1, max(1, abs(bottom - top)), vs[owner].color)
        }
    }

    private fun grid(canvas: ArtCanvas, box: Box, kit: Kit, scheme: Scheme) {
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
                    canvas.fillRect(x, y, cell, cell, lighten(scheme.lcd, 0.07f))
                } else {
                    val c = Schemes.classColor(pad.drumClass)
                    canvas.fillRect(x, y, cell, cell, c)
                    // The raised bevel every TapeOS pad wears.
                    canvas.fillRect(x, y, cell, bevel, lighten(c, 0.35f))
                    canvas.fillRect(x, y, bevel, cell, lighten(c, 0.35f))
                    canvas.fillRect(x, y + cell - bevel, cell, bevel, darken(c, 0.35f))
                    canvas.fillRect(x + cell - bevel, y, bevel, cell, darken(c, 0.35f))
                }
            }
        }
    }

    private fun slices(canvas: ArtCanvas, box: Box, kit: Kit, kitDir: File) {
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
            canvas.fillRect(x, box.y + box.h - h, barW, h, v.color)
            x += barW + gap
        }
    }

    private fun rings(canvas: ArtCanvas, box: Box, kit: Kit, kitDir: File, seed: Int) {
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
            canvas.drawArc(cx, cy, r, start, sweep, stroke, v.color)
        }
    }

    // ---- colour helpers (packed 0xRRGGBB in, same out) ----------------------

    private fun lighten(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r + (255 - r) * k).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * k).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * k).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    private fun darken(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r * (1 - k)).toInt().coerceIn(0, 255)
        val ng = (g * (1 - k)).toInt().coerceIn(0, 255)
        val nb = (b * (1 - k)).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }
}
