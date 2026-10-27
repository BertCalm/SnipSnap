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
 * Text styles per the handoff; sizes are dp-at-390 read as sp.
 *
 * The one look's faces (`docs/MODERN_LOOK.md` §5 step 3): the system
 * faces at the floor `docs/DIRECTION_2026_10.md` §5 asks for - 12 sp for
 * anything read as text, 11 sp mono for labels and eyebrows, serif italic
 * for the words a person wrote, which is the rule that keeps the metaphor
 * honest. Each style is a composable read, as it was when the scheme chose
 * between two sets of faces: the 526 call sites read `TapeType.pixel` and
 * friends inside composition, and a read outside composition (a
 * `remember`, a draw lambda) fails to compile and names itself, which is
 * the point.
 */
object TapeType {
    @Composable
    fun lcd(size: Int): TextStyle =
        // Readouts in mono; the LCD's small body in the sans, at the floor.
        if (size <= 17) MODERN_BODY else TextStyle(fontFamily = FontFamily.Monospace, fontSize = (size - 2).coerceAtLeast(14).sp)

    /** A header on the LCD: a serif italic, the handwriting's twin. */
    val lcdHeader: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp)
    val lcdReadout: TextStyle
        @Composable get() = lcd(22)
    val lcdSmall: TextStyle
        @Composable get() = lcd(17)

    /** Chrome labels: mono at 11 sp with a touch of tracking (`.eyebrow`). */
    val pixel: TextStyle
        @Composable get() = MODERN_LABEL

    /**
     * The smallest style, at 228 sites - legends, subtitles, the lines under
     * things. Those are body text and get the 12 sp sans floor, not a
     * smaller label.
     */
    val pixelSmall: TextStyle
        @Composable get() = MODERN_BODY

    val display: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp)
    val displayBig: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 1.sp)

    /** What a person named: the serif italic - and nothing else in italic. */
    val marker: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp)
    val markerBig: TextStyle
        @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp)

    /** The body: a proportional sans at the 12 sp floor. */
    private val MODERN_BODY = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)

    /** The label: mono at 11 sp, upper case as written, +1 tracking. */
    private val MODERN_LABEL = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
}
