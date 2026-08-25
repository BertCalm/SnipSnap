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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.store.KitEntry
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout

/** MY KITS — the tape shelf. */
@Composable
fun KitsScreen(
    entries: List<KitEntry>,
    onOpen: (KitEntry) -> Unit,
    onNew: () -> Unit,
) {
    val s = LocalScheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = "MY KITS", right = "${entries.size} TAPES")

        if (entries.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = Copy.EMPTY_SHELF,
                    style = TextStyle(
                        color = s.ink2.toColor(),
                        fontFamily = TapeFonts.pixel,
                        fontSize = 9.sp,
                    ),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.dir.absolutePath }) { entry ->
                    ShelfRow(entry = entry, onOpen = { onOpen(entry) })
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Layout.PRIMARY_ACTION_H.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(s.lcd.toColor())
                .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(6.dp))
                .clickable { onNew() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "+  NEW BLANK TAPE",
                style = TextStyle(
                    color = s.lcdInk.toColor(),
                    fontFamily = TapeFonts.display,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                ),
            )
        }
    }
}

@Composable
private fun ShelfRow(entry: KitEntry, onOpen: () -> Unit) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(s.field.toColor())
            .clickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = entry.name,
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.marker,
                    fontSize = 15.sp,
                ),
            )
            BasicText(
                text = "${entry.padCount} SNIPS",
                style = TextStyle(
                    color = s.ink2.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                ),
            )
        }
        BasicText(
            text = if (entry.padCount == 0) "DRAFT" else "ON SHELF",
            style = TextStyle(
                color = s.amber.toColor(),
                fontFamily = TapeFonts.pixel,
                fontSize = 9.sp,
            ),
        )
    }
}

/** The sunken LCD strip every screen wears at the top. */
@Composable
fun LcdHeader(left: String, right: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.LCD_HEADER_H.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(s.lcd.toColor())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = left,
            style = TextStyle(color = s.lcdInk.toColor(), fontFamily = TapeFonts.lcd, fontSize = 25.sp),
        )
        BasicText(
            text = right,
            style = TextStyle(color = s.amber.toColor(), fontFamily = TapeFonts.lcd, fontSize = 21.sp),
        )
    }
}
