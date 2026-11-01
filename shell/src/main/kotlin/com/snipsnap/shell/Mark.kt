package com.snipsnap.shell

import kotlin.math.max

/**
 * The SnipSnap mark, drawn once here for every surface that wears it:
 * the launcher icon, the title bar, the J-Card spine and the expansion
 * tiles. Geometry is the owner's, settled on screen mockups:
 *
 * - **Symbol** — four pads (squares on point) in a 2×2, touching at the
 *   corners so the cluster reads as an x. Top-left and bottom-right
 *   take the accent, the other pair the ink. Each pad is filled from
 *   its four points on a grid of the pad's own diagonal, never as a
 *   rotated square: rotated squares spaced by their side overlapped.
 * - **Lockup** — SNIP · symbol · SNAP on one line, Unbounded ExtraBold,
 *   SNIP in ink, SNAP in accent, the symbol exactly cap height.
 * - **Stacked** — SNIP over SNAP, flush left with the face's natural
 *   spacing, the symbol to the right as tall as SNIP's cap top to
 *   SNAP's baseline, a small fixed gap after the wider word.
 *
 * Every variant sits in a box whose top-left is ([x],[y]) and whose
 * height is the mark height; the width comes from [width] because the
 * words are measured on the canvas that will set them.
 */
object Mark {

    enum class Variant(val id: String) {
        SYMBOL("symbol"),
        LOCKUP("lockup"),
        STACKED("stacked");

        companion object {
            fun byId(id: String): Variant? = entries.firstOrNull { it.id == id }
        }
    }

    /** One pad of the symbol: its centre and half-diagonal. */
    data class Diamond(val cx: Float, val cy: Float, val r: Float)

    /** Below this the pads collapse into noise; [draw] refuses. */
    const val MIN_HEIGHT = 8f

    /** Unbounded ExtraBold's cap height as a fraction of the em; the lockups size type so caps match the mark height. */
    const val CAP_RATIO = 0.72f

    /** Gap between the wider word and the symbol in STACKED, as a fraction of height. */
    const val STACKED_GAP = 0.14f

    /** Gap each side of the symbol in LOCKUP, as a fraction of height. */
    const val LOCKUP_GAP = 0.25f

    /** Air between SNIP's baseline and SNAP's cap top in STACKED, as a fraction of height. */
    private const val STACKED_LINE_GAP = 0.12f

    private const val SNIP = "SNIP"
    private const val SNAP = "SNAP"
    private val FACE = ArtFont.UNBOUNDED_EXTRABOLD

    /**
     * The four pads of a symbol whose bounding box is [height] square with
     * its top-left at ([x],[y]); order TL, TR, BL, BR. Pads touch, so each
     * half-diagonal is a quarter of the height.
     */
    fun symbol(x: Float, y: Float, height: Float): List<Diamond> {
        val r = height / 4f
        return listOf(
            Diamond(x + r, y + r, r),
            Diamond(x + 3 * r, y + r, r),
            Diamond(x + r, y + 3 * r, r),
            Diamond(x + 3 * r, y + 3 * r, r),
        )
    }

    /** The mark's width at [height] on [canvas], where its words are measured. */
    fun width(canvas: ArtCanvas, variant: Variant, height: Float): Float = when (variant) {
        Variant.SYMBOL -> height
        Variant.LOCKUP -> {
            val size = height / CAP_RATIO
            canvas.textWidth(SNIP, FACE, size) + 2 * LOCKUP_GAP * height + height + canvas.textWidth(SNAP, FACE, size)
        }
        Variant.STACKED -> {
            val size = stackedTypeSize(height)
            max(canvas.textWidth(SNIP, FACE, size), canvas.textWidth(SNAP, FACE, size)) + STACKED_GAP * height + height
        }
    }

    /**
     * Draw [variant] with its box's top-left at ([x],[y]) and the box
     * [height] tall; the box is [width] wide. Requires [height] >= [MIN_HEIGHT].
     */
    fun draw(canvas: ArtCanvas, variant: Variant, x: Float, y: Float, height: Float, ink: Int, accent: Int) {
        require(height >= MIN_HEIGHT) { "the mark needs at least $MIN_HEIGHT px of height, got $height" }
        when (variant) {
            Variant.SYMBOL -> pads(canvas, x, y, height, ink, accent)
            Variant.LOCKUP -> {
                val size = height / CAP_RATIO
                val baseline = y + height
                val wSnip = canvas.textWidth(SNIP, FACE, size)
                canvas.drawText(SNIP, FACE, size, x, baseline, TextAnchor.START, ink)
                val symbolX = x + wSnip + LOCKUP_GAP * height
                pads(canvas, symbolX, y, height, ink, accent)
                canvas.drawText(SNAP, FACE, size, symbolX + height + LOCKUP_GAP * height, baseline, TextAnchor.START, accent)
            }
            Variant.STACKED -> {
                val size = stackedTypeSize(height)
                val cap = size * CAP_RATIO
                val wSnip = canvas.textWidth(SNIP, FACE, size)
                val wSnap = canvas.textWidth(SNAP, FACE, size)
                canvas.drawText(SNIP, FACE, size, x, y + cap, TextAnchor.START, ink)
                canvas.drawText(SNAP, FACE, size, x, y + height, TextAnchor.START, accent)
                pads(canvas, x + max(wSnip, wSnap) + STACKED_GAP * height, y, height, ink, accent)
            }
        }
    }

    /** [ink]/[accent] for a scheme, with the accent replaced by `scheme.amber` when it equals the ink, so the x keeps two tones. */
    fun tones(scheme: Scheme): Pair<Int, Int> =
        scheme.ink to if (scheme.accent == scheme.ink) scheme.amber else scheme.accent

    /** Two lines of caps with a gap between them fill the box exactly. */
    private fun stackedTypeSize(height: Float): Float =
        (height - STACKED_LINE_GAP * height) / 2f / CAP_RATIO

    private fun pads(canvas: ArtCanvas, x: Float, y: Float, height: Float, ink: Int, accent: Int) {
        val tones = intArrayOf(accent, ink, ink, accent)
        symbol(x, y, height).forEachIndexed { i, d ->
            canvas.fillPolygon(
                floatArrayOf(d.cx, d.cx + d.r, d.cx, d.cx - d.r),
                floatArrayOf(d.cy - d.r, d.cy, d.cy + d.r, d.cy),
                tones[i],
            )
        }
    }
}
