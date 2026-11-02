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
import com.snipsnap.loop.OrbitSet
import com.snipsnap.shell.OrbitAquarium
import com.snipsnap.shell.Scheme

@Composable
internal fun OrbitAquariumPanel(set: OrbitSet, route: OrbitAquarium.Route, scheme: Scheme,
    busy: Boolean, playing: Boolean, faulted: Boolean, limitedFrames: Long,
    auditionUp: Boolean, auditionFaulted: Boolean, auditionPending: Boolean,
    onChange: (OrbitSet) -> Unit, onReset: () -> Unit,
) {
    val tails = listOf(0f, 1f, 3f, 5f, 10f, 30f)
    Column(Modifier.fillMaxWidth().testTag("orbit-aquarium-panel"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("AQUARIUM · ORBIT", TapeType.lcdHeader, scheme.ink.tape)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(if (set.aquariumFromKit) "KIT FX ON" else "USE KIT FX", scheme,
                enabled = !busy && (route.warning == null || set.aquariumFromKit), lit = set.aquariumFromKit,
                modifier = Modifier.weight(1f).testTag("orbit-aquarium-route").semantics { selected = set.aquariumFromKit },
                onClick = { onChange(set.copy(aquariumFromKit = !set.aquariumFromKit)) })
            ActionButton("RESET TAIL", scheme, enabled = !busy && set.aquariumFromKit,
                modifier = Modifier.weight(1f), onClick = onReset)
        }
        TapeText(when {
            faulted -> "AQUARIUM FAULT · RESET TAIL OR RESTART PLAY"
            !set.aquariumFromKit -> "KIT FX OFF"
            !route.settings.enabled -> "DRY · KIT PERFORMANCE IS BYPASSED"
            route.settings.settings.isBypass -> "DRY · ALL SIX EFFECT MIXES ARE ZERO"
            route.settings.outputGain == 0f -> "WET MIX MUTED"
            playing -> "STEREO KIT FX LIVE"
            else -> "KIT FX READY"
        }, TapeType.pixelSmall, if (faulted) scheme.warn.tape else scheme.ink.tape, maxLines = Int.MAX_VALUE)
        route.warning?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE) }
        TapeText(when {
            !auditionUp -> "TAP STREAM UNAVAILABLE"
            auditionFaulted -> "TAP FX FAULT · RESET TAIL OR RETRY A TAP"
            auditionPending -> "TAP FX WAITING TO QUEUE SETTINGS…"
            else -> "TAP STREAM READY"
        }, TapeType.pixelSmall, if (!auditionUp || auditionFaulted) scheme.warn.tape else scheme.ink2.tape,
            maxLines = Int.MAX_VALUE)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("TAIL −", scheme, enabled = !busy && set.aquariumTailSeconds > 0,
                modifier = Modifier.weight(1f), accessibilityLabel = "Shorten ORBIT FX bounce tail",
                onClick = { onChange(set.copy(aquariumTailSeconds = tails.lastOrNull { it < set.aquariumTailSeconds } ?: 0f)) })
            TapeText("${set.aquariumTailSeconds.toInt()} s", TapeType.pixel, scheme.ink.tape)
            ActionButton("TAIL +", scheme, enabled = !busy && set.aquariumTailSeconds < 30,
                modifier = Modifier.weight(1f), accessibilityLabel = "Lengthen ORBIT FX bounce tail",
                onClick = { onChange(set.copy(aquariumTailSeconds = tails.firstOrNull { it > set.aquariumTailSeconds } ?: 30f)) })
        }
        TapeText("Saved KIT → PERFORMANCE sound at ORBIT tempo. Mix and tapped pads have separate FX history. BOUNCE starts fresh for one turn plus the chosen zero-input tail; taps are excluded. The ending can cut a long tail, and a wet bounce can have a loop seam. CLIP TO KIT carries notes only.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
        TapeText("SNIPS keeps the stereo file; TAPE edits in mono. A wet bounce already contains FX. Playing it through KIT FX applies them again.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
        if (set.aquariumFromKit) TapeText("MIX PROTECTION · $limitedFrames FRAMES THIS PLAY", TapeType.pixelSmall, scheme.ink2.tape)
    }
}
