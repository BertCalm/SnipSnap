package com.snipsnap.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.snipsnap.app.R
import com.snipsnap.shell.Look
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
 * `amber` where it is drawn on chrome rather than on the LCD. The modern
 * look's rule (`docs/MODERN_LOOK.md` §4): amber is the LCD's colour and
 * never sits on chrome — on paper it is 2.1 : 1 — so a chrome site reads
 * `accent` under the modern look and `amber` under the classic one, where
 * the two are the same warm orange on dark. The frame's chips use this;
 * the screens' own amber-on-chrome sites are an audit the grep cannot do,
 * since it cannot tell a surface from a colour.
 */
val Scheme.amberOnChrome: Color @Composable get() = (if (look == Look.MODERN) accent else amber).tape

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

/**
 * Text styles per the handoff; sizes are dp-at-390 read as sp.
 *
 * Each style is a composable read of the current scheme's look
 * (`docs/MODERN_LOOK.md` §5 step 3): under `Look.CLASSIC` the four
 * TapeOS faces at the handoff's sizes, under `Look.MODERN` the system
 * faces at the floor `docs/DIRECTION_2026_10.md` §5 asks for — 12 sp for
 * anything read as text, 11 sp mono for labels and eyebrows, serif italic
 * for the words a person wrote, which is the rule that keeps the metaphor
 * honest across the change of face. The 526 call sites read
 * `TapeType.pixel` and friends inside composition and keep compiling as
 * written; a read outside composition (a `remember`, a draw lambda) fails
 * to compile and names itself, which is the point.
 */
object TapeType {
    private val look: Look @Composable get() = LocalScheme.current.look

    @Composable
    fun lcd(size: Int): TextStyle =
        if (look == Look.MODERN) {
            // Readouts in mono; the LCD's small body in the sans, at the floor.
            if (size <= 17) MODERN_BODY else TextStyle(fontFamily = FontFamily.Monospace, fontSize = (size - 2).coerceAtLeast(14).sp)
        } else {
            TextStyle(fontFamily = TapeFonts.Lcd, fontSize = size.sp)
        }

    /** A header on the LCD: the handwriting's modern twin is a serif italic. */
    val lcdHeader: TextStyle
        @Composable get() = if (look == Look.MODERN) TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp) else TextStyle(fontFamily = TapeFonts.Lcd, fontSize = 21.sp)
    val lcdReadout: TextStyle
        @Composable get() = lcd(22)
    val lcdSmall: TextStyle
        @Composable get() = lcd(17)

    /** Chrome labels: pixel at 9 sp, or mono at 11 sp with a touch of tracking (`.eyebrow`). */
    val pixel: TextStyle
        @Composable get() = if (look == Look.MODERN) MODERN_LABEL else TextStyle(fontFamily = TapeFonts.Pixel, fontSize = 9.sp, letterSpacing = 0.5.sp)

    /**
     * The smallest classic style, 8 sp at 228 sites — legends, subtitles,
     * the lines under things. Under the modern look those are body text and
     * get the 12 sp sans floor, not a smaller label.
     */
    val pixelSmall: TextStyle
        @Composable get() = if (look == Look.MODERN) MODERN_BODY else TextStyle(fontFamily = TapeFonts.Pixel, fontSize = 8.sp, letterSpacing = 0.5.sp)

    val display: TextStyle
        @Composable get() = if (look == Look.MODERN) TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp) else TextStyle(fontFamily = TapeFonts.Display, fontSize = 11.sp, letterSpacing = 2.sp)
    val displayBig: TextStyle
        @Composable get() = if (look == Look.MODERN) TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 1.sp) else TextStyle(fontFamily = TapeFonts.Display, fontSize = 12.sp, letterSpacing = 2.sp)

    /** What a person named: handwriting, or the modern serif italic — and nothing else in italic. */
    val marker: TextStyle
        @Composable get() = if (look == Look.MODERN) TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp) else TextStyle(fontFamily = TapeFonts.Marker, fontSize = 13.sp)
    val markerBig: TextStyle
        @Composable get() = if (look == Look.MODERN) TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp) else TextStyle(fontFamily = TapeFonts.Marker, fontSize = 15.sp)

    /** The modern body: a proportional sans at the 12 sp floor. */
    private val MODERN_BODY = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)

    /** The modern label: mono at 11 sp, upper case as written, +1 tracking. */
    private val MODERN_LABEL = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
}
