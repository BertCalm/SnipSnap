package com.snipsnap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.snipsnap.kit.Kit
import com.snipsnap.shell.ArtCanvas
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Android half of [KitArt.draw] — the browser-tile cover art
 * (WAVEFORM/GRID/SLICES/RINGS, `:shell`'s [ArtCanvas] contract) painted
 * with `android.graphics` instead of `java.awt`/`javax.imageio`, neither
 * of which exists on Android at any API level. [KitArt.render]/
 * [KitArt.png] stay exactly as they were — AWT, used by `:cli` and the
 * `:shell` tests — this is a second, independent renderer for the same
 * [KitArt.draw] logic, wired in by `ExportWizardModel.artRenderer` so the
 * shipped app never touches AWT. See `KitArt.kt`'s own class doc for the
 * full story of why the split exists.
 *
 * No cross-platform pixel parity is attempted or required — RINGS'
 * seeded arc placement only has to be deterministic against its own
 * inputs on this platform, not match the AWT renderer's rotation sense.
 */
object AndroidKitArt {

    /**
     * Renders [kit]'s cover art and returns PNG bytes — the same contract
     * [KitArt.png] has, so this drops straight into
     * `ExportWizardModel.artRenderer: (Kit, File, KitArt.Style) -> ByteArray`.
     */
    fun png(
        kit: Kit,
        kitDir: File,
        style: KitArt.Style,
        scheme: Scheme = Schemes.DEFAULT,
        seed: Int = 0,
        size: Int = KitArt.DEFAULT_SIZE,
        label: String = kit.name,
    ): ByteArray {
        // ARGB_8888: KitArt.draw's own first call is a full-canvas
        // fillRect (the LCD background), so the bitmap starts fully
        // opaque regardless of config - ARGB_8888 is just the config
        // every other bitmap path in :app already uses.
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            KitArt.draw(AndroidCanvas(canvas), kit, kitDir, style, scheme, seed, size, label)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [ArtCanvas] against a live [android.graphics.Canvas]. `colorRgb` is
     * packed `0xRRGGBB` with no alpha channel — Android's `Color` ints are
     * ARGB, so every fill/stroke ORs in `0xFF000000` to force full
     * opacity; without it every draw would be fully transparent (alpha 0)
     * and the tile would come out blank.
     */
    private class AndroidCanvas(private val canvas: Canvas) : ArtCanvas {
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }
        private val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        override fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int) {
            fillPaint.color = (0xFF000000.toInt()) or colorRgb
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillPaint)
        }

        override fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int) {
            strokePaint.color = (0xFF000000.toInt()) or colorRgb
            strokePaint.strokeWidth = strokeWidth
            // Android's drawArc: 0deg at 3 o'clock, sweeping clockwise for
            // positive sweepDeg - the same convention KitArt.draw's callers
            // assume. No attempt is made to match AWT's Arc2D rotation
            // sense (Java2D's Y-down space flips it); this renderer's own
            // output only needs to be deterministic against its own
            // inputs, not pixel-identical to the AWT one - see this file's
            // class doc.
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), startDeg, sweepDeg, false, strokePaint)
        }
    }
}
