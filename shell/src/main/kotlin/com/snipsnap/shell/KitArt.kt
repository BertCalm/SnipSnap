package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
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

    /** Fill the closed polygon through the points ([xs][i], [ys][i]) in order. */
    fun fillPolygon(xs: FloatArray, ys: FloatArray, colorRgb: Int)

    /**
     * Set [text] in a bundled [font] at [sizePx] (the em, in pixels) with
     * its baseline on [baselineY] and [x] at the [anchor] edge.
     */
    fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int)

    /** The advance width [drawText] would cover for [text] at [sizePx]. */
    fun textWidth(text: String, font: ArtFont, sizePx: Float): Float

    /** Fill [path] (closed as drawn; an open path is closed for the fill) with [fill]. */
    fun fillPath(path: ArtPath, fill: ArtFill)

    /** Stroke [path] [strokeWidth] wide with round caps and joins, painted with [fill]. */
    fun strokePath(path: ArtPath, strokeWidth: Float, fill: ArtFill)

    /** Fill the circle of radius [r] about ([cx], [cy]) with [fill]. */
    fun fillCircle(cx: Float, cy: Float, r: Float, fill: ArtFill)
}

/**
 * Procedural cover art — the expansion tile drawn from the kit itself:
 * its waveforms, its class colours, its name. Deterministic: same kit,
 * same parameters, same bytes, so re-exporting never churns a card.
 *
 * Everything renders on a [Palette]'s ground with the kit name set in a
 * bundled face inside the style's own name zone, and the SnipSnap mark
 * bottom-right in the palette's ink and first field tone. The four knobs
 * ([ArtKnobs]: style, palette, seed pad, mark) are what a person picks;
 * the CLI `art` verb regenerates every style in one command so the owner
 * can judge them on screen.
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

    /** The family: four redrawn, four new, one blurb each for the CLI's listing. */
    enum class Style(val id: String, val blurb: String) {
        /** The kit laid on tape: every pad's audio joined as one river, class colours blending along it. */
        WAVEFORM("waveform", "every pad end to end as one river, class colours blending along it"),

        /** The instrument itself: the 4x4 bank, each pad a small bloom of its own sound. */
        GRID("grid", "the 4x4 bank, each pad a small bloom of its own sound"),

        /** A chopped break as a wheel: one wedge per pad, reaching out by its length. */
        SLICES("slices", "one wedge per pad, radiating, sized by length"),

        /** Seeded orbits: one glowing arc per pad, weighted by its sound. */
        RINGS("rings", "seeded arcs per pad with weight and glow"),

        /** The seed pad's loudness wrapped round a circle, the name in the hole. */
        BLOOM("bloom", "the seed's loudness wrapped round a circle, the name in the hole"),

        /** The seed sliced into ridge lines that fade back, the name top-left in an italic serif. */
        RIDGES("ridges", "the attack sliced into ridge lines that fade back"),

        /** A dot field that swells with the seed's loudness, the name on a band. */
        FIELD("field", "a dot field that swells with the sound, the name on a band"),

        /** The seed's attack as one glowing stroke, the name tracked wide below. */
        RIBBON("ribbon", "the attack as one glowing stroke"),
        ;

        companion object {
            fun byId(id: String): Style? = entries.firstOrNull { it.id == id.lowercase() }
        }
    }

    const val DEFAULT_SIZE = 600

    /**
     * The platform-agnostic half of rendering onto [canvas]: the ground,
     * the style's field and name, the mark. See the class doc for why
     * this exists apart from [render]/[png]. Public, not internal — see
     * [ArtCanvas]'s own doc. [ringsSeed] is the one hidden parameter: the
     * dice RINGS throws for its start angles, settable from the CLI.
     */
    fun draw(
        canvas: ArtCanvas,
        kit: Kit,
        kitDir: File,
        knobs: ArtKnobs,
        size: Int,
        label: String,
        ringsSeed: Int = 0,
    ) = draw(canvas, kit, TileSound.pads(kit, kitDir), knobs, size, label, ringsSeed)

    /**
     * [draw] from [pads] already read ([TileSound.pads]): the phone's art
     * picker reads a kit once and draws all eight previews from it, rather
     * than decoding every WAV once per preview.
     */
    fun draw(
        canvas: ArtCanvas,
        kit: Kit,
        pads: List<PadSound>,
        knobs: ArtKnobs,
        size: Int,
        label: String,
        ringsSeed: Int = 0,
    ) {
        require(size in 64..2048) { "size wants 64..2048, got $size" }
        val palette = knobs.palette
        canvas.fillRect(0, 0, size, size, palette.ground)
        val zone = nameZone(canvas, knobs.style, knobs.mark, size)
        val seed = TileSound.seedOf(pads, knobs.seedSlot)

        when (knobs.style) {
            Style.BLOOM -> TileStyles.bloom(canvas, size, palette, seed, label, zone)
            Style.RIDGES -> TileStyles.ridges(canvas, size, palette, seed, label, zone)
            Style.FIELD -> TileStyles.field(canvas, size, palette, seed, label, zone)
            Style.RIBBON -> TileStyles.ribbon(canvas, size, palette, seed, label, zone)
            Style.WAVEFORM -> TileStyles.river(canvas, size, palette, pads, label, zone)
            Style.GRID -> TileStyles.padBlooms(canvas, size, palette, pads, label, zone)
            Style.SLICES -> TileStyles.wheel(canvas, size, palette, pads, label, zone)
            Style.RINGS -> TileStyles.orbits(canvas, size, palette, pads, ringsSeed, label, zone)
        }

        val (mx, my) = markOrigin(canvas, knobs.mark, size)
        Mark.draw(canvas, knobs.mark, mx, my, markHeight(knobs.mark, size), palette.ink, palette.field1)
    }

    /** Render to pixels. [size] is the square edge, 64..2048. */
    fun render(
        kit: Kit,
        kitDir: File,
        knobs: ArtKnobs,
        size: Int = DEFAULT_SIZE,
        /** The tile's caption — a pack tile carries the pack's title, not one kit's. */
        label: String = kit.name,
        ringsSeed: Int = 0,
    ): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            draw(AwtCanvas(g), kit, kitDir, knobs, size, label, ringsSeed)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Render straight to PNG bytes (what `ExpansionWriter` wants). */
    fun png(
        kit: Kit,
        kitDir: File,
        knobs: ArtKnobs,
        size: Int = DEFAULT_SIZE,
        label: String = kit.name,
        ringsSeed: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(render(kit, kitDir, knobs, size, label, ringsSeed), "png", out)
        return out.toByteArray()
    }

    /**
     * [png] in the shape `ExportWizardModel.artRenderer` takes: the desktop
     * default, named here so the write path never spells the AWT call.
     */
    fun awtPng(kit: Kit, kitDir: File, knobs: ArtKnobs): ByteArray = png(kit, kitDir, knobs)

    // ---- the tile's geometry, shared by every style and pinned by KitArtTest ----

    /** The margin every style keeps from the tile's edge. */
    internal fun margin(size: Int): Int = Math.round(size * 0.06f)

    /** Where the field goes for the styles that set their name in the bottom strip. */
    internal fun fieldBox(size: Int): Box {
        val m = margin(size)
        return Box(m, m, size - 2 * m, Math.round(size * 0.70f))
    }

    /**
     * The mark's cap height on a tile, never below what [Mark.draw] accepts:
     * on a tile under 200 px the one-line lockup sits at its floor and the
     * strip's name zone, which ends a margin before the mark, gives way to
     * it; the export tile ([DEFAULT_SIZE]) and the
     * picker's previews are well above that.
     */
    internal fun markHeight(variant: Mark.Variant, size: Int): Float {
        val share = when (variant) {
            Mark.Variant.SYMBOL -> 0.08f
            Mark.Variant.STACKED -> 0.07f
            Mark.Variant.LOCKUP -> 0.04f
        }
        return maxOf(Mark.MIN_HEIGHT, size * share)
    }

    /** The mark's top-left: its right edge and bottom edge both one margin in from the tile's. */
    internal fun markOrigin(canvas: ArtCanvas, variant: Mark.Variant, size: Int): Pair<Float, Float> {
        val h = markHeight(variant, size)
        val w = Mark.width(canvas, variant, h)
        val m = margin(size)
        return (size - m - w) to (size - m - h)
    }

    /** A bank-A pad's cell in the Grid style, MPC layout: A01 bottom-left, A13 top-left. */
    internal fun gridCell(size: Int, slot: Int): Box {
        require(slot in 1..16) { "bank A has slots 1..16, got $slot" }
        val box = fieldBox(size)
        val side = minOf(box.w, box.h)
        val ox = box.x + (box.w - side) / 2
        val oy = box.y + (box.h - side) / 2
        val gap = maxOf(2, (side * 0.04f).toInt())
        val cell = (side - 3 * gap) / 4
        val row = 3 - (slot - 1) / 4
        val col = (slot - 1) % 4
        return Box(ox + col * (cell + gap), oy + row * (cell + gap), cell, cell)
    }

    /**
     * The box the name must stay inside; KitArtTest measures legibility over
     * it. A strip zone ends one margin before where the mark actually starts,
     * measured on [canvas], so every variant gets the same breathing room on
     * either platform's type; when that leaves less than half the tile, the
     * name moves to its own row above the mark rather than shrink to a smudge.
     */
    internal fun nameZone(canvas: ArtCanvas, style: Style, variant: Mark.Variant, size: Int): Box = when (style) {
        Style.BLOOM -> {
            val half = size * 0.40f * 0.42f / sqrt(2f)
            val c = size / 2f
            Box(Math.round(c - half), Math.round(c - half), Math.round(2 * half), Math.round(2 * half))
        }
        Style.RIDGES -> {
            val m = margin(size)
            Box(m, m, size - 2 * m, Math.round(size * 0.20f))
        }
        Style.FIELD -> {
            val m = margin(size)
            Box(m, Math.round(size * 0.42f), size - 2 * m, Math.round(size * 0.16f))
        }
        else -> {
            val m = margin(size)
            val markX = markOrigin(canvas, variant, size).first
            val beside = (markX - m).toInt() - m
            if (beside >= size / 2) {
                // The name shares the bottom row with the mark, ending a margin before it.
                Box(m, Math.round(size * 0.80f), beside, Math.round(size * 0.14f))
            } else {
                // A wide mark (the one-line lockup) would squeeze the name to a smudge: the name
                // takes its own full-width row above the mark's, between the field and the mark.
                Box(m, Math.round(size * 0.775f), size - 2 * m, Math.round(size * 0.115f))
            }
        }
    }

    // ---- the AWT adapter — java.awt lives here and in the font cache below, nowhere else ----

    /**
     * Public, not private: [JCard] reuses [waveform] against its own
     * [Graphics2D], and the CLI's `mark` verb draws [Mark] through it.
     */
    class AwtCanvas(private val g: Graphics2D) : ArtCanvas {
        override fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int) {
            g.color = Color(colorRgb)
            g.fillRect(x, y, w, h)
        }

        override fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int) {
            g.color = Color(colorRgb)
            g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, startDeg, sweepDeg, Arc2D.OPEN))
        }

        override fun fillPolygon(xs: FloatArray, ys: FloatArray, colorRgb: Int) {
            require(xs.size == ys.size && xs.size >= 3) { "a polygon needs matching xs/ys with at least three points" }
            val path = Path2D.Float()
            path.moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
            path.closePath()
            g.color = Color(colorRgb)
            g.fill(path)
        }

        override fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int) {
            val f = AwtFonts.font(font, sizePx)
            val w = g.getFontMetrics(f).stringWidth(text)
            val left = when (anchor) {
                TextAnchor.START -> x
                TextAnchor.MIDDLE -> x - w / 2f
                TextAnchor.END -> x - w
            }
            g.font = f
            g.color = Color(colorRgb)
            g.drawString(text, left, baselineY)
        }

        override fun textWidth(text: String, font: ArtFont, sizePx: Float): Float =
            g.getFontMetrics(AwtFonts.font(font, sizePx)).stringWidth(text).toFloat()

        override fun fillPath(path: ArtPath, fill: ArtFill) = painted(fill) { g.fill(awtPath(path)) }

        override fun strokePath(path: ArtPath, strokeWidth: Float, fill: ArtFill) = painted(fill) {
            g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(awtPath(path))
        }

        override fun fillCircle(cx: Float, cy: Float, r: Float, fill: ArtFill) = painted(fill) {
            g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        }

        /** Sets the paint and the alpha composite for one call and puts both back after. */
        private inline fun painted(fill: ArtFill, draw: () -> Unit) {
            val paintBefore = g.paint
            val compositeBefore = g.composite
            g.paint = when (fill) {
                is ArtFill.Solid -> Color(fill.rgb)
                is ArtFill.Linear -> GradientPaint(fill.x0, fill.y0, Color(fill.rgb0), fill.x1, fill.y1, Color(fill.rgb1))
                is ArtFill.Radial -> RadialGradientPaint(
                    fill.cx, fill.cy, fill.r,
                    floatArrayOf(0f, 1f), arrayOf(Color(fill.inner), Color(fill.outer)),
                )
            }
            if (fill.alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fill.alpha.coerceIn(0f, 1f))
            try {
                draw()
            } finally {
                g.paint = paintBefore
                g.composite = compositeBefore
            }
        }

        private fun awtPath(path: ArtPath): Path2D.Float {
            val p = Path2D.Float()
            for (s in path.segments) {
                when (s) {
                    is ArtPath.Segment.Move -> p.moveTo(s.x, s.y)
                    is ArtPath.Segment.Line -> p.lineTo(s.x, s.y)
                    is ArtPath.Segment.Quad -> p.quadTo(s.cx, s.cy, s.x, s.y)
                    ArtPath.Segment.Close -> p.closePath()
                }
            }
            return p
        }
    }

    // ---- styles ------------------------------------------------------------

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

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

    /** The original strip: every pad's min/max columns end to end. The J-Card's front still wears it. */
    internal fun waveformStrip(canvas: ArtCanvas, box: Box, kit: Kit, kitDir: File) {
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
}

/**
 * JVM-side font cache: one base [java.awt.Font] per [ArtFont], created
 * from the bundled bytes rather than the system font table so a headless
 * CI box and a desk with the family installed draw the same glyphs. Here
 * and not in `ArtFont.kt` because the phone loads that file's class and
 * AWT does not exist there; this file is already the AWT adapter.
 */
internal object AwtFonts {
    private val base = HashMap<ArtFont, Font>()

    fun font(font: ArtFont): Font = synchronized(base) {
        base.getOrPut(font) { Font.createFont(Font.TRUETYPE_FONT, font.bytes().inputStream()) }
    }

    fun font(font: ArtFont, sizePx: Float): Font = font(font).deriveFont(sizePx)
}
