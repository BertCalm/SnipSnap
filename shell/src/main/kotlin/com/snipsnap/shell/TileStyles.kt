package com.snipsnap.shell

import kotlin.math.cos
import kotlin.math.sin

/**
 * The tile styles, one function each, all with the same shape: paint the
 * field for [KitArt.draw] inside a [size]-square tile whose ground is
 * already down, then set [label] inside [zone]. Nothing here knows which
 * platform is painting; everything goes through [ArtCanvas].
 */
internal object TileStyles {

    /** How many petals the envelope is wrapped into; a one-shot's decay becomes one petal's falling edge. */
    const val BLOOM_PETALS = 6

    /**
     * Bloom: the seed's loudness envelope wrapped round a circle as
     * [BLOOM_PETALS] petals. Each petal is a cosine body (so it is always a
     * rounded form, never a spike) whose reach is the envelope in decibels,
     * loudest bin at the petal's centre, quietest at its edges: a sustained
     * pad fills its petals out, a drum keeps them slender. Filled with a
     * radial two-tone gradient, a hole of ground in the middle where the
     * name sits in condensed caps, on two lines when it has a break.
     */
    fun bloom(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.40f
        val bins = 90
        val env = seed?.envelope(bins) ?: FloatArray(bins)
        val n = 360
        val perPetal = n / BLOOM_PETALS
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = (i.toDouble() / n) * 2 * Math.PI - Math.PI / 2
            // d: 0 at the petal's centre, 1 at its edges.
            val k = i % perPetal
            val d = kotlin.math.abs(k - perPetal / 2f) / (perPetal / 2f)
            val body = 0.5f + 0.5f * cos(Math.PI * d).toFloat()
            // The envelope in decibels over a 40 dB window, 1 at the loudest bin, 0 at -40 dB and below;
            // read at d squared so the hit's loud bins fill the petal's middle rather than one spike, and
            // averaged over three bins so a single peak bin cannot poke through the body.
            val at = (d * d * (bins - 1)).toInt().coerceIn(0, bins - 1)
            val env0 = (env[maxOf(0, at - 1)] + env[at] + env[minOf(bins - 1, at + 1)]) / 3f
            val db = 20.0 * Math.log10(maxOf(env0, 1e-4f).toDouble())
            val reach = (1.0 + db.coerceIn(-40.0, 0.0) / 40.0).toFloat()
            val rr = r * (0.50f + 0.50f * body * (0.45f + 0.55f * reach))
            xs[i] = cx + (rr * cos(a)).toFloat()
            ys[i] = cy + (rr * sin(a)).toFloat()
        }
        val petal = ArtPath.polygon(xs, ys)
        canvas.fillPath(petal, ArtFill.Radial(cx, cy, r, palette.field1, palette.field2))
        canvas.strokePath(petal, size * 0.008f, ArtFill.Solid(palette.ink, 0.35f))
        canvas.fillCircle(cx, cy, r * 0.42f, ArtFill.Solid(palette.ground))

        holeName(canvas, palette, label, zone, cx, cy)
    }

    /**
     * The name in Bloom's hole: one line when it fits at a readable size,
     * else two lines split at the space, underscore or hyphen nearest the middle, each fitted to
     * the zone's width and the pair to its height.
     */
    private fun holeName(canvas: ArtCanvas, palette: Palette, label: String, zone: KitArt.Box, cx: Float, cy: Float) {
        val text = label.uppercase()
        val font = ArtFont.OSWALD_BOLD
        val oneLineMax = zone.h * 0.6f
        val single = TileType.fitText(canvas, text, font, zone.w.toFloat(), oneLineMax)
        val split = text.indices.filter { text[it] in " _-" }.minByOrNull { kotlin.math.abs(it - text.length / 2) }
        if (split == null || (single.first == text && single.second >= oneLineMax * 0.6f)) {
            canvas.drawText(single.first, font, single.second, cx, cy + single.second * 0.36f, TextAnchor.MIDDLE, palette.ink)
            return
        }
        val lineMax = zone.h * 0.38f
        val top = text.substring(0, split).trimEnd()
        val bottom = text.substring(split + 1).trimStart()
        val size = minOf(
            TileType.fitText(canvas, top, font, zone.w.toFloat(), lineMax).second,
            TileType.fitText(canvas, bottom, font, zone.w.toFloat(), lineMax).second,
        )
        val topText = TileType.fitText(canvas, top, font, zone.w.toFloat(), size).first
        val bottomText = TileType.fitText(canvas, bottom, font, zone.w.toFloat(), size).first
        canvas.drawText(topText, font, size, cx, cy - size * 0.18f, TextAnchor.MIDDLE, palette.ink)
        canvas.drawText(bottomText, font, size, cx, cy + size * 0.92f, TextAnchor.MIDDLE, palette.ink)
    }

    /** The name in the bottom strip: upper case, START at the zone's left, shrunk to the zone. */
    fun stripName(canvas: ArtCanvas, palette: Palette, label: String, zone: KitArt.Box, font: ArtFont) {
        val (text, fitted) = TileType.fitText(canvas, label.uppercase(), font, zone.w.toFloat(), zone.h * 0.6f)
        canvas.drawText(text, font, fitted, zone.x.toFloat(), zone.y + zone.h * 0.72f, TextAnchor.START, palette.ink)
    }

    // ---- colour helpers (packed 0xRRGGBB in, same out) ----------------------

    fun lighten(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r + (255 - r) * k).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * k).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * k).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    fun darken(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r * (1 - k)).toInt().coerceIn(0, 255)
        val ng = (g * (1 - k)).toInt().coerceIn(0, 255)
        val nb = (b * (1 - k)).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    /** [a] at t = 0 running to [b] at t = 1. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
