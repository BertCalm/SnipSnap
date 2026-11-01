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

    /**
     * Bloom: the seed's loudness envelope wrapped round a circle, mirrored
     * so it reads as a flower rather than a clock, filled with a radial
     * two-tone gradient, a hole of ground in the middle where the name
     * sits in condensed caps.
     */
    fun bloom(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.40f
        val env = seed?.envelope(180) ?: FloatArray(180)
        val n = 360
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = (i.toDouble() / n) * 2 * Math.PI - Math.PI / 2
            val e = env[minOf(i, n - 1 - i).coerceIn(0, 179)]
            val rr = r * (0.45f + 0.55f * e)
            xs[i] = cx + (rr * cos(a)).toFloat()
            ys[i] = cy + (rr * sin(a)).toFloat()
        }
        val petal = ArtPath.polygon(xs, ys)
        canvas.fillPath(petal, ArtFill.Radial(cx, cy, r, palette.field1, palette.field2))
        canvas.strokePath(petal, size * 0.008f, ArtFill.Solid(palette.ink, 0.35f))
        canvas.fillCircle(cx, cy, r * 0.42f, ArtFill.Solid(palette.ground))

        val (text, fitted) = TileType.fitText(canvas, label.uppercase(), ArtFont.OSWALD_BOLD, zone.w.toFloat(), zone.h * 0.6f)
        canvas.drawText(text, ArtFont.OSWALD_BOLD, fitted, cx, cy + fitted * 0.36f, TextAnchor.MIDDLE, palette.ink)
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
