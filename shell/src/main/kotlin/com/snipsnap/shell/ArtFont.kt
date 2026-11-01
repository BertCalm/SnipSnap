package com.snipsnap.shell

/**
 * A typeface bundled with the app as a `:shell` resource, so the JVM
 * renderer and the phone read the very same file. Unbounded is set under
 * the SIL Open Font License 1.1; the licence text sits beside the TTF.
 *
 * Nothing here touches `java.awt`: the phone loads this file's class,
 * and AWT does not exist there. `:app` reads the same [resourcePath]
 * through its asset manager; the JVM loader, [AwtFonts], lives beside
 * the AWT adapter in `KitArt.kt`.
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
