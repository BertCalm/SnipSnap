package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.BinRedOnLcd
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Ages
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Places
import com.snipsnap.shell.ShelfModel
import java.util.Locale

/*
 * PR 6b (`docs/superpowers/specs/2026-10-27-ui-clarity-design.md` §7.4, the
 * project-row line): a project row gets a visible `⋮` that opens RENAME,
 * DELETE and REMOVE A KIT. The spec's law is that every hold has a visible
 * twin; projects never had the hold, so this is the first way to do any of
 * the three. All taps — no long-press anywhere here.
 *
 * Kept out of `KitsScreen.kt` on purpose: that file's composables sit near
 * the dex 256-register cliff, so the row, the door and the three small
 * sheets live here as separate composables and `KitsScreen` only holds one
 * state variable and makes one call.
 */

/**
 * One project on the shelf (plan §3.1): its name, then `2 KITS · 94 BPM ·
 * EDITED 2 HR AGO` — the model's own subtitle plus the age the kit rows
 * already show, so the two lists read the same way. Tapping the words
 * opens it; `App` decides what opening means (the active kit onto PADS).
 * The row of the project that is [isOpen] wears the selected convention -
 * the accent border (`pressedBevel`), the `selected` semantic, and OPEN NOW
 * where the rest say OPEN ▸ - so a list of sixteen that all read the same
 * names the one you are in.
 *
 * The `⋮` ([ProjectMoreDoor]) sits beside the words rather than over them,
 * the way `RoomRow` keeps its button clear of its gesture: two clickables
 * nested in one merged node would leave a screen reader only one of them.
 */
@Composable
internal fun ProjectRow(
    row: ShelfModel.ProjectRow,
    isOpen: Boolean,
    busy: Boolean,
    onOpen: (ShelfModel.ProjectRow) -> Unit,
    onMore: (ShelfModel.ProjectRow) -> Unit,
) {
    val scheme = LocalScheme.current
    val age = Ages.agoOrNull(row.editedAtMillis, System.currentTimeMillis())
    val facts = listOfNotNull(row.subtitle, age?.let { "EDITED $it" }).joinToString("  ·  ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .let { if (isOpen) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .semantics { this.selected = isOpen },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .tapeClick(label = "OPEN PROJECT ${row.name.uppercase(Locale.ROOT)}", enabled = !busy) { onOpen(row) }
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TapeText(row.name, TapeType.markerBig, scheme.ink.tape)
                TapeText(facts, TapeType.pixelSmall, scheme.ink2.tape)
            }
            TapeText(
                if (isOpen) "OPEN NOW" else "OPEN ▸",
                TapeType.pixelSmall,
                if (isOpen) scheme.ink.tape else scheme.ink2.tape,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        ProjectMoreDoor(row.name, enabled = !busy) { onMore(row) }
    }
}

