package com.snipsnap.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
 * Destructive-action text on chrome, bound from the scheme's own `bin` —
 * [Schemes.BIN_RED_GLOW] for every dark scheme (tuned to clear WCAG AA
 * against every dark chrome gray; see that constant's KDoc and
 * `ContrastTest`), the page's own error red for a paper one, where the
 * glow is 2.5 : 1. Was duplicated as a private `Color(0xFFC86050)` literal
 * in six screen files; every one of them binds this instead, so the
 * colour — and any retune — lives in exactly one place. A composable read
 * because the scheme is the composition's.
 */
val BinRedGlow: Color @Composable get() = LocalScheme.current.bin.tape

/** The same word on the LCD — the dock's STOP — from the scheme's `binLcd`. */
val BinRedOnLcd: Color @Composable get() = LocalScheme.current.binLcd.tape

/**
 * The LCD's second tier of text: [Scheme.lcdInk] at [Schemes.LCD_INK2_ALPHA].
 * `ink`, `ink2` and `ink3` are chrome inks - tuned against `gray`, and on a
 * paper scheme (where the LCD is the dark surface) near-invisible on it - so
 * a secondary line inside an LCD reads this, and emphasis there reads `amber`.
 * Not a composable read, so the canvases that draw their own labels can use
 * it too. `ContrastTest` holds it at AA over the LCD in every scheme.
 */
val Scheme.lcdInk2: Color get() = lcdInk.tape.copy(alpha = Schemes.LCD_INK2_ALPHA)

val LocalScheme = staticCompositionLocalOf { Schemes.DEFAULT }

/**
 * The window's theme: the scheme, and any further locals the root wants
 * every screen to read ([also] - App passes the motor, `LocalHaptics`, so
 * no pad grid or stepper needs it as a parameter).
 */
@Composable
fun TapeTheme(
    scheme: Scheme,
    vararg also: ProvidedValue<*>,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalScheme provides scheme,
        *also,
        content = content,
    )
}

/**
 * The type scale (clarity spec section 7.5), defined once here. Sizes are
 * dp-at-390 read as sp; the floor is [MIN_SP] and a law
 * (`ConventionTest`) holds both the floor and this table.
 *
 * | Style | Size |
 * |---|---|
 * | [body] | 14 sp |
 * | [secondary] | 13 sp |
 * | [label] (buttons and labels) | 13 sp, +0.5 tracking, caps as written |
 * | [eyebrow] (section titles) | 11 sp, caps as written |
 * | [screenTitle] | 20 sp |
 * | readouts | mono, [lcd] above 17 |
 *
 * The faces are the one look's (`docs/MODERN_LOOK.md` section 5 step 3):
 * the system faces, serif italic for the words a person wrote, which is the
 * rule that keeps the metaphor honest. Each style is a composable read, as
 * it was when the scheme chose between two sets of faces: the call sites
 * read `TapeType.pixel` and friends inside composition, and a read outside
 * composition (a `remember`, a draw lambda) fails to compile and names
 * itself, which is the point.
 *
 * The old names stay and now mean a rung of the scale: `pixel` is [label],
 * `pixelSmall` is [secondary], `lcdSmall` and `lcd(17)` are [body],
 * `lcdHeader` is [screenTitle].
 */
object TapeType {
    /** The smallest size anywhere in the app, sp. */
    const val MIN_SP = 11
    const val BODY_SP = 14
    const val SECONDARY_SP = 13
    const val LABEL_SP = 13
    const val EYEBROW_SP = 11
    const val TITLE_SP = 20

    @Composable
    fun lcd(size: Int): TextStyle =
        // Readouts in mono; the LCD's small body in the sans, at the body size.
        if (size <= 17) BODY else TextStyle(fontFamily = FontFamily.Monospace, fontSize = (size - 2).coerceAtLeast(BODY_SP).sp)

    /** Body text: a proportional sans, 14 sp. */
    val body: TextStyle
        @Composable get() = BODY

    /** Legends, subtitles, the lines under things: the sans, 13 sp. */
    val secondary: TextStyle
        @Composable get() = SECONDARY

    /** Buttons and labels: mono, 13 sp, +0.5 tracking, caps as written. */
    val label: TextStyle
        @Composable get() = LABEL

    /** Section titles: mono, 11 sp, caps as written, +1 tracking. */
    val eyebrow: TextStyle
        @Composable get() = EYEBROW

    /** A screen's title: a serif italic, the handwriting's twin, 20 sp. */
    val screenTitle: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = TITLE_SP.sp)

    /** A header on the LCD: [screenTitle]. */
    val lcdHeader: TextStyle
        @Composable get() = screenTitle
    val lcdReadout: TextStyle
        @Composable get() = lcd(22)
    val lcdSmall: TextStyle
        @Composable get() = lcd(17)

    /** Chrome labels and buttons: [label]. */
    val pixel: TextStyle
        @Composable get() = LABEL

    /** The smaller text at 500-odd sites: [secondary]. */
    val pixelSmall: TextStyle
        @Composable get() = SECONDARY

    val display: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp)
    val displayBig: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 1.sp)

    /** What a person named: the serif italic - and nothing else in italic. */
    val marker: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp)
    val markerBig: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp)

    private val BODY = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = BODY_SP.sp)
    private val SECONDARY = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = SECONDARY_SP.sp)
    private val LABEL = TextStyle(fontFamily = FontFamily.Monospace, fontSize = LABEL_SP.sp, letterSpacing = 0.5.sp)
    private val EYEBROW = TextStyle(fontFamily = FontFamily.Monospace, fontSize = EYEBROW_SP.sp, letterSpacing = 1.sp)
}
