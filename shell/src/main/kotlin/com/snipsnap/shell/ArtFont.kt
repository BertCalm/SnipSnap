package com.snipsnap.shell

/**
 * A typeface bundled with the app as a `:shell` resource, so the JVM
 * renderer and the phone read the very same file. Every face is set under
 * the SIL Open Font License 1.1; each family's licence text sits beside
 * its TTF as `<Family>-OFL.txt`.
 *
 * Nothing here touches `java.awt`: the phone loads this file's class,
 * and AWT does not exist there. `:app` reads the same [resourcePath]
 * through its asset manager; the JVM loader, [AwtFonts], lives beside
 * the AWT adapter in `KitArt.kt`.
 */
enum class ArtFont(val resourcePath: String) {
    /** The mark's face, and Field's wide geometric caption. */
    UNBOUNDED_EXTRABOLD("fonts/Unbounded-ExtraBold.ttf"),
    /** Condensed caps: Bloom's name in the hole, Grid's caption. */
    OSWALD_BOLD("fonts/Oswald-Bold.ttf"),
    /** The italic serif Ridges sets its name in. */
    PLAYFAIR_BOLD_ITALIC("fonts/PlayfairDisplay-BoldItalic.ttf"),
    /** The grotesk the ribbon and the strip captions use. */
    SPACE_GROTESK_BOLD("fonts/SpaceGrotesk-Bold.ttf");

    /** The TTF bytes, read through the class loader; fails loudly, naming the path, when the resource is missing. */
    fun bytes(): ByteArray {
        val stream = ArtFont::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("font resource missing: $resourcePath")
        return stream.use { it.readBytes() }
    }
}

/** Where text hangs from its x: the left edge, the centre, or the right edge. */
enum class TextAnchor { START, MIDDLE, END }
