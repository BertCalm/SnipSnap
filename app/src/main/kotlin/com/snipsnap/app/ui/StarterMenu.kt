package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Layout
import com.snipsnap.shell.StarterKits

/**
 * NEW KIT: the first-run and every-run answer to "an empty grid never
 * shows". Six starters over [StarterKits.ALL] plus one BLANK TAPE row.
 *
 * Picking a starter closes this menu and lands on the grid — reroll is
 * re-opening the menu and tapping the same row again with a fresh seed,
 * not a control that lives here.
 */
@Composable
fun StarterMenu(
    onPick: (StarterKits.Starter) -> Unit,
    onBlank: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(s.gray.toColor())
                .padding(14.dp)
                // Swallow the click so tapping the card doesn't dismiss it.
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = "NEW KIT",
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.display,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                ),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StarterKits.ALL.forEach { starter ->
                    StarterRow(starter = starter, onClick = { onPick(starter) })
                }
                BlankTapeRow(onClick = onBlank)
            }
        }
    }
}

@Composable
private fun StarterRow(starter: StarterKits.Starter, onClick: () -> Unit) {
    val s = LocalScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(s.lcd.toColor())
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(
            text = starter.displayName,
            style = TextStyle(
                color = s.lcdInk.toColor(),
                fontFamily = TapeFonts.display,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            ),
        )
        BasicText(
            text = starter.blurb,
            style = TextStyle(
                color = s.ink2.toColor(),
                fontFamily = TapeFonts.pixel,
                fontSize = 9.sp,
            ),
        )
        if (starter.seeded) {
            // Reroll isn't a control on this row — it's tapping the row
            // again, later, with a fresh seed. The hint says so, rather
            // than reading like "tap this twice right now".
            BasicText(
                text = "EVERY TAP ROLLS A NEW ONE",
                style = TextStyle(
                    color = s.amber.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                ),
            )
        }
    }
}

@Composable
private fun BlankTapeRow(onClick: () -> Unit) {
    val s = LocalScheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.PRIMARY_ACTION_H.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(s.lcd.toColor())
            .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "BLANK TAPE",
            style = TextStyle(
                color = s.lcdInk.toColor(),
                fontFamily = TapeFonts.display,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            ),
        )
    }
}
