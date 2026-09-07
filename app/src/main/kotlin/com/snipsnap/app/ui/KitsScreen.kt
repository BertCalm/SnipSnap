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
    /** ROOMS (YY5): what OUTSIDE measured and kept, beside the instruments; hold one to forget it into the bin. */
    rooms: List<Rooms.Room> = emptyList(),
    onForgetRoom: (Rooms.Room) -> Unit = {},
) {
    val scheme = LocalScheme.current
    var menuOpen by remember { mutableStateOf(false) }

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
                TapeText("THE SHELF", TapeType.lcdHeader, scheme.lcdInk.tape)
            }

            if (kits.isEmpty() && instruments.isEmpty() && rooms.isEmpty()) {
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
                        KitRow(entry, onOpen)
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
                    if (rooms.isNotEmpty()) {
                        item(key = "rooms-header") {
                            TapeText("ROOMS · OUTSIDE MEASURED THEM. ANY PAD PLAYS IN ONE.", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                        items(rooms, key = { "room:" + it.file.name }) { room ->
                            RoomRow(room, onForgetRoom)
                        }
                        item(key = "rooms-note") {
                            TapeText("HOLD A ROOM TO FORGET IT · THE BIN KEEPS IT ${Rooms.BIN_DAYS} DAYS", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
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
 * MUTATE card. Holding presses the row and reveals FORGET → BIN in the
 * bin's red; a tap anywhere lets go.
 */
@Composable
private fun RoomRow(room: Rooms.Room, onForget: (Rooms.Room) -> Unit) {
    val scheme = LocalScheme.current
    var armed by remember(room.file) { mutableStateOf(false) }
    val ageDays = ((System.currentTimeMillis() - room.measuredAt) / (24L * 60 * 60 * 1000)).toInt()
    val age = if (ageDays <= 0) "TODAY" else "$ageDays D AGO"
    val meta = "${room.lagMs.roundToInt()} MS · ${(room.confidence * 100).roundToInt()}% SURE · ${room.from.replace(':', ' ').uppercase(Locale.ROOT)} · $age"
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
                    .tapeClick { onForget(room) },
                contentAlignment = Alignment.Center,
            ) {
                TapeText("FORGET → BIN", TapeType.pixel, BIN_RED_GLOW)
            }
        } else {
            Box(Modifier.height(30.dp).lcdPanel(scheme).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                TapeText("%.1f s".format(Locale.ROOT, room.seconds), TapeType.lcdSmall, scheme.amber.tape)
            }
        }
    }
}

@Composable
private fun KitRow(entry: KitShelf.Entry, onOpen: (KitShelf.Entry) -> Unit) {
    val scheme = LocalScheme.current
    val kit = entry.kit
    Row(
        Modifier
            .fillMaxWidth()
            .raisedBevel(scheme)
            .tapeClick { onOpen(entry) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(kit.name, TapeType.markerBig, scheme.ink.tape)
            val tempo = kit.tempoBpm?.let { "  ·  %.0f BPM".format(it) } ?: ""
            TapeText("${kit.pads.size} PADS$tempo", TapeType.pixelSmall, scheme.ink2.tape)
        }
        // Every kit is a DRAFT until the export wizard (M5) records a dub
        // to the card; ON CARD status arrives with it.
        TapeText("DRAFT", TapeType.pixelSmall, scheme.ink2.tape)
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
                            TapeText("REROLLS", TapeType.pixelSmall, scheme.ink2.tape)
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
                PrimaryAction(label = "ARM TAPE", enabled = true, onClick = onArm)
            }
            Box(Modifier.weight(1f)) {
                PrimaryAction(label = "ARM INSIDE", enabled = true, onClick = onArmInside)
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
                TapeText("EJECT", TapeType.displayBig, BIN_RED_GLOW)
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
                TapeText("SNIP", TapeType.displayBig, scheme.amber.tape)
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
