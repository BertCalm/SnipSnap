package com.snipsnap.synth

import kotlin.math.abs

/**
 * A picture as SNAP sees it: packed ARGB pixels, row-major, top row first —
 * exactly the array Android's `Bitmap.getPixels` hands back, so the app
 * layer copies one `IntArray` across and this module never learns what a
 * Bitmap is. No Android type reaches `:synth`, same as every other engine.
 *
 * A photo is already mathematical data: three numbers per pixel, 0..255.
 * SNAP only has to decide which of those numbers to read, and in what
 * order — see [Snap.table].
 */
class Photo(val width: Int, val height: Int, val argb: IntArray) {

    init {
        require(width > 0 && height > 0) { "a photo needs a width and a height: ${width}x$height" }
        // Long arithmetic: 65536 x 65536 wraps to zero in Int and would
        // let an empty array through to an index error later.
        require(argb.size.toLong() == width.toLong() * height.toLong()) {
            "pixel count ${argb.size} is not ${width}x$height"
        }
    }

    fun pixel(x: Int, y: Int): Int = argb[y * width + x]

    /** The [w] x [h] patch whose top-left corner is ([x0], [y0]), as a photo of its own. */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): Photo {
        require(x0 >= 0 && y0 >= 0 && w > 0 && h > 0 && x0 + w <= width && y0 + h <= height) {
            "crop ${w}x$h at ($x0, $y0) is outside ${width}x$height"
        }
        val px = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(argb, (y0 + y) * width + x0, px, y * w, w)
        return Photo(w, h, px)
    }

    /** Rec. 601 luma of the pixel at ([x], [y]), 0..1 — how bright it looks, not how much light it carries. */
    fun luminance(x: Int, y: Int): Float = luminance(pixel(x, y))

    companion object {
        fun red(argb: Int): Int = (argb shr 16) and 0xFF
        fun green(argb: Int): Int = (argb shr 8) and 0xFF
        fun blue(argb: Int): Int = argb and 0xFF

        /** Rec. 601 luma, 0..1: green weighs most because eyes do. */
        fun luminance(argb: Int): Float =
            (0.299f * red(argb) + 0.587f * green(argb) + 0.114f * blue(argb)) / 255f

        /**
         * Build a photo from a function of position — a test's ramp or
         * stripe pattern, or a preview drawn from numbers. [rgb] returns
         * a packed 0xRRGGBB; the alpha byte is set opaque here.
         */
        fun of(width: Int, height: Int, rgb: (x: Int, y: Int) -> Int): Photo {
            val px = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                px[y * width + x] = (0xFF shl 24) or (rgb(x, y) and 0xFFFFFF)
            }
            return Photo(width, height, px)
        }

        /** A grey photo from a brightness function, 0..1. */
        fun grey(width: Int, height: Int, lum: (x: Int, y: Int) -> Float): Photo = of(width, height) { x, y ->
            val v = Math.round(lum(x, y).coerceIn(0f, 1f) * 255f)
            (v shl 16) or (v shl 8) or v
        }

        /** Pack a colour, each channel 0..255. */
        fun rgb(r: Int, g: Int, b: Int): Int =
            (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** A pure hue, [degrees] round the colour wheel, at full saturation and brightness, packed as [rgb]. */
        fun hue(degrees: Float): Int {
            val h = (((degrees % 360f) + 360f) % 360f) / 60f
            val x = 1f - abs(h % 2f - 1f)
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(1f, x, 0f)
                1 -> Triple(x, 1f, 0f)
                2 -> Triple(0f, 1f, x)
                3 -> Triple(0f, x, 1f)
                4 -> Triple(x, 0f, 1f)
                else -> Triple(1f, 0f, x)
            }
            return rgb(Math.round(r * 255f), Math.round(g * 255f), Math.round(b * 255f))
        }

        /**
         * [color] shaded to exactly [brightness] (0..1, on [luminance]'s own
         * Rec. 601 scale): darkened toward black below the colour's own
         * brightness, washed toward white above it. Luma is a straight sum
         * of the channels, so both directions land on the asked-for
         * brightness to within a channel's rounding — which is what lets a
         * picture carry colour without changing what a reader of
         * brightness alone sees.
         */
        fun tint(color: Int, brightness: Float): Int {
            val target = brightness.coerceIn(0f, 1f)
            val own = luminance(color)
            val r = red(color) / 255f
            val g = green(color) / 255f
            val b = blue(color) / 255f
            fun shade(c: Float): Int = Math.round(
                255f * if (target <= own) c * (target / own) else c + (1f - c) * ((target - own) / (1f - own)),
            )
            if (own <= 0f) return rgb(Math.round(target * 255f), Math.round(target * 255f), Math.round(target * 255f))
            return rgb(shade(r), shade(g), shade(b))
        }
    }
}
