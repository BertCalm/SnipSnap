package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.statusQuip
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Layout

/**
 * The window everything lives in: a 1996 title bar, a menu row, the
 * screen, and a three-cell status bar — ported from the working prototype
 * (`design/TapeOS Oilslick.dc.html`). Sizes come from `:shell`'s [Layout],
 * which is the handoff's dp table in Kotlin form.
 */
@Composable
fun SnipSnapWindow(
    state: NavState,
    onMenu: (Screen) -> Unit,
    tapeCell: String,
    snipCell: String,
    content: @Composable () -> Unit,
) {
    val s = LocalScheme.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The desk bleeds edge to edge — it is the desktop behind
            // everything, and it should run under the status and gesture
            // bars. The *window* must not: targetSdk 35 on Android 15 is
            // edge-to-edge with no opt-out, so without this inset the
            // system clock draws straight through the titlebar.
            .background(Brush.verticalGradient(listOf(s.desk1.toColor(), s.desk2.toColor())))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Layout.OUTER_MARGIN.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(s.gray.toColor())
                .padding(4.dp),
        ) {
            Titlebar(title = state.screen.title)
            MenuRow(current = state.screen, onMenu = onMenu)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            StatusBar(tapeCell = tapeCell, snipCell = snipCell, quip = state.statusQuip())
        }
    }
}

@Composable
private fun Titlebar(title: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.TITLEBAR_H.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(s.title1.toColor(), s.title2.toColor())))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = s.titleInk.toColor(),
                fontFamily = TapeFonts.display,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            ),
        )
    }
}

@Composable
private fun MenuRow(current: Screen, onMenu: (Screen) -> Unit) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.MENU_ROW_H.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (screen in Screen.entries) {
            BasicText(
                text = screen.menuLabel,
                modifier = Modifier
                    .clickable { onMenu(screen) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (screen == current) s.lcdInk.toColor() else s.ink2.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp,
                ),
            )
        }
    }
}

@Composable
private fun StatusBar(tapeCell: String, snipCell: String, quip: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.STATUS_BAR_H.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StatusCell(tapeCell, s.lcdInk.toColor())
        StatusCell(snipCell, s.amber.toColor())
        StatusCell(quip, s.amber.toColor(), modifier = Modifier.weight(1f), fontSize = 8)
    }
}

@Composable
private fun StatusCell(
    text: String,
    ink: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    fontSize: Int = 9,
) {
    val s = LocalScheme.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(s.lcd.toColor())
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = ink,
                fontFamily = TapeFonts.pixel,
                fontSize = fontSize.sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
