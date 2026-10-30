package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.MidiBinding
import com.snipsnap.shell.MidiLearn

/**
 * MIDI learn's panel, the same on every screen that offers it: one chip
 * per target, then FORGET; a line saying what to do next; one line per
 * target naming the control it has; and the last message heard, live.
 *
 * Tap a chip and it lights, waiting for a control: [onArm] with its
 * target, or with null when the lit chip is tapped again. FORGET drops the
 * lit target's control ([onForget]). The screen owns the [MidiLearn] and
 * the saving; this only draws what it is handed.
 *
 * @param targets each target's name and its chip, in the panel's order.
 * @param hint what the controls do here, under the status line.
 */
@Composable
internal fun MidiLearnPanel(
    targets: List<Pair<String, String>>,
    armed: String?,
    bindings: List<MidiBinding>,
    supported: Boolean,
    connected: Boolean,
    lastHeard: String?,
    hint: String,
    onArm: (String?) -> Unit,
    onForget: (String) -> Unit,
) {
    val scheme = LocalScheme.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((t, chip) in targets) {
                ActionButton(
                    chip,
                    scheme,
                    enabled = supported,
                    dimmed = armed != t,
                    modifier = Modifier.weight(1f).semantics { selected = armed == t },
                ) { onArm(if (armed == t) null else t) }
            }
            ActionButton(
                "FORGET",
                scheme,
                enabled = armed != null && bindings.any { it.target == armed },
                modifier = Modifier.weight(1.4f),
            ) { armed?.let(onForget) }
        }

        Spacer(Modifier.height(6.dp))

        val armedChip = targets.firstOrNull { it.first == armed }?.second
        TapeText(
            when {
                !supported -> "THIS PHONE HAS NO MIDI"
                !connected -> "PLUG IN A KEYBOARD TO LEARN"
                armedChip != null -> "MOVE A CONTROL FOR $armedChip"
                else -> "TAP A TARGET, THEN MOVE A CONTROL"
            },
            TapeType.pixel,
            scheme.ink.tape,
        )
        TapeText(hint, TapeType.pixel, scheme.ink.tape)
        for ((t, chip) in targets) {
            val bound = bindings.firstOrNull { it.target == t }
            TapeText("$chip  ${bound?.let { MidiLearn.describe(it.source) } ?: "-"}", TapeType.pixel, scheme.amber.tape)
        }
        TapeText("LAST  ${lastHeard ?: "-"}", TapeType.pixel, scheme.ink.tape)
    }
}
