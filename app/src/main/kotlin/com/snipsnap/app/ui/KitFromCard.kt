package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.KeySpec
import com.snipsnap.shell.KeyPicker
import com.snipsnap.shell.KitMaker
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.KitFromAnything

/**
 * The pad sheet's KIT FROM ANYTHING card: the key (root and scale chips,
 * drawn the way the KIT screen's KEY panel draws them), a genre, and
 * MAKE KIT. Its own composable, with every input a parameter, so the pad
 * sheet's one large composable pays a single call for it and not a
 * column's worth of registers (SynthScreen met the dex register limit on
 * an inline Column). The pad's audio is the source; the sheet's handler
 * owns the refusals, the busy flag and the work, and [onMake] hands it
 * the choice.
 */
@Composable
internal fun KitFromCard(
    scheme: Scheme,
    /** The open kit's key; the chips open on it, else on A minor ([KitMaker.openingKey]). */
    kitKey: KeySpec?,
    busy: Boolean,
    /** This card's MAKE KIT is the job in flight: the button shows its ring. */
    working: Boolean,
    onMake: (KeySpec, KitFromAnything.Genre) -> Unit,
) {
    val opening = KitMaker.openingKey(kitKey)
    var root by remember(kitKey) { mutableStateOf(opening.rootSemitone) }
    var scaleLabel by remember(kitKey) { mutableStateOf(KeyPicker.scaleLabel(opening.scale)) }
    var genre by remember { mutableStateOf(KitMaker.DEFAULT_GENRE) }
    val key = KeyPicker.key(root, scaleLabel)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("KIT FROM ANYTHING · SIXTEEN PADS FROM THIS ONE · ${KeyPicker.label(key)}", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
        for (row in KeyPicker.ROOTS.indices.chunked(6)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in row) {
                    KitFromChip(KeyPicker.ROOTS[r], r == root, busy, scheme, TapeType.pixel, Modifier.weight(1f)) { root = r }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in KeyPicker.SCALES) {
                KitFromChip(s, s == scaleLabel, busy, scheme, TapeType.pixelSmall, Modifier.weight(1f), maxLines = 2) { scaleLabel = s }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (g in KitMaker.GENRES) {
                KitFromChip(KitMaker.genreLabel(g), g == genre, busy, scheme, TapeType.pixel, Modifier.weight(1f)) { genre = g }
            }
        }
        ActionButton(
            "MAKE KIT ▸ ${KitMaker.genreLabel(genre)}",
            scheme,
            enabled = !busy,
            working = working,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onMake(key, genre) },
        )
    }
}

/** One chip, KEY panel style: a raised bevel, amber when chosen. Always clickable, `!busy` forwarded to the touch layer. */
@Composable
private fun KitFromChip(
    label: String,
    selected: Boolean,
    busy: Boolean,
    scheme: Scheme,
    type: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
            .tapeClick(label = label, enabled = !busy, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, type, if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape, maxLines = maxLines)
    }
}
