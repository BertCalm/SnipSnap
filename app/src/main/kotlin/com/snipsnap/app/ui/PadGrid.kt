package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Schemes
import com.snipsnap.xpm.PadNoteMap

/**
 * Grid cell → pad slot, in MPC orientation: row 0 is the **top** row and
 * holds slots 13–16, row 3 is the bottom and holds 1–4, so A01 sits
 * bottom-left exactly as it does on the hardware. What your hands learn
 * here is what the MPC gives back.
 */
fun slotForCell(row: Int, col: Int, bankIndex: Int = 0): Int {
    require(row in 0..3 && col in 0..3) { "cell out of range: $row,$col" }
    require(bankIndex in 0..7) { "bank 0..7 (A..H), got $bankIndex" }
    return bankIndex * 16 + (3 - row) * 4 + col + 1
}

/** The inverse of [slotForCell], within the slot's own bank. */
fun cellForSlot(slot: Int): Pair<Int, Int> {
    require(slot >= 1) { "slot out of range: $slot" }
    val within = (slot - 1) % 16
    return (3 - within / 4) to (within % 4)
}

/**
 * The 4×4 grid. An assigned pad wears its class colour as a border and a
 * handwritten label; an empty one is bare chrome. Both come from
 * `:shell`'s tables so the colours match the MPC's own.
 */
@Composable
fun PadGrid(pads: List<KitPad?>, onHit: (Int) -> Unit) {
    val s = LocalScheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
        for (row in 0..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                for (col in 0..3) {
                    val slot = slotForCell(row, col)
                    val pad = pads.getOrNull(slot - 1)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(Layout.PAD_H.dp)
                            .clip(RoundedCornerShape(Layout.PAD_RADIUS.dp))
                            .background(if (pad == null) s.gray.toColor() else s.lcd.toColor())
                            .then(
                                if (pad == null) Modifier
                                else Modifier.border(
                                    2.dp,
                                    Schemes.classColor(pad.drumClass).toColor(),
                                    RoundedCornerShape(Layout.PAD_RADIUS.dp),
                                ),
                            )
                            .clickable { onHit(slot) },
                    ) {
                        BasicText(
                            text = PadNoteMap.labelForPad(slot),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 5.dp),
                            style = TextStyle(
                                color = s.ink2.toColor(),
                                fontFamily = TapeFonts.pixel,
                                fontSize = 9.sp,
                            ),
                        )
                        if (pad != null) {
                            BasicText(
                                text = pad.displayName,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .padding(bottom = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    color = Schemes.classColor(pad.drumClass).toColor(),
                                    fontFamily = TapeFonts.marker,
                                    fontSize = 13.sp,
                                    // Needed *because* of fillMaxWidth above:
                                    // the text box now spans the pad, so
                                    // BottomCenter no longer centres the
                                    // glyphs — without this they sit hard
                                    // against the left edge.
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
