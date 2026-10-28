package com.snipsnap.app.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.BinRedOnLcd
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Bench
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Copy
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

    /** [Navigation.backOutOf]: leave [page] for [fallback] if it is still what is on screen; true when it moved. */
    fun backOutOf(page: Location, fallback: Location): Boolean {
        val moved = model.backOutOf(page, fallback)
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
 *
 * A line is stamped with where it was said, read from [locationNow] at
 * the moment of the write, so an async `say` landing after the user left
 * says "here", where it landed. [moved] is how navigation tells the dock
 * the user is somewhere else.
 */
class DockState(
    private val locationNow: () -> Location,
    private val model: DockModel = DockModel(),
) {
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
        model.say(text, locationNow())
        now = model.now
    }

    fun offer(text: String, door: Door) {
        model.offer(text, door, locationNow())
        now = model.now
    }

    /** The user is at [to] now: a line said elsewhere rests, a door's words stay with it. */
    fun moved(to: Location) {
        model.moved(to)
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
fun rememberDock(locationNow: () -> Location): DockState = remember { DockState(locationNow) }

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
            .heightIn(min = Layout.MENU_ROW_H.dp)
            // The chips fill the row; the row is as tall as its tallest
            // chip, so a large font grows it instead of clipping the label.
            .height(IntrinsicSize.Min),
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
        TapeText(label, TapeType.pixel, scheme.accent.tape)
    }
}

/**
 * The dock at the foot of every screen, on the LCD surface. Three
 * regions, left to right, each read from [dock]:
 *
 * - **transport**: the project's beat. The clock is App's (`BeatPlayer`),
 *   not a screen's, so the beat plays on wherever the user goes and this
 *   chip is its one control from anywhere a kit is open: [onPlayToggle] is
 *   set and the readout is a button — ▶ to play (what a tap does, so the
 *   stopped chip says ▶, not ■), the running readout to stop, ● while a
 *   take goes in. It is null where the beat cannot run — a bench that
 *   makes its own sound (ORBIT, SPLIT, LOOP, ARRANGE) — and there a
 *   stopped beat draws nothing, never a button that would do nothing.
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
 * [busy] is the status bar's old third cell: the job in flight, shown in
 * place of the standing line while it runs. Not at once: a job is held
 * back for [BusyLatch.SHOW_AFTER_MS], so the many that finish in a blink
 * draw nothing, and once drawn it stays for [BusyLatch.MIN_VISIBLE_MS] from
 * the frame that first drew it, so it can be read. While it is drawn the dock
 * carries four things:
 *
 *  - a [BusyBar] along the top edge of the panel, over the row and adding
 *    no height: counted when the job can be counted, with no end when not;
 *  - the job's own words in the line, which hold through the linger;
 *  - the count (`5 OF 16`) after the line, outside the line's live region
 *    so a progress tick is not announced, and never given to the line to
 *    ellipsize: it is the line that gives way;
 *  - CANCEL in the door's place, when the job can be stopped.
 *
 * A job owns the dock from the moment it starts, drawn or not, and the
 * standing door waits until the job's bar has gone.
 */
@Composable
fun Dock(
    dock: DockState,
    busy: Busy?,
    onArm: () -> Unit,
    onPlayToggle: (() -> Unit)?,
    onSnip: () -> Unit,
    onEject: () -> Unit,
    onTakeDoor: () -> Unit,
    modifier: Modifier = Modifier,
) = Dock(dock, busy, onArm, onPlayToggle, onSnip, onEject, onTakeDoor, modifier, SystemClock::uptimeMillis)

/**
 * [Dock] on a clock the caller supplies, in ms and monotonic, for the
 * job's show delay. The device test hands it the compose test clock, for
 * the reason [rememberBusyVisible]'s own overload has one: the delay is a
 * coroutine `delay`, which runs on the test clock's virtual time, and
 * [SystemClock] would not move with it. [clock] has no default so this
 * cannot be mistaken for the public one at a call site.
 */
