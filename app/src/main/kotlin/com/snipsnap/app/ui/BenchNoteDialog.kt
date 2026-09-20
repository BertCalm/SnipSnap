package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.BenchNotes
import com.snipsnap.shell.Copy

/**
 * BENCH NOTE (`docs/WORKSHOP.md`, WS4): the slip the title bar's NOTE
 * chip pulls down. `KitRenameDialog`'s shape — scrim, raised bevel, a
 * `BasicTextField` in a sunken field, CANCEL beside the one real button —
 * with three differences, each for a reason:
 *
 * - **It hangs from the top, not the centre.** The keyboard is up for
 *   the whole of this dialog's life and covers the lower half of the
 *   screen; a card at the top stays clear of it whichever way the window
 *   answers the keyboard (a pan finds the field already visible and does
 *   nothing; a resize is met by [imePadding] and the card stays put). A
 *   centred card would have put KEEP under the keys. And it hangs from
 *   the chip that opened it.
 * - **The field takes lines**, because a note is a sentence or two typed
 *   with a thumb, and a single-line field that scrolls sideways hides
 *   what was just said. What is *kept* is still one line:
 *   [BenchNotes.oneLine] folds the newlines, because the `→` line it
 *   becomes at the desk is one line too.
 * - **The keyboard comes up on its own.** The chip was the tap; a second
 *   tap on the field, standing there with the note in your head, is the
 *   tap that loses it.
 *
 * [context] is the stamp's own line (`PLAY · Break Kit · A03`), shown
 * before a word is typed so the tester sees what the note will be filed
 * under. KEEP is dim until there is a word to keep; [onKeep] receives
 * the folded line, never the raw text.
 */
@Composable
fun BenchNoteDialog(context: String, onCancel: () -> Unit, onKeep: (String) -> Unit) {
    val scheme = LocalScheme.current
    var text by remember { mutableStateOf("") }
    val line = BenchNotes.oneLine(text)
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .imePadding()
            // No descendant text of its own — labelled with the same
            // word the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it doesn't fall through to the
                // scrim's CANCEL below — a bare gesture detector, which
                // registers no semantics node (MessageBox.kt's pattern).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("BENCH NOTE", TapeType.lcdSmall, scheme.ink.tape)
            TapeText(context, TapeType.pixel, scheme.ink2.tape, maxLines = 2)
            Box(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 4,
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            TapeText(Copy.NOTE_PROMPT, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("KEEP", scheme, enabled = line.isNotEmpty(), modifier = Modifier.weight(1f), onClick = { onKeep(line) })
            }
        }
    }
}
