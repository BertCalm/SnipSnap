package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SurfaceAquarium

@Composable
internal fun SurfaceAquariumPanel(
    route: SurfaceAquarium.Route, optedIn: Boolean, ready: Boolean, busy: Boolean,
    streamUp: Boolean, faulted: Boolean, pending: Boolean, limitedFrames: Long,
    scheme: Scheme, onToggle: () -> Unit, onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("surface-aquarium-panel"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("AQUARIUM · KIT", TapeType.lcdHeader, scheme.ink.tape)
        TapeText(com.snipsnap.shell.Copy.AQUARIUM_LINE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(if (optedIn) "KIT FX ON" else "USE KIT FX", scheme,
                enabled = ready && !busy && (route.warning == null || optedIn), lit = optedIn,
                accessibilityLabel = "${if (optedIn) "Disconnect" else "Use"} kit Aquarium on SURFACE",
                modifier = Modifier.weight(1f).testTag("surface-aquarium-route").semantics { selected = optedIn },
                onClick = onToggle)
            ActionButton("RESET TAIL", scheme, enabled = ready && !busy && optedIn,
                modifier = Modifier.weight(1f).testTag("surface-aquarium-reset"), onClick = onReset)
        }
        TapeText(when {
            !ready -> "Choose a kit · wait for its surface settings"
            !streamUp -> "NO STREAM"
            faulted -> "Aquarium fault · reset tail or reopen surface"
            pending -> "Waiting to queue settings…"
            !optedIn -> "KIT FX OFF"
            !route.settings.enabled -> "Dry · live kit effects are off"
            route.settings.settings.isBypass -> "Dry · all six effect mixes are zero"
            route.settings.outputGain == 0f -> "WET MIX MUTED"
            else -> "Stereo kit FX live"
        }, TapeType.pixelSmall, if (faulted) scheme.warnInk.tape else scheme.ink.tape,
            Modifier.testTag("surface-aquarium-state"), maxLines = Int.MAX_VALUE)
        route.warning?.let { TapeText(it, TapeType.pixelSmall, scheme.warnInk.tape, maxLines = Int.MAX_VALUE) }
        TapeText("Saved for this kit. Uses KIT TOOLS → LIVE KIT EFFECTS settings on SURFACE’s own stereo bus. PRINT includes FX. Playing the print through KIT FX applies them again.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
        if (optedIn) TapeText("Protection · $limitedFrames frames this stream", TapeType.pixelSmall, scheme.ink2.tape)
    }
}
