package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.CaptureSource
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Scheme

/**
 * SETUP's LISTEN FROM (UAT E2-CHOOSER): the source the dock's LISTEN remembers, MIC or APP AUDIO,
 * one row each. The chosen row is pressed in and reads SELECTED beneath its name, as the scheme
 * rows read LOADED: a state said in a word, not only in a bevel. [source] is null until the player
 * has chosen one, in which case no row is selected and the dock still asks. Its own composable,
 * like PAD VELOCITY, so PropertiesScreen's body stays the size it is.
 */
@Composable
internal fun ListenSourceGroup(scheme: Scheme, source: CaptureSource?, onPick: (CaptureSource) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    GroupBox(
        legend = Copy.SETUP_LISTEN_HEADING,
        summary = source?.label ?: Copy.SETUP_LISTEN_ASKS,
        open = open,
        onToggle = { open = !open },
        scheme = scheme,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in CaptureSource.entries) {
                val selected = option == source
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                        .semantics { this.selected = selected }
                        .tapeClick(label = "${Copy.SETUP_LISTEN_HEADING} ${option.label}") { if (!selected) onPick(option) }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TapeText(option.label, TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                    if (selected) TapeText("SELECTED", TapeType.pixelSmall, scheme.ink2.tape)
                }
            }
        }
        TapeText(Copy.SETUP_LISTEN_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}
