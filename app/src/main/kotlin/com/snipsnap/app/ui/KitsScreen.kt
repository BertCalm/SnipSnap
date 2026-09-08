package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.StarterKits
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import com.snipsnap.shell.Rooms
import com.snipsnap.app.theme.pressedBevel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.snipsnap.kit.Names

/**
 * The tape shelf: every kit folder on the device, plus the FRESH TAPE
 * menu (the cold-start answer — the shelf is never uselessly empty when
 * six starters are one tap away).
 */
@Composable
fun KitsScreen(
    kits: List<KitShelf.Entry>,
    instruments: List<KitShelf.InstrumentEntry>,
    busy: Boolean,
    armed: Boolean,
    onOpen: (KitShelf.Entry) -> Unit,
    onOpenInstrument: (KitShelf.InstrumentEntry) -> Unit,
    onFresh: (StarterKits.Starter) -> Unit,
    onArm: () -> Unit,
    onArmInside: () -> Unit,
    onSnip: () -> Unit,
    onEject: () -> Unit,
    /** BACKUP (X3.3): every kit on one file, handed to the chooser. */
    onBackup: () -> Unit,
    /** SNIPS (Task 3): every catch on the phone, one list — play, assign to a pad, open in TAPE, or delete. */
    onSnips: () -> Unit = {},
    /**
     * True while a SNIPS row's → PAD is routing the user here to pick a kit
     * (see `App.kt`'s `pendingSnipAssign`) — swaps the header's own line for
     * a hint instead of the usual "THE SHELF", and adds a one-line nudge
     * toward what happens next. Every kit row's `onOpen` stays the same
     * callback either way; `App` is what decides what opening a kit means
     * while this is true.
     */
    assigningSnip: Boolean = false,
    /** ROOMS (YY5): what OUTSIDE measured and kept, beside the instruments; hold one to forget it into the bin. */
    rooms: List<Rooms.Room> = emptyList(),
    onForgetRoom: (Rooms.Room) -> Unit = {},
    /** The rooms in the bin, each with its days left; RESTORE brings one back. */
    binnedRooms: List<Rooms.Binned> = emptyList(),
    onRestoreRoom: (Rooms.Binned) -> Unit = {},
    /** DELETE (Task 4): a held kit row's DELETE, confirmed — into the 30-day bin, `KitShelf.deleteKit`'s promise. */
    onDeleteKit: (KitShelf.Entry) -> Unit = {},
    /** RENAME (Task 4): a held kit row's RENAME, confirmed with the typed name — `KitShelf.renameKit`'s collision fallback may freshen it. */
    onRenameKit: (KitShelf.Entry, String) -> Unit = { _, _ -> },
) {
    val scheme = LocalScheme.current
    var menuOpen by remember { mutableStateOf(false) }
    // DELETE/RENAME (Task 4): screen-level, not per-row — same shape as
    // SnipsScreen's own `confirmDelete`, so only one row's dialog is ever
    // up regardless of how many rows are armed at once.
    var confirmDeleteKit by remember { mutableStateOf<KitShelf.Entry?>(null) }
    var renameTarget by remember { mutableStateOf<KitShelf.Entry?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Layout.LCD_HEADER_H.dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TapeText(if (assigningSnip) "PICK A KIT FOR THIS SNIP" else "THE SHELF", TapeType.lcdHeader, scheme.lcdInk.tape)
            }
            if (assigningSnip) {
                TapeText("TAP A KIT, THEN LONG-PRESS AN EMPTY PAD.", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            }

            if (kits.isEmpty() && instruments.isEmpty() && rooms.isEmpty() && binnedRooms.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .lcdPanel(scheme)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .sunkenField(scheme)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(kits, key = { it.dir.name }) { entry ->
                        KitRow(
                            entry = entry,
                            busy = busy,
                            pickModeActive = assigningSnip,
                            onOpen = onOpen,
                            onRequestDelete = { confirmDeleteKit = it },
                            onRequestRename = { renameTarget = it },
                        )
                    }
                    // INSTRUMENTS: what MAKE INSTRUMENT and MAKE PAD left beside the kits, playable on KEYS.
                    if (instruments.isNotEmpty()) {
                        item(key = "instruments-header") {
                            TapeText("INSTRUMENTS · PLAY THEM ON KEYS", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                        items(instruments, key = { "instrument:" + it.sidecar.name }) { entry ->
                            InstrumentRow(entry, onOpenInstrument)
                        }
                    }
                    // ROOMS: what OUTSIDE measured and KEEP ROOM kept - any pad plays inside one through MUTATE ▸ ROOM.
                    if (rooms.isNotEmpty() || binnedRooms.isNotEmpty()) {
                        item(key = "rooms-header") {
                            TapeText("ROOMS · OUTSIDE MEASURED THEM. ANY PAD PLAYS IN ONE.", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                        items(rooms, key = { "room:" + it.file.name }) { room ->
                            RoomRow(room, busy, onForgetRoom)
                        }
                        if (rooms.isNotEmpty()) {
                            item(key = "rooms-note") {
                                TapeText("HOLD A ROOM TO FORGET IT · THE BIN KEEPS IT ${Rooms.BIN_DAYS} DAYS", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
                            }
                        }
                        // The bin's door on the phone: every forgotten room, its days
                        // left, and RESTORE - the same row TAKES + BIN draws for a pad.
                        if (binnedRooms.isNotEmpty()) {
                            item(key = "rooms-bin-header") {
                                TapeText("IN THE BIN · RESTORE BEFORE THE DAYS RUN OUT", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                            }
                            items(binnedRooms, key = { "binned:" + it.room.file.name }) { binned ->
                                BinnedRoomRow(binned, busy, onRestoreRoom)
                            }
                        }
                    }
                }
            }

            PrimaryAction(
                label = if (busy) "DUBBING…" else "FRESH TAPE",
                enabled = !busy,
                onClick = { menuOpen = true },
            )
            // BACKUP: the whole shelf as one file, out the share sheet -
            // the "new phone" story; the same file shared back in lands
            // every kit again.
            ActionButton(
                "BACKUP ▸ EVERY KIT, ONE FILE",
                scheme,
                enabled = !busy && kits.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = onBackup,
            )
            // SNIPS: always openable, even with zero snips yet (its own
            // empty state says so) — unlike BACKUP above, this isn't gated
            // on the shelf holding anything.
            ActionButton(
                "SNIPS ▸ EVERY CATCH, ONE LIST",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSnips,
            )
            ArmControl(
                armed = armed,
                onArm = onArm,
                onArmInside = onArmInside,
                onSnip = onSnip,
                onEject = onEject,
            )
        }

        if (menuOpen) {
            StarterMenu(
                onPick = { menuOpen = false; onFresh(it) },
                onDismiss = { menuOpen = false },
            )
        }

        confirmDeleteKit?.let { target ->
            KitDeleteConfirmDialog(
                onCancel = { confirmDeleteKit = null },
                onConfirm = { confirmDeleteKit = null; onDeleteKit(target) },
            )
        }
        renameTarget?.let { target ->
            KitRenameDialog(
                initialName = target.kit.name,
                onCancel = { renameTarget = null },
                onConfirm = { newName -> renameTarget = null; onRenameKit(target, newName) },
            )
        }
    }
}

@Composable
private fun InstrumentRow(entry: KitShelf.InstrumentEntry, onOpen: (KitShelf.InstrumentEntry) -> Unit) {
    val scheme = LocalScheme.current
    val i = entry.instrument
    Row(
        Modifier
            .fillMaxWidth()
            .raisedBevel(scheme)
            .tapeClick { onOpen(entry) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(i.name, TapeType.markerBig, scheme.ink.tape)
            val looped = if (i.zones.any { it.loopStartFrame > 0 }) "  ·  HOLDS" else ""
            TapeText("${i.zones.size} ${if (i.zones.size == 1) "ZONE" else "ZONES"}  ·  ROOT ${com.snipsnap.shell.KeysLayout.label(i.rootNote)}$looped", TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText("KEYS ▸", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/**
 * A kept room: its name, how it was measured, its length on an LCD.
 * Tapping does nothing - a room is not opened, it is used from a pad's
 * MUTATE card. Holding the words presses the row and reveals FORGET → BIN
 * in the bin's red; a tap on the words lets go. The gesture never sits
 * over the button.
 */
@Composable
private fun RoomRow(room: Rooms.Room, busy: Boolean, onForget: (Rooms.Room) -> Unit) {
    val scheme = LocalScheme.current
    var armed by remember(room.file) { mutableStateOf(false) }
    val ageDays = ((System.currentTimeMillis() - room.measuredAt) / (24L * 60 * 60 * 1000)).toInt()
    val age = if (ageDays <= 0) "TODAY" else "$ageDays D AGO"
    // Built from the parts the sidecar actually held: a room whose sidecar
    // was lost reads UNMEASURED and its age, never "0 MS · 0% SURE ·  ·".
    val meta = buildList {
        if (room.lagMs > 0f || room.confidence > 0f) {
            add("${room.lagMs.roundToInt()} MS")
            add("${(room.confidence * 100).roundToInt()}% SURE")
        } else {
            add("UNMEASURED")
        }
        if (room.from.isNotBlank()) add(room.from.replace(':', ' ').uppercase(Locale.ROOT))
        add(age)
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (armed) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The hold (and the tap that lets go) live on the words, so the
        // FORGET button beside them is never under a gesture that could
        // swallow its tap.
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .pointerInput(room.file) {
                    detectTapGestures(onLongPress = { armed = true }, onTap = { armed = false })
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TapeText(room.name, TapeType.markerBig, scheme.ink.tape)
            TapeText(meta, TapeType.pixelSmall, scheme.ink2.tape)
        }
        if (armed) {
            Box(
                Modifier
                    .width(124.dp)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .border(2.dp, Brush.linearGradient(listOf(BIN_RED_GLOW, BIN_RED_BORDER)), RoundedCornerShape(4.dp))
                    .let { if (!busy) it.tapeClick { onForget(room) } else it },
                contentAlignment = Alignment.Center,
            ) {
                TapeText("FORGET → BIN", TapeType.pixel, if (busy) scheme.ink3.tape else BIN_RED_GLOW)
            }
        } else {
            Box(Modifier.height(30.dp).lcdPanel(scheme).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                TapeText("%.1f s".format(Locale.ROOT, room.seconds), TapeType.lcdSmall, scheme.amber.tape)
            }
        }
    }
}

/**
 * A room in the bin: its name, the days it has left (the last two in
 * `warn`, as TAKES + BIN counts a pad down), and RESTORE in the LCD's
 * second colour. The row is the bin's own - LCD-dark, one line - so a
 * forgotten room never reads as one still on the shelf.
 */
@Composable
private fun BinnedRoomRow(binned: Rooms.Binned, busy: Boolean, onRestore: (Rooms.Binned) -> Unit) {
    val scheme = LocalScheme.current
    val daysLeft = binned.daysLeft(System.currentTimeMillis())
    val dayColor = if (daysLeft <= 2) scheme.warn.tape else scheme.amber.tape
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
        TapeText(binned.room.name, TapeType.marker, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
        TapeText("${daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                .let { if (!busy) it.tapeClick { onRestore(binned) } else it }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
        }
    }
}

/**
 * A kit on the shelf: tap opens it, holding the row arms DELETE + RENAME in
 * place of the DRAFT chip — `RoomRow`'s own long-press/tap shape, but the
 * `pointerInput` sits on the whole Row (not a child `Column` beside a
 * sibling button, as `RoomRow` can afford since a room row has no `onOpen`
 * at all): with `onOpen` gone from a plain `tapeClick` and living inside
 * this same `detectTapGestures`'s `onTap` instead, a tap disarms when armed
 * and opens the kit otherwise, and DELETE/RENAME's own child `tapeClick`s
 * still win for their own bounds (a descendant's consumed tap never bubbles
 * to this Row's `onTap`) — the same reason a trailing icon button inside a
 * clickable list row never also fires the row's own click.
 *
 * [pickModeActive] (SNIPS → PAD's `assigningSnip`, Task 3): long-pressing a
 * kit row while the user is mid-pick would arm a red DELETE right beside
 * the very kit they're trying to select — confusing at best, right next to
 * the header's own "TAP A KIT, THEN LONG-PRESS AN EMPTY PAD" hint that
 * primes exactly that gesture. While true, this row falls back to its
 * pre-Task-4 shape entirely: a plain `tapeClick` that only opens the kit,
 * no `pointerInput`/long-press at all, so DELETE/RENAME are simply
 * unreachable for the duration of a pick rather than merely hidden.
 */
@Composable
private fun KitRow(
    entry: KitShelf.Entry,
    busy: Boolean,
    pickModeActive: Boolean,
    onOpen: (KitShelf.Entry) -> Unit,
    onRequestDelete: (KitShelf.Entry) -> Unit,
    onRequestRename: (KitShelf.Entry) -> Unit,
) {
    val scheme = LocalScheme.current
    val kit = entry.kit
    var armed by remember(entry.dir) { mutableStateOf(false) }
    val revealActions = armed && !pickModeActive
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (revealActions) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .then(
                if (pickModeActive) {
                    Modifier.tapeClick { onOpen(entry) }
                } else {
                    Modifier.pointerInput(entry.dir) {
                        detectTapGestures(
                            onLongPress = { armed = true },
                            onTap = { if (armed) armed = false else onOpen(entry) },
                        )
                    }
                },
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(kit.name, TapeType.markerBig, scheme.ink.tape)
            val tempo = kit.tempoBpm?.let { "  ·  %.0f BPM".format(it) } ?: ""
            TapeText("${kit.pads.size} PADS$tempo", TapeType.pixelSmall, scheme.ink2.tape)
        }
        if (revealActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .let { if (!busy) it.tapeClick { onRequestRename(entry) } else it }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("RENAME", TapeType.pixel, if (busy) scheme.ink3.tape else scheme.amber.tape)
                }
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .border(2.dp, Brush.linearGradient(listOf(BIN_RED_GLOW, BIN_RED_BORDER)), RoundedCornerShape(4.dp))
                        .let { if (!busy) it.tapeClick { onRequestDelete(entry) } else it }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("DELETE", TapeType.pixel, if (busy) scheme.ink3.tape else BIN_RED_GLOW)
                }
            }
        } else {
            // Every kit is a DRAFT until the export wizard (M5) records a dub
            // to the card; ON CARD status arrives with it.
            TapeText("DRAFT", TapeType.pixelSmall, scheme.ink2.tape)
        }
    }
}

/**
 * "DELETE THIS KIT? OFF THE SHELF NOW, GONE FOR GOOD IN 30 DAYS." —
 * deliberately does NOT say "the bin": this app's two other bins (TAKES +
 * BIN, ROOMS's own FORGET → BIN) are both user-restorable and visible
 * (RESTORE buttons, an "EMPTY THE BIN NOW" action), and a deleted kit's own
 * `.bin` folder has neither — no restore UI, no listing, no early-empty.
 * Calling it "the bin" against the rest of the app's own vocabulary would
 * invite exactly that false expectation (whole-branch review finding); this
 * says what actually happens instead. Same scrim + raisedBevel +
 * tap-swallowing shape as `SnipsScreen.kt`'s own `DeleteConfirmDialog` and
 * this file's own `StarterMenu` above — duplicated, not hoisted, per the
 * house convention stated in `SnipsScreen.kt`'s `HeaderChip`.
 */
@Composable
private fun KitDeleteConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("DELETE THIS KIT? OFF THE SHELF NOW, GONE FOR GOOD IN 30 DAYS.", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                        .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
                        .tapeClick(onConfirm)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("DELETE", TapeType.pixel, BIN_RED_GLOW)
                }
            }
        }
    }
}

/**
 * A plain single-line rename field — no rename-input pattern exists
 * elsewhere in the app to copy (FRESH TAPE auto-names through
 * `KitShelf.freshName`, never asking), so this is `BasicTextField` styled to
 * the row's own theme rather than Material's `TextField`. A modal dialog
 * rather than an inline field in the row itself: the row lives inside a
 * `LazyColumn` under a `Column` with no `imePadding`, so an inline field
 * risks the keyboard covering it; a centered dialog (this file's own
 * `StarterMenu`/`KitDeleteConfirmDialog` shape) sidesteps that entirely.
 * RENAME is disabled while the typed name fails `Names.isMpcSafe` — the
 * same refusal `KitShelf.renameKit` would give, surfaced before the tap
 * instead of after.
 */
@Composable
private fun KitRenameDialog(initialName: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val scheme = LocalScheme.current
    var name by remember { mutableStateOf(initialName) }
    val safe = Names.isMpcSafe(name)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("RENAME KIT", TapeType.lcdSmall, scheme.ink.tape)
            Box(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!safe) {
                TapeText(
                    "NAMES CAN'T HOLD / \\ : * ? \" < > | OR END IN A DOT/SPACE.",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 2,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("RENAME", scheme, enabled = safe, modifier = Modifier.weight(1f), onClick = { onConfirm(name) })
            }
        }
    }
}

