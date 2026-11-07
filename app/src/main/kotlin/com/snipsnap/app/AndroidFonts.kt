package com.snipsnap.app

import android.content.res.AssetManager
import android.graphics.Typeface
import com.snipsnap.shell.ArtFont

/**
 * The phone's loader for the faces `:shell` bundles. The TTF is one
 * file in `shell/src/main/resources`; the app's Gradle file packs that
 * directory as assets, so [ArtFont.resourcePath] is also the asset
 * path. `Typeface` wants assets or a file, never a stream, which is
 * why the canvas takes an [AssetManager] rather than the bytes.
 */
object AndroidFonts {
    private val cache = HashMap<ArtFont, Typeface>()

    /** One [Typeface] per [ArtFont]; a missing asset fails here, naming the path, not as blank text later. */
    fun typeface(assets: AssetManager, font: ArtFont): Typeface = synchronized(cache) {
        cache.getOrPut(font) {
            runCatching { Typeface.createFromAsset(assets, font.resourcePath) }
                .getOrElse { throw IllegalStateException("font asset missing: ${font.resourcePath}", it) }
        }
    }
}
