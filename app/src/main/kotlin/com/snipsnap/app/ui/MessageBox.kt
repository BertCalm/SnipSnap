package com.snipsnap.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.LandingNote

/**
 * The honest little message box (wave FFF): the capture-blocked dialog's
 * shape, generalised. A scrim, a raised bevel, the [note]'s title in LCD
 * type, its lines in a sunken field - trouble in the warn colour - and
 * the one button. It stays until read: the scrim and the button both
 * dismiss, the box itself swallows the tap.
 *
 * A landing may carry two more things (`LandingNote.dubbed`): a tile, drawn
 * 96 dp square above the title, and one route, a chip between the lines and
 * the button that closes the box and goes. A tile that will not decode is
 * left out - the words are the box, the picture is only company.
 */
@Composable
fun MessageBox(note: LandingNote.Note, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own (the card below swallows its
            // own tap, a separate merge boundary) — labelled with the
            // same word the visible dismiss button below already uses,
            // since the scrim does exactly what that button does.
            .tapeClick(label = note.button, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it doesn't fall through to the
                // scrim's dismiss handler below. A raw pointerInput,
                // not tapeClick: this Column's real children (the title,
                // the lines, PrimaryAction) carry their own accessible
                // names below, and clickable()'s own semantics would add
                // a second, nameless actionable node wrapping all of them
                // — worse than the thing this pass is fixing, not better.
                // A bare gesture detector registers no semantics node at
                // all, so it consumes the touch without touching the
                // accessibility tree (KitScreen.kt's PadCell uses the same
                // "pointerInput registers no click action" idiom).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val tile = remember(note.tile) {
                note.tile?.let { runCatching { BitmapFactory.decodeByteArray(it.png, 0, it.png.size)?.asImageBitmap() }.getOrNull() }
            }
            if (tile != null) {
                Image(
                    bitmap = tile,
                    contentDescription = "KIT TILE",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(TILE_DP.dp),
                )
            }
            TapeText(note.title, TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            if (note.lines.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().sunkenField(scheme).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (line in note.lines) {
                        // Three lines: the shelf's own refusal ("nothing on the shelf can read…") needs them at 390.
                        TapeText(line.text, TapeType.pixelSmall, if (line.trouble) scheme.warn.tape else scheme.ink2.tape, maxLines = 3)
                    }
                }
            }
            note.route?.let { route ->
                ActionButton(
                    route.label,
                    scheme,
                    enabled = true,
                    accessibilityLabel = route.label.removeSuffix("▸").trim(),
                    modifier = Modifier.fillMaxWidth().height(Layout.MIN_HIT_TARGET.dp),
                ) {
                    onDismiss()
                    route.go()
                }
            }
            PrimaryAction(label = note.button, enabled = true, onClick = onDismiss)
        }
    }
}

/** The landing's tile, square. */
private const val TILE_DP = 96
