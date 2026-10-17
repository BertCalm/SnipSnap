package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.BinRedOnLcd
import com.snipsnap.app.theme.amberOnChrome
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Bench
import com.snipsnap.shell.DockModel
import com.snipsnap.shell.Door
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Location
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Navigation
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.Place
import com.snipsnap.shell.Places
import com.snipsnap.shell.Stage
import com.snipsnap.audio.SilenceWatch
import kotlin.math.sqrt

/*
 * The frame of the four-place shape (`docs/SCREEN_PLAN_2026_10.md` §1):
 * the place row, the stage row, the dock, and the gear. Everything here
 * draws from two `:shell` models, `Navigation` and `DockModel`, and
 * decides nothing itself — the models are where the shape is tested.
 */

/**
 * [Navigation], observed. The model is a plain class with no notion of
 * Compose, so this mirrors its [location] into snapshot state inside the
 * same call that moves it; the composition reads the mirror and redraws.
 * The mirror is a cache for observation, not a second navigation state —
 * nothing writes it except the methods below, each of which writes the
 * model first and copies second.
 */
class NavState(private val model: Navigation = Navigation()) {
    var location: Location by mutableStateOf(model.location)
        private set

    /**
     * PADS' state (`Navigation.pads`), observed the same way: bank,
     * selected pad, open box. Written through here so the model and the
     * composition move together, and reset together when the kit changes
     * ([resetPads]) — a bank or a selection is per kit, and a stage switch
     * is not a kit change (plan §3.3, the J20–J26 class).
     */
    private val padsBankState = mutableStateOf(model.pads.bank)
    var padsBank: Int
        get() = padsBankState.value
        set(value) {
            model.pads.bank = value
            padsBankState.value = value
        }

    private val padsSelectedState = mutableStateOf(model.pads.selectedSlot)
    var padsSelectedSlot: Int?
        get() = padsSelectedState.value
        set(value) {
            model.pads.selectedSlot = value
            padsSelectedState.value = value
        }

    private val padsOpenBoxState = mutableStateOf(model.pads.openBox)
    var padsOpenBox: PadSheetBoxes.Box?
        get() = padsOpenBoxState.value
        set(value) {
            model.pads.openBox = value
            padsOpenBoxState.value = value
        }

    /** A different kit on the grid: bank A, nothing selected, every box closed. */
    fun resetPads() {
        padsBank = 0
        padsSelectedSlot = null
        padsOpenBox = null
    }

    /** The other per-place records, straight through, until their steps of the plan observe them. */
    val beat get() = model.beat
    val tape get() = model.tape
    val make get() = model.make

    val backChip: String? get() = model.backChip
    val canGoBack: Boolean get() { location; return model.canGoBack }

    fun go(place: Place) = sync { model.go(place) }
    fun stage(stage: Stage) = sync { model.stage(stage) }
    fun open(bench: Bench) = sync { model.open(bench) }
    fun closeBench() = sync { model.closeBench() }
    fun take(door: Door) = sync { model.take(door) }
    fun goTo(target: Location) = sync { model.goTo(target) }

    /** The system Back: true when it moved. */
    fun back(): Boolean {
        val moved = model.back()
        location = model.location
        return moved
    }

    private inline fun sync(move: () -> Location) {
        move()
        location = model.location
    }
}

@Composable
fun rememberNavigation(): NavState = remember { NavState() }

/**
 * [DockModel], observed, the same way [NavState] observes [Navigation]:
 * every write goes through here and copies the model's fields into
 * snapshot state after it. [now] is the line and its door together, as
 * the model holds them — the door can never outlive the words it was
 * offered with, because they are one value (J10/J44).
 */
class DockState(private val model: DockModel = DockModel()) {
    var now: DockModel.Now by mutableStateOf(model.now)
        private set

    // The two regions the model exposes as plain vars: written through here
    // so the model and the mirror move together.
    private val transportState = mutableStateOf(model.transport)
    var transport: DockModel.Transport
        get() = transportState.value
        set(value) {
            model.transport = value
            transportState.value = value
        }

    private val captureState = mutableStateOf(model.capture)
    var capture: DockModel.Capture
        get() = captureState.value
        set(value) {
            model.capture = value
            captureState.value = value
        }

    fun say(text: String) {
        model.say(text)
        now = model.now
    }

    fun offer(text: String, door: Door) {
        model.offer(text, door)
        now = model.now
    }

    fun take(): Door? {
        val door = model.take()
        now = model.now
        return door
    }

    fun clear() {
        model.clear()
        now = model.now
    }

    val captureLabel: String get() { capture; return model.captureLabel }
    val transportLabel: String get() { transport; return model.transportLabel }
}

@Composable
fun rememberDock(): DockState = remember { DockState() }

