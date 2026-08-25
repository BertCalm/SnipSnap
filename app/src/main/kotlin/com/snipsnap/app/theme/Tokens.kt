package com.snipsnap.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes

/**
 * The bridge between `:shell`'s scheme tables and Compose.
 *
 * Every colour in this app comes through here. `:shell` owns the values —
 * a colour that exists only in the UI layer cannot be regression-tested,
 * which is the whole reason the tables live in a JVM module.
 */

/** Packed `0xRRGGBB` (the form `:shell` stores) as an opaque Compose colour. */
fun Int.toColor(): Color = Color(this or 0xFF000000.toInt())

/** Every colour token, paired with its field name, for exhaustive checks. */
fun Scheme.tokens(): List<Pair<String, Int>> = listOf(
    "gray" to gray, "grayHi" to grayHi, "grayEdge" to grayEdge,
    "grayMid" to grayMid, "grayDark" to grayDark,
    "ink" to ink, "ink2" to ink2,
    "title1" to title1, "title2" to title2, "titleInk" to titleInk,
    "desk1" to desk1, "desk2" to desk2,
    "lcd" to lcd, "lcdInk" to lcdInk, "amber" to amber, "field" to field,
)

val LocalScheme: ProvidableCompositionLocal<Scheme> =
    staticCompositionLocalOf { Schemes.DEFAULT }

@Composable
fun TapeOsTheme(scheme: Scheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScheme provides scheme, content = content)
}
