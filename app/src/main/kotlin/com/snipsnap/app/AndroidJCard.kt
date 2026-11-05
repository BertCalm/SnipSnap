package com.snipsnap.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.snipsnap.kit.Kit
import com.snipsnap.shell.JCard
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Android half of [JCard.draw] — the cassette insert painted with
 * `android.graphics` instead of `java.awt`/`javax.imageio`, neither of
 * which exists on Android at any API level. [JCard.render]/[JCard.png]
 * stay exactly as they were (AWT, for `:cli` and the `:shell` tests);
 * this is the second renderer for the same [JCard.draw] layout, handed
 * to `ExportWizardModel.jCardRenderer` / `Inserts.write` so WRITE KIT on
 * the phone never reaches AWT. Same pattern as [AndroidKitArt], and it
 * reuses that file's [AndroidKitArt.AndroidCanvas].
 *
 * No cross-platform pixel parity is claimed, though the card is all
 * axis-aligned rectangles, so in practice the two agree.
 */
object AndroidJCard {

    /** Renders [kit]'s J-card and returns PNG bytes — [JCard.png]'s contract. [assets] carries the bundled faces. */
    fun png(
        kit: Kit,
        kitDir: File,
        scheme: Scheme = Schemes.DEFAULT,
        width: Int = JCard.DEFAULT_WIDTH,
        catalog: String? = null,
        assets: AssetManager,
    ): ByteArray {
        // JCard.draw's first call is a full-canvas fillRect (the LCD), so
        // the bitmap ends fully opaque whatever the config.
        val bitmap = Bitmap.createBitmap(width, JCard.heightFor(width), Bitmap.Config.ARGB_8888)
        try {
            JCard.draw(AndroidKitArt.AndroidCanvas(Canvas(bitmap), assets), kit, kitDir, scheme, width, catalog)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
