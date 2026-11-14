package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.MurmurKeptInstrumentPicker as KeptMurmurPicker

/** Reaches the existing kept-source maker from MAKE, without changing a live SYNTH patch. */
@Composable
internal fun MurmurKeptInstrumentPicker(enabled: Boolean, onMake: (String) -> Unit) {
    val scheme = LocalScheme.current
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) choosing = false }
    Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
        .raisedBevel(scheme)
        .tapeClick(label = "MURMUR PLUCK INSTRUMENTS", enabled = enabled) { if (enabled) choosing = true }
        .padding(10.dp), contentAlignment = Alignment.CenterStart) {
        TapeText("MURMUR PLUCK ▾", TapeType.pixel, scheme.ink.tape)
    }
    if (choosing && enabled) Dialog(onDismissRequest = { choosing = false }) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().raisedBevel(scheme)
            .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TapeText("MURMUR PLUCK", TapeType.markerBig, scheme.ink.tape)
            TapeText("THREE KEPT SETTINGS. CHOOSE AN INSTRUMENT FOR THE LIBRARY.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            TapeText("THE HELD SETTING KEEPS SOUNDING UNTIL YOU LIFT A KEY.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            for (choice in KeptMurmurPicker.choices) {
                Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "MAKE MURMUR ${choice.label}") {
                        choosing = false
                        onMake(choice.id)
                    }.padding(10.dp)) {
                    TapeText("${choice.label} · ${choice.range}", TapeType.pixelSmall, scheme.ink.tape)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .tapeClick(label = "CLOSE MURMUR INSTRUMENTS") { choosing = false }
                .padding(10.dp), contentAlignment = Alignment.Center) {
                TapeText("CLOSE", TapeType.pixelSmall, scheme.ink2.tape)
            }
        }
    }
}
