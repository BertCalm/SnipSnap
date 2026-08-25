package com.snipsnap.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.snipsnap.app.R

/**
 * The four TapeOS typefaces, bundled rather than downloaded.
 *
 * `androidx.compose.ui.text.googlefonts` needs the Play Services font
 * provider; the development emulator image is `google_apis` (no Play
 * Store), so a downloadable font would fail in exactly the loop this
 * milestone is iterated in. `:shell`'s [com.snipsnap.shell.Type] names
 * which face goes where; this object supplies them.
 */
object TapeFonts {
    /** VT323 — everything on an LCD. */
    val lcd: FontFamily = FontFamily(Font(R.font.vt323))

    /** Silkscreen — pixel UI chrome, menu row, status bar. */
    val pixel: FontFamily = FontFamily(Font(R.font.silkscreen))

    /** Michroma — display headers and key actions. */
    val display: FontFamily = FontFamily(Font(R.font.michroma))

    /** Permanent Marker — handwriting on pads and cassette labels. */
    val marker: FontFamily = FontFamily(Font(R.font.permanent_marker))
}