/** The row's `⋮`: a bordered 48dp square, named for its project, dimmed (not dropped) while the shelf is busy. */
@Composable
private fun ProjectMoreDoor(projectName: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .widthIn(min = Layout.MIN_HIT_TARGET.dp)
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = "MORE FOR PROJECT ${projectName.uppercase(Locale.ROOT)}", enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText("⋮", TapeType.markerBig, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}

private enum class OpsStage { MENU, RENAME, DELETE, DROP }

/**
 * What the `⋮` opens, for the project [row]: the menu, then whichever of
 * RENAME, DELETE or REMOVE A KIT was tapped. Each action closes the whole
 * thing (`onDismiss`) before it fires, and Back or a tap outside closes it
 * without firing anything. [taken] is the folder names REROLL avoids.
 */
@Composable
internal fun ProjectOpsHost(
    row: ShelfModel.ProjectRow,
    taken: Set<String>,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDropKit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by remember(row.dir) { mutableStateOf(OpsStage.MENU) }
    BackHandler(onBack = onDismiss)
    when (stage) {
        OpsStage.MENU -> ProjectMenuCard(
            row,
            onRename = { stage = OpsStage.RENAME },
            onDelete = { stage = OpsStage.DELETE },
            onDrop = { stage = OpsStage.DROP },
            onDismiss = onDismiss,
        )
        OpsStage.RENAME -> KitRenameDialog(
            initialName = row.name,
            taken = taken,
            onCancel = onDismiss,
            onConfirm = { name -> onDismiss(); onRename(name) },
            title = "RENAME PROJECT",
        )
        OpsStage.DELETE -> ProjectDeleteCard(onCancel = onDismiss, onConfirm = { onDismiss(); onDelete() })
        OpsStage.DROP -> ProjectDropCard(row, onPick = { kit -> onDismiss(); onDropKit(kit) }, onDismiss = onDismiss)
    }
}

/** A scrim and a raised card: the shape the kit dialogs share, with a heading line. */
@Composable
private fun OpsCard(heading: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(label = "CANCEL", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it does not fall through to the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TapeText(heading, TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            content()
        }
    }
}

/**
 * The project row's menu, as a [SheetMenu] titled with the project's name: RENAME,
 * DELETE and REMOVE A KIT, each a name and the line under it. REMOVE A KIT is
 * disabled, not hidden, when there is nothing to take out. Each row keeps the accessible
 * name that says which project it acts on.
 */
@Composable
private fun ProjectMenuCard(
    row: ShelfModel.ProjectRow,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDrop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = row.name.uppercase(Locale.ROOT)
    SheetMenu(name, onDismiss) {
        SheetRow(Places.PROJECT_RENAME, Places.PROJECT_RENAME_DESC, onRename, accessibilityLabel = "RENAME PROJECT $name")
        SheetRow(Places.PROJECT_DELETE, Places.PROJECT_DELETE_DESC, onDelete, accessibilityLabel = "DELETE PROJECT $name")
        SheetRow(
            Places.PROJECT_DROP,
            Places.PROJECT_DROP_DESC,
            onDrop,
            enabled = row.project.kits.isNotEmpty(),
            accessibilityLabel = "REMOVE A KIT FROM PROJECT $name",
        )
    }
}

/** The DELETE confirm: what goes, what stays, how to get it back ([Copy.PROJECT_DELETE_CONFIRM]). */
@Composable
private fun ProjectDeleteCard(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalScheme.current
    OpsCard(Copy.PROJECT_DELETE_CONFIRM, onCancel) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), tier = ButtonTier.Text, onClick = onCancel)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                    .border(2.dp, scheme.binLcd.tape, RoundedCornerShape(4.dp))
                    .tapeClick(label = "DELETE", onClick = onConfirm)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("DELETE", TapeType.pixel, BinRedOnLcd)
            }
        }
    }
}

/** REMOVE A KIT: one button per kit the project holds; the tap takes it out, the kit stays on the shelf. */
@Composable
private fun ProjectDropCard(row: ShelfModel.ProjectRow, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    val project = row.name.uppercase(Locale.ROOT)
    OpsCard(Copy.PROJECT_DROP_SHEET, onDismiss) {
        Column(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (kit in row.project.kits) {
                ActionButton(
                    kit,
                    scheme,
                    enabled = true,
                    accessibilityLabel = "REMOVE KIT ${kit.uppercase(Locale.ROOT)} FROM PROJECT $project",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(kit) },
                )
            }
        }
        ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), tier = ButtonTier.Text, onClick = onDismiss)
    }
}

/**
 * A project asleep in the bin, in the shape of `DeletedKitRow`: its name,
 * its kits and age ([ageText], that file's own helper's words), the days
 * left, and a RESTORE.
 */
@Composable
internal fun DeletedProjectRow(row: KitShelf.BinnedProject, ageText: String, busy: Boolean, onRestore: () -> Unit) {
    val scheme = LocalScheme.current
    val dayColor = if (row.daysLeft <= 2) scheme.warn.tape else scheme.amber.tape
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(5.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(row.name, TapeType.marker, scheme.lcdInk.tape, maxLines = 1)
            TapeText(
                "PROJECT  ·  ${row.kitCount} ${if (row.kitCount == 1) "KIT" else "KITS"}  ·  $ageText",
                TapeType.pixelSmall,
                scheme.lcdInk2,
            )
        }
        TapeText("${row.daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                .tapeClick(label = "RESTORE PROJECT ${row.name.uppercase(Locale.ROOT)}", enabled = !busy, onClick = onRestore)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
        }
    }
}
