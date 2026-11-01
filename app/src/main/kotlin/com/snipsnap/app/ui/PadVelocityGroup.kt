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
import com.snipsnap.app.PadFeelStore
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.PadVelocity
import com.snipsnap.shell.Scheme

/**
 * SETUP's PAD VELOCITY: how a tap on a pad becomes a velocity (POS, FULL LVL
 * or AREA) and the curve laid over it. Its own composable, like the other
 * groups that grew out of PropertiesScreen, so that screen's body stays the
 * size it is. The choice lives in [PadFeelStore]; this only shows and sets it.
 */
@Composable
internal fun PadVelocityGroup(scheme: Scheme) {
    val context = LocalContext.current
    remember(context) { PadFeelStore.init(context) }
    var open by rememberSaveable { mutableStateOf(false) }
    val mode = PadFeelStore.mode
    val curve = PadFeelStore.curve
    GroupBox(
        legend = "PAD VELOCITY",
        summary = "${mode.chip} · ${curve.chip}",
        open = open,
        onToggle = { open = !open },
        scheme = scheme,
    ) {
        VelocityChips("MODE", PadVelocity.Mode.entries, mode, { it.chip }, PadFeelStore::setMode, scheme)
        TapeText(Copy.SETUP_PAD_VELOCITY_MODE_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
        VelocityChips("CURVE", PadVelocity.Curve.entries, curve, { it.chip }, PadFeelStore::setCurve, scheme)
        TapeText(Copy.SETUP_PAD_VELOCITY_CURVE_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}

/** One row of equal chips, the chosen one pressed in: HAPTICS' two-up, for any number. */
@Composable
private fun <T> VelocityChips(
    label: String,
    options: List<T>,
    chosen: T,
    chip: (T) -> String,
    onChoose: (T) -> Unit,
    scheme: Scheme,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) {
            val selected = option == chosen
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                    .semantics { this.selected = selected }
                    .tapeClick(label = "PAD VELOCITY $label ${chip(option)}") { if (!selected) onChoose(option) },
                contentAlignment = Alignment.Center,
            ) {
                TapeText(chip(option), TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
            }
        }
    }
}