/**
 * The four places, always visible, never scrolling. The selected place is
 * the pressed chip; the others are raised. Each chip fills the row's
 * height, so the whole tab is the target and not just the word in it
 * (September UAT, finding 9), and carries its own name and selection to
 * TalkBack (audit finding 7). The row is the first thing read after the
 * title.
 */
@Composable
fun PlaceRow(current: Place, onSelect: (Place) -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.MENU_ROW_H.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (place in Place.entries) {
            val isSelected = place == current
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .let { if (isSelected) it.pressedBevel(scheme, 3.dp) else it.raisedBevel(scheme, 3.dp) }
                    .semantics { selected = isSelected }
                    .tapeClick(label = place.label) { onSelect(place) },
                contentAlignment = Alignment.Center,
            ) {
                TapeText(place.label, TapeType.pixel, if (isSelected) scheme.ink.tape else scheme.ink2.tape)
            }
        }
    }
}

/**
 * The stage row under the place row on TAPE (`DECK · CUT`) and PROJECT
 * (`PADS · BEAT · OUT`) — the segment row CHOP and GROOVE already use,
 * so a stage reads as what it is: a half of the place, not another tab.
 */
@Composable
fun StageRow(current: Stage, onSelect: (Stage) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (stage in Stage.of(current.place)) {
            SegmentButton(
                label = stage.label,
                active = stage == current,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(stage) },
            )
        }
    }
}

/**
 * A door chip: a label that opens somewhere else, drawn raised in the
 * LCD's second colour. The frame's back chip for a bench that has none
 * of its own, the dock's standing door, and the step-one door onto the
 * fullscreen grid all wear this.
 */
@Composable
fun DoorChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme, 3.dp)
            .tapeClick(label = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.amberOnChrome)
    }
}

/**
 * The dock at the foot of every screen, on the LCD surface. Three
 * regions, left to right, each read from [dock]:
 *
 * - **transport**: the project's beat, from GROOVE's own TRANSPORT group.
 *   While BEAT is the stage, [onPlayToggle] is set and the readout is a
 *   button — ▶ to play, ■ to stop, ● while a take goes in; off BEAT the
 *   clock is not running (it is GrooveScreen's frame loop, which BOUNCE,
 *   RECORD and the audition all ride) so the region is a readout that
 *   says `■`, never a button that would do nothing. Keeping the beat
 *   playing across benches wants that clock pulled out of the screen; the
 *   dock is ready for it, the screen is not yet.
 * - **capture**: the capture session's one control, which used to be the
 *   shelf's ArmControl and nowhere else. Idle, the light is dark and the
 *   words (`LISTEN · MIC`) are the door to arming — a tap opens the
 *   two-source choice ([onArm]), since arming the mic and recording an
 *   app are different consents. Armed, the light is amber, the counter
 *   ticks, the level meter shows the input (a flat meter beside a ticking
 *   counter reads unmistakably as *recording, hearing nothing*), and SNIP
 *   and STOP sit beside it. Colour is never the only signal: the words
 *   change with the light.
 * - **now**: the line and its door. The line is what the toast used to
 *   say and stays until the next one; the door stays until taken. A
 *   `liveRegion` on the line keeps the "did DELETE work" channel the
 *   toast gave a screen-reader user (audit finding 3), polite rather than
 *   assertive for the same reason the toast's was.
 *
 * [busy] is the status bar's old third cell: a line about a write in
 * flight, shown in place of the standing line while it runs.
 */
@Composable
fun Dock(
    dock: DockState,
    busy: String?,
    onArm: () -> Unit,
    onPlayToggle: (() -> Unit)?,
    onSnip: () -> Unit,
    onEject: () -> Unit,
    onTakeDoor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val now = dock.now
    val armed = dock.capture is DockModel.Capture.Armed
    val line = busy ?: now.text
    // The old toast's rise: the line fades in when its words change, so a
    // replaced sentence reads as an event and not as a silent edit.
    val t = remember(line) { Animatable(0f) }
    LaunchedEffect(line) { t.animateTo(1f, tween(Motion.TOAST_IN_MS)) }

    Row(
        modifier
            .fillMaxWidth()
            .height(Layout.DOCK_H.dp)
            .lcdPanel(scheme)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Transport.
        if (onPlayToggle != null) {
            val playing = dock.transport !is DockModel.Transport.Stopped
            DockChip(dock.transportLabel, scheme.amber.tape, onClick = onPlayToggle, label = if (playing) Places.STOP_BEAT else Places.PLAY_BEAT)
        } else {
            TapeText(dock.transportLabel, TapeType.lcdSmall, scheme.lcdInk.tape)
        }
        // Capture.
        if (!armed) {
            Row(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .tapeClick(label = Places.LISTEN, onClick = onArm)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CaptureLight(lit = false)
                TapeText(dock.captureLabel, TapeType.pixelSmall, scheme.amber.tape)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CaptureLight(lit = true)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TapeText(dock.captureLabel, TapeType.pixelSmall, scheme.amber.tape)
                    CaptureMeter(Modifier.width(72.dp).height(6.dp))
                }
                DockChip(Places.SNIP, scheme.amber.tape, onClick = onSnip)
                DockChip(Places.STOP, BinRedOnLcd, onClick = onEject, border = BIN_RED_BORDER)
            }
        }
        // Now: the line, then its door.
        TapeText(
            line,
            TapeType.pixelSmall,
            scheme.lcdInk.tape,
            modifier = Modifier
                .weight(1f)
                .alpha(t.value)
                .semantics { liveRegion = LiveRegionMode.Polite },
            maxLines = 2,
        )
        val door = now.door
        if (door != null && busy == null) {
            DoorChip(door.label, onClick = onTakeDoor)
        }
    }
}

