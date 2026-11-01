package com.snipsnap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.ArtFont
import com.snipsnap.shell.Mark
import com.snipsnap.shell.TextAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's ArtCanvas against real android.graphics: the font asset, the polygon and the type. */
@RunWith(AndroidJUnit4::class)
class AndroidCanvasTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun rgb(bitmap: Bitmap, x: Int, y: Int) = bitmap.getPixel(x, y) and 0xFFFFFF

    private fun has(bitmap: Bitmap, rgb: Int): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (rgb(bitmap, x, y) == rgb) return true
        return false
    }

    @Test
    fun theFontAssetIsPacked() {
        assets.open(ArtFont.UNBOUNDED_EXTRABOLD.resourcePath).use { assertTrue(it.read() >= 0) }
        assertTrue(AndroidFonts.typeface(assets, ArtFont.UNBOUNDED_EXTRABOLD).isBold)
    }

    @Test
    fun polygonAndTextPaint() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = AndroidKitArt.AndroidCanvas(Canvas(bitmap), assets)
        canvas.fillRect(0, 0, 200, 200, 0x000000)
        canvas.fillPolygon(floatArrayOf(100f, 140f, 100f, 60f), floatArrayOf(10f, 50f, 90f, 50f), 0xFF0000)
        canvas.drawText("SNAP", ArtFont.UNBOUNDED_EXTRABOLD, 40f, 100f, 150f, TextAnchor.MIDDLE, 0xFFFFFF)

        assertEquals(0xFF0000, rgb(bitmap, 100, 50))
        assertEquals(0x000000, rgb(bitmap, 5, 5))
        var whiteLeft = false
        var whiteRight = false
        for (y in 110 until 150) for (x in 0 until 200) {
            if (rgb(bitmap, x, y) == 0xFFFFFF) { if (x < 100) whiteLeft = true else whiteRight = true }
        }
        assertTrue("the word straddles its MIDDLE anchor", whiteLeft && whiteRight)
        assertTrue(canvas.textWidth("SNAP", ArtFont.UNBOUNDED_EXTRABOLD, 40f) > 60f)
        bitmap.recycle()
    }

    @Test
    fun markRendersOnThePhone() {
        val bitmap = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        val canvas = AndroidKitArt.AndroidCanvas(Canvas(bitmap), assets)
        canvas.fillRect(0, 0, 400, 100, 0x101010)
        Mark.draw(canvas, Mark.Variant.STACKED, 10f, 10f, 80f, ink = 0xF2F2F2, accent = 0xFF7A3D)
        assertTrue("ink present", has(bitmap, 0xF2F2F2))
        assertTrue("accent present", has(bitmap, 0xFF7A3D))
        bitmap.recycle()
    }
}
