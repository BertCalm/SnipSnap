package com.snipsnap.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one TapeOS invariant, in code: **gray bevelled chrome is where you
 * work; the dark LCD is where sound lives.** Light schemes lighten the
 * chrome — the LCD stays dark in all six, which `SchemesTest` in `:shell`
 * and `TokensTest` here both enforce.
 *
 * Screens compose these two rather than painting their own backgrounds, so
 * the rule cannot drift one screen at a time.
 */

/** Readouts, waveforms, pad grids — anything that represents sound. */
@Composable
fun LcdSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val s = LocalScheme.current
    Box(modifier = modifier.background(s.lcd.toColor()), content = content)
}

/** Window bodies, button rows, dialogs — anything you operate. */
@Composable
fun ChromeSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val s = LocalScheme.current
    Box(modifier = modifier.background(s.gray.toColor()), content = content)
}
