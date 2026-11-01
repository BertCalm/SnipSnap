package com.snipsnap.shell

import java.awt.Font

/**
 * A typeface bundled with the app as a `:shell` resource, so the JVM
 * renderer and the phone read the very same file. Unbounded is set under
 * the SIL Open Font License 1.1; the licence text sits beside the TTF.
 *
 * Nothing here touches `java.awt`: `:app` loads the same [resourcePath]
 * through its asset manager, and [AwtFonts] is the JVM-only loader.
 */
enum class ArtFont(val resourcePath: String) {
    UNBOUNDED_EXTRABOLD("fonts/Unbounded-ExtraBold.ttf");

    /** The TTF bytes, read through the class loader; fails loudly, naming the path, when the resource is missing. */
    fun bytes(): ByteArray {
        val stream = ArtFont::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("font resource missing: $resourcePath")
        return stream.use { it.readBytes() }
    }
}

/** Where text hangs from its x: the left edge, the centre, or the right edge. */
enum class TextAnchor { START, MIDDLE, END }

/**
 * JVM-side font cache: one base [Font] per [ArtFont], created from the
 * bundled bytes rather than the system font table so a headless CI box
 * and a desk with the family installed draw the same glyphs.
 */
internal object AwtFonts {
    private val base = HashMap<ArtFont, Font>()

    fun font(font: ArtFont): Font = synchronized(base) {
        base.getOrPut(font) { Font.createFont(Font.TRUETYPE_FONT, font.bytes().inputStream()) }
    }

    fun font(font: ArtFont, sizePx: Float): Font = font(font).deriveFont(sizePx)
}
