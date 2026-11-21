package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.shell.BoroughStackSwap
import com.snipsnap.shell.Copy
import com.snipsnap.synth.BoroughKits

/** A swap waiting on the player: the stack asked for, and the slots of A13 to A16 that changed. */
data class PercStackAsk(val stack: BoroughKits.Stack, val changedSlots: List<Int>)

/**
 * The PERC STACK row on the KIT screen, drawn only for a BOROUGH kit (docs/BOROUGH.md, round 3.2): HANDS,
 * TAMBOURINE, BELLS and TOPS as segments, the stack on A13 to A16 selected. Tapping another asks for it with
 * [onPick] (replaceChanges false); the caller runs `BoroughStackSwap.swap` off the main thread and, when the pads
 * were changed, hands back [ask], which this draws as the confirm: REPLACE asks again with replaceChanges true,
 * CANCEL and Back call [onKeep]. Which kit, which stack and what counts as changed are `BoroughStackSwap`'s, read
 * from the kit's pads; the work is the caller's.
 */
@Composable
internal fun PercStackRow(
    kit: Kit,
    busy: Boolean,
    ask: PercStackAsk?,
    onPick: (stack: BoroughKits.Stack, replaceChanges: Boolean) -> Unit,
    onKeep: () -> Unit,
) {
    val choices = remember(kit) { BoroughStackSwap.choices(kit) } ?: return
    val scheme = LocalScheme.current
    Column(Modifier.fillMaxWidth().testTag("perc-stack-row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText(Copy.PERC_STACK, TapeType.pixelSmall, scheme.ink2.tape, Modifier.padding(top = 4.dp))
        // Two by two: TAMBOURINE in monospace is wider than a quarter of a phone, and a word broken over two lines
        // reads worse than a second row.
        for (pair in choices.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in pair) {
                    // The selected segment is the stack already there: tapping it is the picker at rest, not a refusal.
                    SegmentButton(choice.label, active = choice.selected, modifier = Modifier.weight(1f), enabled = !busy) {
                        if (!choice.selected) onPick(choice.stack, false)
                    }
                }
            }
        }
    }
    if (ask != null) PercStackConfirm(ask, onReplace = { onPick(ask.stack, true) }, onCancel = onKeep)
}

/** "Replace your changes on A13–A16?", the pads that changed and where they go, then CANCEL and REPLACE. */
@Composable
private fun PercStackConfirm(ask: PercStackAsk, onReplace: () -> Unit, onCancel: () -> Unit) {
    val scheme = LocalScheme.current
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.widthIn(max = 400.dp).fillMaxWidth().raisedBevel(scheme).padding(14.dp).testTag("perc-stack-ask"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(Copy.PERC_STACK_ASK, TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            TapeText(Copy.percStackChanged(ask.changedSlots), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), tier = ButtonTier.Text, onClick = onCancel)
                ActionButton(
                    Copy.PERC_STACK_REPLACE, scheme, enabled = true, modifier = Modifier.weight(1f),
                    tier = ButtonTier.Primary, onClick = onReplace,
                )
            }
        }
    }
}
