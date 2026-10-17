package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.snipsnap.shell.Place
import com.snipsnap.shell.Places
import com.snipsnap.shell.Stage

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

    /** The per-place state records, straight through: the screens read and write them. */
    val pads get() = model.pads
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
        TapeText(label, TapeType.pixel, scheme.amber.tape)
    }
}

/**
 * The dock at the foot of every screen, on the LCD surface. Three
 * regions, left to right, each read from [dock]:
 *
 * - **transport**: the project's beat. A readout for now — the beat's
 *   ▶/■ move in from GROOVE with the BEAT stage — so it says `■` and
 *   nothing more until something plays, rather than drawing a button
 *   that would do nothing.
 * - **capture**: the light and counter the shelf's ArmControl shows,
 *   now on every screen, and SNIP while armed. The light is amber while
 *   the tape rolls and dark otherwise, doubled by the words beside it.
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
    onSnip: () -> Unit,
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
        TapeText(dock.transportLabel, TapeType.lcdSmall, scheme.lcdInk.tape)
        // Capture: the light, the words, SNIP while armed.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (armed) scheme.amber.tape else scheme.lcdInk.tape.copy(alpha = 0.25f), CircleShape),
            )
            TapeText(dock.captureLabel, TapeType.pixelSmall, if (armed) scheme.amber.tape else scheme.lcdInk.tape)
            if (armed) {
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(label = Places.SNIP, onClick = onSnip)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(Places.SNIP, TapeType.pixelSmall, scheme.amber.tape)
                }
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

/**
 * Behind the gear in the title bar: two rows, SETUP ▸ and HELP ▸, one
 * tap from every screen. The shelf's own pop-up shape (a scrim, a raised
 * card, a CANCEL), so it reads as the same kind of thing the TOOLS menu
 * is. Opening a row is a navigation; opening the sheet is not.
 */
@Composable
fun GearSheet(onSetup: () -> Unit, onHelp: () -> Unit, onDismiss: () -> Unit) {
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
            ActionButton("${Places.GEAR_SETUP} ▸", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onSetup)
            ActionButton("${Places.GEAR_HELP} ▸", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onHelp)
            ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/**
 * MAKE as a list (`docs/SCREEN_PLAN_2026_10.md` §3.6), the frame's own
 * first cut: one row per lab, each opening the lab as a bench. The
 * subtitles ("last: RESIN · KICK, to A09") and the INSTRUMENTS row come
 * with the MAKE step of the plan; this is the door, so the three labs
 * stay reachable the day their tabs go.
 */
@Composable
fun MakeList(onOpen: (Bench) -> Unit) {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (lab in listOf(Bench.SYNTH, Bench.SNAP, Bench.SURFACE)) {
            ActionButton("${lab.label} ▸", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = { onOpen(lab) })
        }
    }
}