/** The primary action per the handoff: 52dp, rimmed, dark fill. */
@Composable
fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    val rim =
        if (scheme.id == SchemeId.OILSLICK) {
            Modifier.border(2.dp, oilslickSweep(), RoundedCornerShape(6.dp))
        } else {
            Modifier.border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Layout.PRIMARY_ACTION_H.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
            .then(rim)
            .then(if (enabled) Modifier.tapeClick(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.displayBig,
            if (enabled) scheme.lcdInk.tape else scheme.lcdInk.tape.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StarterMenu(onPick: (StarterKits.Starter) -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .raisedBevel(scheme)
                // Swallow taps so the scrim's dismiss doesn't fire through.
                .tapeClick { }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapeText("FRESH TAPE", TapeType.display, scheme.ink.tape)
            for (starter in StarterKits.ALL) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .sunkenField(scheme)
                        .tapeClick { onPick(starter) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TapeText(starter.displayName, TapeType.display, scheme.ink.tape)
                        if (starter.seeded) {
                            Spacer(Modifier.width(6.dp))
                            TapeText("TAPS REROLL", TapeType.pixelSmall, scheme.ink2.tape)
                        }
                    }
                    TapeText(starter.blurb, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                }
            }
        }
    }
}

/**
 * The capture session's entry point — the shelf's third way a kit begins,
 * alongside FRESH TAPE (machine-invented) and IMPORT (brought in): a
 * capture. Idle: two primary-styled buttons, ARM TAPE (the room, through
 * the mic) and ARM INSIDE (another app's audio, with the projection
 * consent). Armed, whichever source: EJECT in the
 * bin-red pair ([BIN_RED_BORDER]/[BIN_RED_GLOW], `TakesBinScreen`'s own
 * convention — a session-ending action reads as "red" even in a scheme
 * with no red anywhere else) beside a small in-app SNIP; the notification
 * action is the out-of-app path, this is the in-app one.
 */
