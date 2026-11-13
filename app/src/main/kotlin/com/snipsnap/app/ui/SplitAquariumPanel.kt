package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SplitAquarium
import java.util.Locale

@Composable
internal fun SplitAquariumPanel(settings: SplitAquarium.Settings, route: SplitAquarium.Route, scheme: Scheme,
    ready: Boolean, refused: Boolean, busy: Boolean, streamUp: Boolean, faulted: Boolean, pending: Boolean,
    limitedFrames: Long, onChange: (SplitAquarium.Settings) -> Unit, onReset: () -> Unit, onClose: () -> Unit,
) {
    val tails = listOf(0f, 1f, 3f, 5f, 10f, 30f)
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(scheme.gray.tape).padding(12.dp)
            .verticalScroll(rememberScrollState()).testTag("split-aquarium-panel"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText("AQUARIUM · SPLIT", TapeType.lcdHeader, scheme.ink.tape)
            TapeText(com.snipsnap.shell.Copy.AQUARIUM_LINE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(if (settings.fromKit) "KIT FX ON" else "USE KIT FX", scheme,
                    enabled = ready && !refused && !busy && (route.warning == null || settings.fromKit),
                    lit = settings.fromKit,
                    modifier = Modifier.weight(1f).testTag("split-aquarium-route").semantics { selected = settings.fromKit },
                    onClick = { onChange(settings.copy(fromKit = !settings.fromKit)) })
                ActionButton("RESET TAIL", scheme, enabled = ready && !busy && settings.fromKit && streamUp,
                    modifier = Modifier.weight(1f), onClick = onReset)
            }
            TapeText(when {
                !ready -> "LOADING SPLIT FX SETTINGS"
                refused -> "SPLIT FX SETTINGS UNREADABLE · USING DRY"
                !streamUp -> "NO SPLIT STREAM · PLAY CAN RETRY"
                faulted -> "AQUARIUM FAULT · RESET TAIL OR RETRY PLAY"
                pending -> "Waiting to queue settings…"
                !settings.fromKit -> "KIT FX OFF"
                !route.sound.enabled -> "DRY · KIT PERFORMANCE IS BYPASSED"
                route.sound.settings.isBypass -> "DRY · ALL SIX EFFECT MIXES ARE ZERO"
                route.sound.outputGain == 0f -> "WET MIX MUTED"
                else -> "STEREO KIT FX READY"
            }, TapeType.pixelSmall, if (refused || faulted || !streamUp) scheme.warn.tape else scheme.ink.tape,
                Modifier.testTag("split-aquarium-state"), maxLines = Int.MAX_VALUE)
            route.warning?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("TAIL −", scheme, enabled = ready && !refused && !busy && settings.tailSeconds > 0,
                    modifier = Modifier.weight(1f), accessibilityLabel = "Shorten SPLIT FX print tail",
                    onClick = { onChange(settings.copy(tailSeconds = tails.lastOrNull { it < settings.tailSeconds } ?: 0f)) })
                TapeText(String.format(Locale.ROOT, "%.1f s", settings.tailSeconds), TapeType.pixel, scheme.ink.tape)
                ActionButton("TAIL +", scheme, enabled = ready && !refused && !busy && settings.tailSeconds < 30,
                    modifier = Modifier.weight(1f), accessibilityLabel = "Lengthen SPLIT FX print tail",
                    onClick = { onChange(settings.copy(tailSeconds = tails.firstOrNull { it > settings.tailSeconds } ?: 30f)) })
            }
            TapeText("Saved for this kit. Uses KIT → PERFORMANCE sound and kit tempo on SPLIT’s own stereo mix.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            TapeText("PRINT starts fresh for one pass plus the chosen FX tail. It does not capture a running loop’s history. Hot mixes clip before KIT FX; wet output is protected. Long tails can be cut and a wet print can have a loop seam.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            TapeText("The SAVED SAMPLES file and PAD keep stereo; SAMPLE edits in mono. A wet print already contains FX. KIT FX applies them again on playback.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            if (settings.fromKit) TapeText("PROTECTION · $limitedFrames FRAMES THIS STREAM", TapeType.pixelSmall, scheme.ink2.tape)
            ActionButton("DONE", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}