/** The capture light: amber while the tape rolls, a dark dot otherwise. */
@Composable
private fun CaptureLight(lit: Boolean) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .size(8.dp)
            .background(if (lit) scheme.amber.tape else scheme.lcdInk.tape.copy(alpha = 0.25f), CircleShape),
    )
}

/**
 * The armed session's input level, collected right here rather than
 * hoisted: it updates at ~21 Hz (`READ_BLOCK_FRAMES` @ 44.1 kHz), so this
 * is the smallest composable scope that should recompose on every tick.
 * On a MIC session the empty field tints the instant the block reads
 * exact digital silence — a per-block observation, deliberately weaker
 * than `MicSessionService.micSilent`'s multi-second verdict, and honest
 * precisely because it never claims more than the one block it saw.
 */
@Composable
private fun CaptureMeter(modifier: Modifier) {
    val scheme = LocalScheme.current
    val level by MicSessionService.level.collectAsState()
    val source by MicSessionService.source.collectAsState()
    val possiblyMuted = source == MicSessionService.Source.MIC && level <= SilenceWatch.DEFAULT_THRESHOLD
    val filled = sqrt(level.coerceIn(0f, 1f))
    val fieldColor = if (possiblyMuted) MUTE_WARN_FIELD else scheme.field.tape
    Canvas(modifier.semantics { contentDescription = "MIC LEVEL METER" }) {
        drawRect(color = fieldColor, size = size)
        if (filled > 0f) drawRect(color = scheme.amber.tape, size = Size(size.width * filled, size.height))
    }
}

/**
 * The meter's empty-field tint on exact digital silence — a deliberate,
 * scheme-independent constant like [BIN_RED_BORDER]: "something's off"
 * has to read the same in every scheme. A different colour from the
 * bin-red pair on purpose: a warning about a live session, not the end
 * of one.
 */
private val MUTE_WARN_FIELD = Color(0xFF4A2E14)

/**
 * BIN red, the house constant for a session-ending action (STOP here,
 * EMPTY THE BIN there), the same in every scheme. The glow half is the
 * tuned token `BinRedGlow`; this is its border, duplicated per file by
 * the convention the other copies state rather than hoisted.
 */
private val BIN_RED_BORDER = Color(0xFF6A2020)

/** A small chip on the LCD: a word in a one-pixel frame, a hit target tall. */
@Composable
private fun DockChip(text: String, ink: Color, onClick: () -> Unit, border: Color = ink, label: String = text) {
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .tapeClick(label = label, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixelSmall, ink)
    }
}

/** One row of a [ChoiceSheet]: its label and what it does. The label carries its own ▸ when it opens a screen. */
data class SheetRow(val label: String, val onClick: () -> Unit)

/**
 * A short menu over the screen — the shelf's own pop-up shape (a scrim, a
 * raised card, a CANCEL), so it reads as the same kind of thing the TOOLS
 * menu is. Tapping outside the card is a genuine dismiss path and needs
 * its own label even though CANCEL below also reaches it; the card
 * swallows the tap so it does not fall through to the scrim. Opening a
 * row may be a navigation; opening the sheet never is.
 */
@Composable
fun ChoiceSheet(rows: List<SheetRow>, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    BackHandler(onBack = onDismiss)
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
                .padding(12.dp)
                .raisedBevel(scheme)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in rows) {
                ActionButton(row.label, scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = row.onClick)
            }
            ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/** Behind the gear in the title bar: SETUP ▸ and HELP ▸, one tap from every screen. */
@Composable
fun GearSheet(onSetup: () -> Unit, onHelp: () -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(
        listOf(SheetRow("${Places.GEAR_SETUP} ▸", onSetup), SheetRow("${Places.GEAR_HELP} ▸", onHelp)),
        onDismiss,
    )
}

/**
 * Behind the dock's idle capture chip: the two sources the shelf's
 * ArmControl offered as two buttons — the room through the mic, or
 * another app's audio with the projection consent.
 */
@Composable
fun CaptureSheet(onMic: () -> Unit, onInside: () -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(
        listOf(SheetRow(Places.LISTEN, onMic), SheetRow(Places.ARM_INSIDE, onInside)),
        onDismiss,
    )
}