@Composable
internal fun Dock(
    dock: DockState,
    busy: Busy?,
    onArm: () -> Unit,
    onPlayToggle: (() -> Unit)?,
    onSnip: () -> Unit,
    onEject: () -> Unit,
    onTakeDoor: () -> Unit,
    modifier: Modifier,
    clock: () -> Long,
) {
    val scheme = LocalScheme.current
    val now = dock.now
    val armed = dock.capture is DockModel.Capture.Armed
    val visible = rememberBusyVisible(busy, clock)
    // The last job the dock was handed, so the bar's linger past the job's
    // end still has its words and its count and not a blank.
    val last = remember { LastBusy() }
    if (busy != null) last.job = busy
    val shown = if (visible) busy ?: last.job else null
    val line = shown?.label ?: now.text
    // The old toast's rise: the line fades in when its words change, so a
    // replaced sentence reads as an event and not as a silent edit.
    val t = remember(line) { Animatable(0f) }
    LaunchedEffect(line) { t.animateTo(1f, tween(Motion.TOAST_IN_MS)) }

    // The panel is a box so the bar can lie over the row's top edge: a
    // sibling of the row, so it takes none of the row's height.
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Layout.DOCK_H.dp)
            .lcdPanel(scheme),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.DOCK_H.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Transport. A live chip that is stopped says ▶, what a tap does; the
            // old ■ read as STOP on the one control that would start. Where the
            // beat cannot run (no chip) a stopped beat has no readout at all: an
            // inert ■ beside the capture light read as a button that did nothing.
            val stopped = dock.transport is DockModel.Transport.Stopped
            if (onPlayToggle != null) {
                DockChip(
                    if (stopped) Places.PLAY_GLYPH else dock.transportLabel,
                    scheme.amber.tape,
                    onClick = onPlayToggle,
                    label = if (stopped) Places.PLAY_BEAT else Places.STOP_BEAT,
                )
            } else if (!stopped) {
                TapeText(dock.transportLabel, TapeType.lcdSmall, scheme.lcdInk.tape)
            }
            // Capture.
            if (!armed) {
                Row(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .widthIn(min = Layout.MIN_HIT_TARGET.dp)
                        .tapeClick(label = Places.LISTEN, onClick = onArm)
                        // The light is an 8 dp dot: the state it shows, said.
                        .semantics { stateDescription = if (dock.capture is DockModel.Capture.Snipped) "SNIPPED" else "MIC IDLE" }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CaptureLight(lit = false)
                    TapeText(dock.captureLabel, TapeType.pixelSmall, scheme.amber.tape)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // The light, the count and the meter, one node that says the
                    // state; SNIP and STOP stay their own, so they are not merged in.
                    Row(
                        Modifier.semantics(mergeDescendants = true) { stateDescription = "MIC ARMED" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        CaptureLight(lit = true)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            TapeText(dock.captureLabel, TapeType.pixelSmall, scheme.amber.tape)
                            CaptureMeter(Modifier.width(72.dp).height(6.dp))
                        }
                    }
                    DockChip(Places.SNIP, scheme.amber.tape, onClick = onSnip)
                    DockChip(Places.STOP, BinRedOnLcd, onClick = onEject, border = BIN_RED_BORDER)
                }
            }
            // Now: the line, then the job's count, then CANCEL or the standing door.
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
            val total = shown?.total
            if (shown != null && total != null) {
                // No weight: the line above is the one that gives way.
                TapeText(Copy.busyCount(shown.done, total), TapeType.pixelSmall, scheme.lcdInk.tape)
            }
            // CANCEL is for the live job: once it has ended and only its bar
            // lingers there is nothing left to stop, and its lambda is stale.
            val cancel = if (visible) busy?.cancel else null
            val door = now.door
            if (cancel != null) {
                DockChip(Copy.CANCEL_JOB, scheme.amber.tape, onClick = cancel)
            } else if (door != null && busy == null && !visible) {
                DoorChip(door.label, onClick = onTakeDoor)
            }
        }
        if (shown != null) BusyBar(shown, scheme.amber.tape, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * What a screen remembers of the job it last drew, so that the linger past
 * the job's end still has its words and its count and not a blank ([Dock],
 * and `KitLoading` for the kit's card). Plain memory, not state: it is only
 * read when the job is null, on the composition that follows the last one to
 * write it.
 */
internal class LastBusy {
    var job: Busy? = null
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
            .widthIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .tapeClick(label = label, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixelSmall, ink)
    }
}

/**
 * One row of a [ChoiceSheet]: its label and what it does. The label carries
 * its own ▸ when it opens a screen. [accessibilityLabel] is the accessible
 * name when it should not be the label - the name the dock's chip already
 * wears for the same door.
 */
data class SheetRow(val label: String, val accessibilityLabel: String? = null, val onClick: () -> Unit)

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
                ActionButton(row.label, scheme, enabled = true, accessibilityLabel = row.accessibilityLabel, modifier = Modifier.fillMaxWidth(), onClick = row.onClick)
            }
            ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/** Behind the gear in the title bar: SETUP ▸, HELP ▸ and MIDI ▸, one tap from every screen. */
@Composable
fun GearSheet(onSetup: () -> Unit, onHelp: () -> Unit, onMidi: () -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(
        listOf(
            SheetRow("${Places.GEAR_SETUP} ▸", onClick = onSetup),
            SheetRow("${Places.GEAR_HELP} ▸", onClick = onHelp),
            SheetRow("${Places.GEAR_MIDI} ▸", onClick = onMidi),
        ),
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
        listOf(SheetRow(Places.ARM_MIC, accessibilityLabel = Places.LISTEN, onClick = onMic), SheetRow(Places.ARM_INSIDE, onClick = onInside)),
        onDismiss,
    )
}
