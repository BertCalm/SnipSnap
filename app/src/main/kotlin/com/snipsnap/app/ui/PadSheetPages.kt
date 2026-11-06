package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme

/**
 * One door row on the pad editor to a screen of its own (`EFFECTS ▸`, `VARIATIONS ▸`): the [label], whose
 * `▸` says it opens a screen, and under it the closed [line] saying what is applied or what is inside
 * ([com.snipsnap.shell.PadSheetBoxes.effectsLine], [com.snipsnap.shell.PadSheetBoxes.variationsLine]).
 * Always drawn, greyed while the sheet is busy ([enabled] false), so the editor does not rearrange
 * itself pad to pad.
 *
 * A composable of its own and not an inline Column on purpose: [PadSheetScreen] is far
 * past the dex register limit's comfort, and an inline Column here would compile into it.
 */
@Composable
internal fun PageDoor(label: String, line: String, scheme: Scheme, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionButton(label, scheme, enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = onClick)
        TapeText(line, TapeType.pixelSmall, scheme.ink2.tape, Modifier.fillMaxWidth(), maxLines = 1)
    }
}

/**
 * The body of one of the editor's pages (EFFECTS, VARIATIONS). The point of being a function with a
 * content lambda: the page's controls compile into the lambda's own method, out of [PadSheetScreen]'s,
 * whose register count is the one this file exists to keep down. It holds no state; everything the
 * controls read and write is the editor's, which is why the pages are drawn by the editor's own call.
 */
@Composable
internal fun PadPageBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