@Composable
private fun ArmControl(
    armed: Boolean,
    onArm: () -> Unit,
    onArmInside: () -> Unit,
    onSnip: () -> Unit,
    onEject: () -> Unit,
) {
    val scheme = LocalScheme.current
    if (!armed) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryAction(label = "LISTEN", enabled = true, onClick = onArm)
            }
            Box(Modifier.weight(1f)) {
                PrimaryAction(label = "LISTEN INSIDE ▸ OTHER APPS' AUDIO", enabled = true, onClick = onArmInside)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordingIndicator()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .weight(2f)
                    .height(Layout.PRIMARY_ACTION_H.dp)
                    .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                    .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(6.dp))
                    .tapeClick(onEject),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("STOP LISTENING", TapeType.displayBig, BIN_RED_GLOW)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(Layout.PRIMARY_ACTION_H.dp)
                    .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                    .border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                    .tapeClick(onSnip),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("SNIP ▸ KEEP LAST 60s", TapeType.displayBig, scheme.amber.tape)
            }
        }
    }
}

/**
 * The armed session's liveness readout — a live input-level bar paired
 * with a wall-clock elapsed counter, so a silent room can't be misread as
 * a dead indicator: "ARMED" alone doesn't say whether anything is still
 * happening, but "flat meter + a ticking counter" reads unmistakably as
 * *recording, hearing nothing*, which is exactly the diagnostic a truly
 * silent mic should produce.
 *
 * [MicSessionService.level] is collected right here, not hoisted up into
 * [ArmControl] — it updates at ~21 Hz (`READ_BLOCK_FRAMES` @ 44.1kHz), so
 * this is the smallest composable scope that should recompose on every
 * tick.
 */
