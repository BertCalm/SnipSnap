package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.KitFromAnything

/**
 * A kit KIT FROM ANYTHING has just written: where it is on the shelf, and
 * the words [KitFromAnything.describe] gave for it (the same strings the
 * CLI prints). [genreLabel] is the genre's word, for the title.
 */
internal class KitFromMade(
    val entry: KitShelf.Entry,
    val report: KitFromAnything.Report,
    val genreLabel: String,
)

/**
 * KIT FROM ANYTHING's result, drawn instead of whatever is under it, as
 * [SpreadOverlay] is: the kit's name, key and tempo, the gate every pad was
 * held to and what the gate said, each blended pad's partner, the count,
 * and OPEN KIT. Back, and CLOSE, just close it; the kit is already on the
 * shelf either way.
 */
@Composable
internal fun KitFromResultOverlay(
    made: KitFromMade,
    scheme: Scheme,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val report = made.report
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText("KIT FROM ANYTHING — ${made.genreLabel}", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(label = "CLOSE", onClick = onClose)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("CLOSE", TapeType.pixel, scheme.amber.tape)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TapeText(report.header, TapeType.pixel, scheme.lcdInk.tape, maxLines = 3)
                TapeText(report.tableHeader, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
                report.rows.forEachIndexed { i, row ->
                    val ink = when (report.rowGates[i]) {
                        KitFromAnything.Gate.FAIL -> scheme.amber.tape
                        KitFromAnything.Gate.UNMEASURED -> scheme.ink3.tape
                        KitFromAnything.Gate.PASS -> scheme.lcdInk.tape
                    }
                    TapeText(row, TapeType.pixelSmall, ink, maxLines = 3)
                }
                for (line in report.blends) {
                    TapeText(line.trim(), TapeType.pixelSmall, scheme.amber.tape, maxLines = 4)
                }
                TapeText(report.summary, TapeType.pixel, scheme.lcdInk.tape, maxLines = 5)
            }
            ActionButton("OPEN KIT ▸", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onOpen)
        }
    }
    BackHandler(onBack = onClose)
}
