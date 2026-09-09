package com.snipsnap.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.snipsnap.app.R
import com.snipsnap.shell.Personality
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes

/**
 * The TapeOS theme: a thin Compose binding over `:shell`'s [Schemes]
 * tables. The colours live in one tested place — this file only converts
 * packed 0xRRGGBB ints to Compose [Color]s and provides them down the
 * tree. The two-surface rule (gray chrome to work on, dark LCD where
 * sound lives) is enforced by `SchemesTest`; the app just draws it.
 */

/** A `:shell` packed 0xRRGGBB colour as an opaque Compose colour. */
val Int.tape: Color get() = Color((0xFF shl 24) or this)

/**
 * Destructive-action text, bound from `:shell`'s [Schemes.BIN_RED_GLOW] —
 * the single source of the value (tuned to clear WCAG AA against every
 * scheme's chrome gray; see that constant's KDoc and `ContrastTest`). Was
 * duplicated as a private `Color(0xFFC86050)` literal in six screen files;
 * every one of them now binds this token instead, so the colour — and any
 * future retune — lives in exactly one place.
 */
val BinRedGlow: Color get() = Schemes.BIN_RED_GLOW.tape

val LocalScheme = staticCompositionLocalOf { Schemes.DEFAULT }
val LocalPersonality = staticCompositionLocalOf { Personality.FULL }

@Composable
fun TapeTheme(
    scheme: Scheme,
    personality: Personality,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalScheme provides scheme,
        LocalPersonality provides personality,
        content = content,
    )
}

/**
 * The four TapeOS faces, committed under `res/font` (fetched from Google
 * Fonts; OFL/Apache families — see app/README.md). Resource fonts resolve
 * at compile time, so there is no runtime fallback machinery to get wrong.
 */
object TapeFonts {
    /** VT323 — everything on an LCD. */
    val Lcd = FontFamily(Font(R.font.vt323))

    /** Silkscreen — pixel UI chrome. */
    val Pixel = FontFamily(Font(R.font.silkscreen))

    /** Michroma — display headers and key actions. */
    val Display = FontFamily(Font(R.font.michroma))

    /**
     * Rock Salt — handwriting on pads, cassette labels and loop blocks.
     * Matches `Type.MARKER` in `:shell`, which is the design system's name
     * for this slot; keep the two in step, because Compose needs an
     * `R.font` resource and cannot read the constant.
     *
     * The prototype exposes this as a "Look" control with four options
     * (Permanent Marker, Gochi Hand, Caveat, Rock Salt) defaulting to Rock
     * Salt. Only the default is vendored; `permanent_marker.ttf` stays for
     * the day that control ships.
     */
    val Marker = FontFamily(Font(R.font.rock_salt))
}

/** Text styles per the handoff; sizes are dp-at-390 read as sp. */
object TapeType {
    fun lcd(size: Int): TextStyle = TextStyle(fontFamily = TapeFonts.Lcd, fontSize = size.sp)

    val lcdHeader = lcd(21)
    val lcdReadout = lcd(22)
    val lcdSmall = lcd(17)

    val pixel = TextStyle(fontFamily = TapeFonts.Pixel, fontSize = 9.sp, letterSpacing = 0.5.sp)
    val pixelSmall = TextStyle(fontFamily = TapeFonts.Pixel, fontSize = 8.sp, letterSpacing = 0.5.sp)

    val display = TextStyle(fontFamily = TapeFonts.Display, fontSize = 11.sp, letterSpacing = 2.sp)
    val displayBig = TextStyle(fontFamily = TapeFonts.Display, fontSize = 12.sp, letterSpacing = 2.sp)

    val marker = TextStyle(fontFamily = TapeFonts.Marker, fontSize = 13.sp)
    val markerBig = TextStyle(fontFamily = TapeFonts.Marker, fontSize = 15.sp)
}