@Composable
private fun RecordingIndicator() {
    val scheme = LocalScheme.current
    val level by MicSessionService.level.collectAsState()

    // The counter anchors to MicSessionService.armedAtElapsedRealtime (a
    // SystemClock timestamp set once, at ARM) rather than counting its own
    // ticks — a locally-counted "start at 0, ++ each second" would reset
    // to 00:00 every time this composable remounts (navigate off the
    // shelf and back while still armed), which reads as a lie about a
    // session that's actually still rolling. Recomputed once up front so a
    // remount shows the true elapsed time immediately, not after the
    // first second-long delay.
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val anchor = MicSessionService.armedAtElapsedRealtime
            // anchor == 0L means nothing has set it in this process — the
            // service's own default, not a real arm time. Reading a real
            // elapsedRealtime() against that default would read as device
            // uptime (hours), not session time, so treat it as "unknown,
            // show zero" instead of doing that subtraction.
            elapsedSeconds = if (anchor == 0L) {
                0
            } else {
                ((SystemClock.elapsedRealtime() - anchor) / 1000L).toInt().coerceAtLeast(0)
            }
            delay(1000)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .sunkenField(scheme)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LevelBar(level, scheme, Modifier.weight(1f).fillMaxHeight())
        TapeText(formatElapsed(elapsedSeconds), TapeType.pixelSmall, scheme.amber.tape)
    }
}

/** [level] is the raw 0f..1f peak; drawn with a sqrt gamma, which reads better than raw linear peak at low levels. */
@Composable
private fun LevelBar(level: Float, scheme: Scheme, modifier: Modifier = Modifier) {
    val filled = sqrt(level.coerceIn(0f, 1f))
    Canvas(modifier) {
        drawRect(color = scheme.field.tape, size = size)
        if (filled > 0f) {
            drawRect(color = scheme.amber.tape, size = Size(size.width * filled, size.height))
        }
    }
}

/** mm:ss, uncapped past 59 minutes (RING_SECONDS is 60s; a session runs far longer than the ring holds). */
private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

// Duplicated, not hoisted — TakesBinScreen.kt's own BIN_RED_BORDER/GLOW
// comment states the house convention explicitly: do it if a clean
// one-liner, else duplicate with a comment. BIN red is deliberately
// constant across every scheme so a session-ending action (EJECT here,
// EMPTY THE BIN there) reads as "red" regardless of the active scheme.
private val BIN_RED_BORDER = Color(0xFF6A2020)
private val BIN_RED_GLOW = Color(0xFFC86050)
