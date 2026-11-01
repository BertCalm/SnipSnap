package com.snipsnap.shell

/**
 * Setting the kit name on a tile: shrink to the zone, track wide, measure.
 * Every call goes through [ArtCanvas.drawText]/[ArtCanvas.textWidth], so
 * the phone and the JVM set the same face at the same size, even if their
 * rasterisers differ by a pixel.
 */
internal object TileType {
    /** The smallest size any name is set at; below this nothing reads. */
    const val MIN_SIZE = 6f

    /** The largest size at most [maxSizePx] whose advance fits [maxWidthPx], never below [MIN_SIZE]. */
    fun fit(canvas: ArtCanvas, text: String, font: ArtFont, maxWidthPx: Float, maxSizePx: Float): Float {
        if (text.isEmpty()) return maxSizePx
        var size = maxSizePx
        while (size > MIN_SIZE && canvas.textWidth(text, font, size) > maxWidthPx) size -= 1f
        return size.coerceAtLeast(MIN_SIZE)
    }

    /**
     * The text and size that fit [maxWidthPx]: shrink from [maxSizePx] no
     * further than [floorShare] of it (type a tenth the size of its zone
     * reads as a smudge), then drop characters for an ellipsis until it
     * fits. A name never leaves its zone, whatever its length.
     */
    fun fitText(canvas: ArtCanvas, text: String, font: ArtFont, maxWidthPx: Float, maxSizePx: Float, floorShare: Float = 0.3f): Pair<String, Float> {
        val floor = maxOf(MIN_SIZE, maxSizePx * floorShare)
        var size = maxSizePx
        while (size > floor && canvas.textWidth(text, font, size) > maxWidthPx) size -= 1f
        if (canvas.textWidth(text, font, size) <= maxWidthPx) return text to size
        var kept = text.trimEnd()
        while (kept.isNotEmpty() && canvas.textWidth("$kept…", font, size) > maxWidthPx) kept = kept.dropLast(1).trimEnd()
        return "$kept…" to size
    }

    /** [fit] for [tracked] text: the tracking rides along with the size. */
    fun fitTracked(canvas: ArtCanvas, text: String, font: ArtFont, maxWidthPx: Float, maxSizePx: Float, trackingEm: Float): Float {
        if (text.isEmpty()) return maxSizePx
        var size = maxSizePx
        while (size > MIN_SIZE && trackedWidth(canvas, text, font, size, trackingEm) > maxWidthPx) size -= 1f
        return size.coerceAtLeast(MIN_SIZE)
    }

    /** [ArtCanvas.drawText] glyph by glyph with [trackingEm] extra advance after each; START anchored at [x]. */
    fun tracked(canvas: ArtCanvas, text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, trackingEm: Float, colorRgb: Int) {
        var at = x
        for (ch in text) {
            val s = ch.toString()
            canvas.drawText(s, font, sizePx, at, baselineY, TextAnchor.START, colorRgb)
            at += canvas.textWidth(s, font, sizePx) + trackingEm * sizePx
        }
    }

    /** The advance [tracked] covers: every glyph plus the tracking between them. */
    fun trackedWidth(canvas: ArtCanvas, text: String, font: ArtFont, sizePx: Float, trackingEm: Float): Float {
        if (text.isEmpty()) return 0f
        var w = 0f
        for (ch in text) w += canvas.textWidth(ch.toString(), font, sizePx)
        return w + trackingEm * sizePx * (text.length - 1)
    }
}
