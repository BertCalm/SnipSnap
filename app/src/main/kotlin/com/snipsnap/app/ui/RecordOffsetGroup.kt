package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.AudioRoute
import com.snipsnap.app.RecordLatency
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.OutputRoute
import com.snipsnap.shell.Scheme

/** The step of the RECORD OFFSET chips, in ms. */
private const val OFFSET_STEP_MS = 10

/**
 * SETUP's RECORD OFFSET: how far a recorded hit is moved back for the time
 * the sound takes to reach the ear, 0 to 400 ms. AUTO (the default) is
 * whatever the stream reports, or 200 ms on Bluetooth; a number is the
 * player's own, and wins. Its own composable, like the groups beside it,
 * so PropertiesScreen's body stays the size it is. The value lives in
 * [RecordLatency].
 */
@Composable
internal fun RecordOffsetGroup(scheme: Scheme) {
    val context = LocalContext.current
    remember(context) { RecordLatency.init(context) }
    var open by rememberSaveable { mutableStateOf(false) }
    val manual = RecordLatency.manualMs
    // Read here so AUTO's own figure follows the route while SETUP is open.
    val now = OutputRoute.compensationMs(manual, RecordLatency.measuredMs, AudioRoute.isBluetooth)
    val reading = if (manual == null) "AUTO · $now MS" else "$manual MS"
    GroupBox(
        legend = "RECORD OFFSET",
        summary = reading,
        open = open,
        onToggle = { open = !open },
        scheme = scheme,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OffsetChip("AUTO", manual == null, "RECORD OFFSET AUTO", scheme, Modifier.weight(1f)) { RecordLatency.choose(null) }
            OffsetChip("− $OFFSET_STEP_MS", false, "RECORD OFFSET DOWN", scheme, Modifier.weight(1f)) {
                RecordLatency.choose(now - OFFSET_STEP_MS)
            }
            OffsetChip("+ $OFFSET_STEP_MS", false, "RECORD OFFSET UP", scheme, Modifier.weight(1f)) {
                RecordLatency.choose(now + OFFSET_STEP_MS)
            }
        }
        TapeText(reading, TapeType.pixel, scheme.ink.tape, maxLines = 1)
        TapeText(Copy.SETUP_RECORD_OFFSET_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
    }
}

@Composable
private fun OffsetChip(text: String, selected: Boolean, label: String, scheme: Scheme, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .semantics { this.selected = selected }
            .tapeClick(label = label) { if (!selected) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
    }
}
