package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.EffectsMenu
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme

/**
 * "Change the sound": the pad's effects as a list sorted by what you want (direction B of the November
 * UX pass, docs/UX_PASS_2026_11.md). START HERE's drum starters first, then one closed section per
 * group whose closed line names what is inside, then the two pages of their own (EFFECT CHAIN, SHAPE).
 * Each row is a name, one line saying what it does, and ON with its AMT when the pad carries it.
 *
 * Data only from [EffectsMenu]; a tap hands the row's [EffectsMenu.Target] back to the editor, which
 * routes it through the same door its chip, pedal line, board or box already uses. A file of its own so
 * the editor's composable stays under the dex register ceiling.
 *
 * Try before you keep (fix e): an effect or a pedal row ([EffectsMenu.tries]) is heard first. The
 * editor plays it from a scratch copy and names it in [hearing]; that row wears the pressed bevel and
 * HEARING, and a bar right under it, where the finger is, offers KEEP and CANCEL.
 */
@Composable
internal fun EffectsList(
    /** What the pad carries now ([EffectsMenu.onNow]): a lit row reads its state. */
    onNow: Map<EffectsMenu.Target, String>,
    /** The stepper's name, AMT or which effect it moves, and whether it has a target at all. */
    amountLabel: String,
    amountEnabled: Boolean,
    amountFraction: Float,
    amountText: String,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
    onPick: (EffectsMenu.Target) -> Unit,
    onAmountChange: (Float) -> Unit,
    onAmountCommit: () -> Unit,
    /** The row being heard and not yet kept, or null. */
    hearing: EffectsMenu.Target? = null,
    onKeep: () -> Unit = {},
    onCancelTry: () -> Unit = {},
) {
    // One group open at a time, like the editor's boxes; all closed when the page opens.
    var openGroup by remember { mutableStateOf<String?>(null) }
    // START HERE repeats rows that also sit in a group: the keep bar goes under the copy that was tapped.
    var tappedWhere by remember { mutableStateOf<String?>(null) }
    val pick = { where: String, target: EffectsMenu.Target ->
        tappedWhere = where
        onPick(target)
    }
    val bar: @Composable (EffectsMenu.Entry, String) -> Unit = { entry, where ->
        if (hearing == entry.target && tappedWhere == where) KeepBar(entry.name, scheme, busy, onKeep, onCancelTry)
    }
    Column(Modifier.fillMaxWidth().testTag("effects-list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // AMT moves the one effect the pad last took, the same stepper the buttons below carry.
        StepperSlider(
            label = amountLabel,
            fraction = amountFraction,
            valueText = amountText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy && amountEnabled,
            onFractionChange = onAmountChange,
            onFractionCommit = onAmountCommit,
            labelWidth = if (amountLabel.length > 3) 96.dp else 44.dp,
            accessibilityLabel = "$amountLabel, effects list",
        )
        TapeText(
            EffectsMenu.START_HERE_HEADING,
            TapeType.pixelSmall,
            scheme.ink3.tape,
            Modifier.fillMaxWidth().semantics { heading() },
        )
        for (entry in EffectsMenu.START_HERE) {
            EffectRow(entry, onNow[entry.target], padColor, scheme, busy, "start", hearing == entry.target) { pick("start", it) }
            bar(entry, "start")
        }
        for (group in EffectsMenu.GROUPS) {
            GroupBox(
                legend = group.heading,
                summary = group.summary,
                open = openGroup == group.heading,
                onToggle = { openGroup = if (openGroup == group.heading) null else group.heading },
                scheme = scheme,
                enabled = !busy,
                // A group that holds a lit row says so while closed: the pad's effect is never hidden.
                summaryColor = if (group.entries.any { it.target in onNow }) scheme.accent.tape else scheme.ink2.tape,
            ) {
                for (entry in group.entries) {
                    EffectRow(entry, onNow[entry.target], padColor, scheme, busy, "group", hearing == entry.target) { pick("group", it) }
                    bar(entry, "group")
                }
            }
        }
        for (entry in EffectsMenu.PAGES) {
            EffectRow(entry, onNow[entry.target], padColor, scheme, busy, "page", false) { pick("page", it) }
        }
    }
}

/** One row of the list: the name, its line, and ON with the AMT when lit. */
@Composable
private fun EffectRow(
    entry: EffectsMenu.Entry,
    state: String?,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
    /** Where the row sits ("start", "group", "page"): START HERE repeats a group's row, so the test tag says which. */
    where: String,
    /** Being heard, not yet kept: the pressed bevel and HEARING, whatever the pad carries. */
    heard: Boolean,
    onPick: (EffectsMenu.Target) -> Unit,
) {
    val lit = state != null && !heard
    val shown = if (heard) EffectsMenu.HEARING else state
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .then(if (heard) Modifier.pressedBevel(scheme) else Modifier.raisedBevel(scheme, fill = if (lit) padColor.copy(alpha = 0.85f) else null))
            .semantics { selected = lit || heard }
            .tapeClick(label = "${entry.name}, ${entry.line}${shown?.let { ", $it" } ?: ""}", enabled = !busy) { onPick(entry.target) }
            .testTag("effect-$where-${entry.name}")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(entry.name, TapeType.pixel, if (busy) scheme.ink3.tape else if (lit) scheme.titleInk.tape else scheme.ink.tape)
            TapeText(entry.line, TapeType.pixelSmall, if (lit) scheme.titleInk.tape else scheme.ink2.tape, maxLines = 2)
        }
        if (shown != null) {
            Spacer(Modifier.width(8.dp))
            TapeText(shown, TapeType.pixelSmall, if (lit) scheme.titleInk.tape else scheme.accent.tape)
        }
    }
}

/** Under the row being heard: the pad is unchanged until KEEP, which applies it; CANCEL lets it go. */
@Composable
private fun KeepBar(name: String, scheme: Scheme, busy: Boolean, onKeep: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("effect-keep-bar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText(EffectsMenu.TRY_LINE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton(EffectsMenu.keepLabel(name), scheme, enabled = !busy, lit = true, modifier = Modifier.weight(2f).testTag("effect-keep"), onClick = onKeep)
            ActionButton(EffectsMenu.TRY_CANCEL, scheme, enabled = !busy, modifier = Modifier.weight(1f).testTag("effect-cancel"), onClick = onCancel)
        }
    }
}
