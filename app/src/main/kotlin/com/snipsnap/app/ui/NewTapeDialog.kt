package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.store.NameVerdict
import com.snipsnap.app.store.verifyKitName
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor

/**
 * FRESH TAPE: name the thing before it exists.
 *
 * Validation is [verifyKitName], and the rejection shows *inline* rather
 * than disabling the button — telling you why beats a dead control
 * (personality law 3: jokes never gate function, and neither do errors).
 */
@Composable
fun NewTapeDialog(
    existing: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalScheme.current
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
                text = "NAME THIS TAPE",
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.display,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(s.lcd.toColor())
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it; error = null },
                    singleLine = true,
                    cursorBrush = SolidColor(s.lcdInk.toColor()),
                    textStyle = TextStyle(
                        color = s.lcdInk.toColor(),
                        fontFamily = TapeFonts.lcd,
                        fontSize = 22.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let {
                BasicText(
                    text = it,
                    style = TextStyle(
                        color = s.amber.toColor(),
                        fontFamily = TapeFonts.pixel,
                        fontSize = 9.sp,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(label = "CANCEL", modifier = Modifier.weight(1f)) { onDismiss() }
                DialogButton(label = "ROLL IT", modifier = Modifier.weight(1f)) {
                    when (val verdict = verifyKitName(typed, existing)) {
                        is NameVerdict.Ok -> onConfirm(verdict.name)
                        is NameVerdict.Rejected -> error = verdict.reason
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = LocalScheme.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(s.lcd.toColor())
            .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = s.lcdInk.toColor(),
                fontFamily = TapeFonts.pixel,
                fontSize = 9.sp,
            ),
        )
    }
}
