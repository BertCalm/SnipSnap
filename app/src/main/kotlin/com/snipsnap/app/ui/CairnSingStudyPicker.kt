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
import com.snipsnap.shell.CairnSingInstrumentPicker

/** CAIRN SING's five studies from MAKE; the phone renders and checks each capture first. */
@Composable
internal fun CairnSingStudyPicker(enabled: Boolean, onMake: (String) -> Unit) {
    val scheme = LocalScheme.current
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) choosing = false }
    Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
        .raisedBevel(scheme)
        .tapeClick(label = "CAIRN SING INSTRUMENTS", enabled = enabled) { if (enabled) choosing = true }
        .padding(10.dp), contentAlignment = Alignment.CenterStart) {
        TapeText("CAIRN SING ▾", TapeType.pixel, scheme.ink.tape)
    }
    if (choosing && enabled) Dialog(onDismissRequest = { choosing = false }) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().raisedBevel(scheme)
            .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TapeText("CAIRN SING", TapeType.markerBig, scheme.ink.tape)
            TapeText("ONE VOICE, FIVE SETTINGS. CHOOSE AN INSTRUMENT FOR THE LIBRARY.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            TapeText("A CONTINUOUS HELD TONE WITH A SHORT FADE WHEN YOU LIFT A KEY. THE FIRST ONE TAKES A MOMENT TO PREPARE.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            for (choice in CairnSingInstrumentPicker.choices) {
                Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "MAKE CAIRN ${choice.label}") {
                        choosing = false
                        onMake(choice.id)
                    }.padding(10.dp)) {
                    TapeText("${choice.label} · ${choice.range}", TapeType.pixelSmall, scheme.ink.tape)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .tapeClick(label = "CLOSE CAIRN INSTRUMENTS") { choosing = false }
                .padding(10.dp), contentAlignment = Alignment.Center) {
                TapeText("CLOSE", TapeType.pixelSmall, scheme.ink2.tape)
            }
        }
    }
}
