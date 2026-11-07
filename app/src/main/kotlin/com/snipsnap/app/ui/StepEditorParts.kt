package com.snipsnap.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * BEAT's step editor, the two things it grew in step 1 (any pad on any lane,
 * and a velocity per step), kept out of GrooveScreen.kt on purpose: the main
 * composable there sits at the dex 256-register cliff, so everything new is a
 * plain class plus private-to-the-module composables that each take one state
 * object, and the model lives in GrooveEdit where a JVM test can reach it.
 */

/**
 * What the editor asks of its host: a [change] to apply to the open program
 * (null leaves it alone), a pad to sound once at [previewVelocity], a toast.
 * One shape so the host has one handler for every way the editor can change
 * something.
 *
 * A change is a function, not a finished clip, because the host applies it to
 * the clip as it is when the tap is handled: two cells released in the same
 * frame each start from the other's result, where two finished clips built
 * from one composition would undo each other.
 */
internal class StepEdit(
    val change: ((Mpc3Clip) -> Mpc3Clip)? = null,
    val previewSlot: Int? = null,
    val previewVelocity: Float = 1f,
    val toast: String? = null,
)

/**
 * The step editor's own state: the open program's lane map, which lane's pad
 * picker is open, and which step is selected for detail. Plain class around
 * Compose state so the screen holds one object, not four variables.
 */
internal class StepEditorState(
    private val kitDir: File,
    private val clipName: String,
    private val scope: CoroutineScope,
    private val onEdit: (StepEdit) -> Unit,
) {
    var lanePads: List<Int> by mutableStateOf(GrooveEdit.DEFAULT_LANE_PADS)
        private set
    var pickLane: GrooveEdit.Lane? by mutableStateOf(null)
    var selLane: GrooveEdit.Lane? by mutableStateOf(null)
    var selStep: Int by mutableIntStateOf(-1)

    private var touched = false

    /** The stored map for this program, unless the player has already chosen something this visit. */
    suspend fun load() {
        val stored = try {
            withContext(Dispatchers.IO) { GrooveStore.loadLanePads(kitDir)[clipName] }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A map that cannot be read is the default lanes, which is what a pattern without one gets.
            null
        }
        if (stored != null && !touched) lanePads = stored
    }

    /** Lane [lane] now plays [slot]; written to groove.json beside the clips, off the main thread. */
    fun assign(lane: GrooveEdit.Lane, slot: Int) {
        touched = true
        val next = GrooveEdit.setLanePad(lanePads, lane, slot)
        lanePads = next
        pickLane = null
        selLane = null
        selStep = -1
        onEdit(StepEdit(previewSlot = slot))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // The latest choice, not this tap's: two quick picks race on IO and the older must not land last.
                    val all = GrooveStore.loadLanePads(kitDir) + (clipName to lanePads)
                    GrooveStore.saveLanePads(kitDir, all)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onEdit(StepEdit(toast = Copy.STEP_PADS_NOT_SAVED))
            }
        }
    }

    fun select(lane: GrooveEdit.Lane, step: Int) {
        pickLane = null
        selLane = lane
        selStep = step
    }

    fun clearSelection() {
        selLane = null
        selStep = -1
    }
}

@Composable
internal fun rememberStepEditorState(kitDir: File, clipName: String, onEdit: (StepEdit) -> Unit): StepEditorState {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onEdit)
    val state = remember(kitDir, clipName) { StepEditorState(kitDir, clipName, scope) { latest(it) } }
    LaunchedEffect(state) { state.load() }
    return state
}

/** A step cell: a tap flips it, a long press selects it for detail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.stepCellClick(label: String, onLongClick: () -> Unit, onClick: () -> Unit): Modifier =
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onLongClickLabel = Copy.STEP_DETAIL_LONG_LABEL,
        onLongClick = onLongClick,
        onClick = onClick,
    ).semantics { contentDescription = label }

/**
 * Draws a step's velocity as the height of its fill, from the bottom: a
 * quiet hit is a low bar and an accent fills the cell. Off steps draw nothing.
 */
internal fun Modifier.velocityFill(velocity: Float?, color: Color): Modifier =
    if (velocity == null) this else drawBehind {
        val h = size.height * velocity.coerceIn(0.05f, 1f)
        drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(3.dp.toPx()))
    }

/**
 * Either the pad picker (a lane's name was tapped) or the step-detail strip
 * (a step was held), or nothing. Shown below the grid.
 */
@Composable
internal fun StepEditorPanel(
    state: StepEditorState,
    clip: Mpc3Clip,
    kit: Kit,
    scheme: Scheme,
    laneNames: Map<GrooveEdit.Lane, String>,
    editorBar: Int,
    onEdit: (StepEdit) -> Unit,
) {
    val pick = state.pickLane
    val sel = state.selLane
    if (pick != null) {
        LanePadPicker(state, pick, clip, kit, scheme, laneNames.getValue(pick))
    } else if (sel != null && state.selStep >= 0) {
        StepDetailStrip(state, sel, clip, scheme, laneNames.getValue(sel), editorBar, onEdit)
    }
}

/** The sixteen pads, two rows of eight; a pad with a sound shows its name, an empty one is dimmed. */
@Composable
internal fun LanePadPicker(
    state: StepEditorState,
    lane: GrooveEdit.Lane,
    clip: Mpc3Clip,
    kit: Kit,
    scheme: Scheme,
    laneName: String,
) {
    val current = state.lanePads[lane.ordinal]
    val off = remember(clip, state.lanePads) { GrooveEdit.offLaneNotes(clip, state.lanePads).size }
    Column(Modifier.fillMaxWidth().sunkenField(scheme, 4.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TapeText(Copy.stepPadPickTitle(laneName), TapeType.pixelSmall, scheme.lcdInk.tape, Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = Layout.MIN_HIT_TARGET.dp).tapeClick(label = Copy.STEP_DETAIL_CLOSE) { state.pickLane = null }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Copy.STEP_DETAIL_CLOSE, TapeType.pixelSmall, scheme.amber.tape)
            }
        }
        for (rowStart in intArrayOf(1, 9)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (slot in rowStart until rowStart + 8) {
                    val pad = kit.pad(slot)
                    val name = pad?.displayName?.trim().orEmpty().uppercase()
                    val chosen = slot == current
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (chosen) it.pressedBevel(scheme, 3.dp) else it.border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(3.dp)) }
                            .semantics { selected = chosen }
                            .tapeClick(label = Copy.stepPadSpoken(slot, name.ifEmpty { Copy.STEP_PAD_EMPTY })) { state.assign(lane, slot) }
                            .padding(horizontal = 2.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TapeText("$slot", TapeType.pixelSmall, if (pad != null) scheme.lcdInk.tape else scheme.lcdInk2)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            FitTapeText(
                                name.ifEmpty { Copy.STEP_PAD_EMPTY },
                                TapeType.pixelSmall.copy(textAlign = TextAlign.Center),
                                if (pad != null) scheme.lcdInk.tape else scheme.lcdInk2.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        }
        if (off > 0) TapeText(Copy.stepPadOffRows(off), TapeType.pixelSmall, scheme.lcdInk2)
    }
}

/** Heading, exact velocity, and the four levels for the held step. */
@Composable
internal fun StepDetailStrip(
    state: StepEditorState,
    lane: GrooveEdit.Lane,
    clip: Mpc3Clip,
    scheme: Scheme,
    laneName: String,
    editorBar: Int,
    onEdit: (StepEdit) -> Unit,
) {
    val step = state.selStep
    val pads = state.lanePads
    val barSteps = GrooveEdit.stepsPerBar(clip)
    val velocity = GrooveEdit.velocityAt(clip, lane, step, pads)
    val title = Copy.stepDetailTitle(laneName, step / barSteps + 1, step % barSteps + 1)
    Column(Modifier.fillMaxWidth().sunkenField(scheme, 4.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TapeText(title, TapeType.pixelSmall, scheme.lcdInk.tape, Modifier.weight(1f))
            if (velocity != null) {
                TapeText(Copy.stepDetailVelocity((velocity * 100).roundToInt()), TapeType.pixelSmall, scheme.lcdInk2)
            }
            Box(
                Modifier.heightIn(min = Layout.MIN_HIT_TARGET.dp).tapeClick(label = Copy.STEP_DETAIL_CLOSE) { state.clearSelection() }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Copy.STEP_DETAIL_CLOSE, TapeType.pixelSmall, scheme.amber.tape)
            }
        }
        if (velocity == null) {
            TapeText(Copy.STEP_DETAIL_EMPTY, TapeType.pixelSmall, scheme.lcdInk2, Modifier.fillMaxWidth(), maxLines = 2)
        } else {
            val now = GrooveEdit.levelOf(velocity)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (level in GrooveEdit.VelocityLevel.entries) {
                    val chosen = level == now
                    val name = levelName(level)
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (chosen) it.pressedBevel(scheme, 4.dp) else it.sunkenField(scheme, 4.dp) }
                            .semantics { selected = chosen }
                            .tapeClick(label = Copy.stepVelocitySpoken(name)) {
                                onEdit(
                                    StepEdit(
                                        change = { GrooveEdit.setStepVelocity(it, lane, step, level.velocity, state.lanePads) },
                                        previewSlot = pads[lane.ordinal],
                                        previewVelocity = level.velocity,
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(name, TapeType.pixelSmall, if (chosen) scheme.lcd.tape else scheme.ink2.tape)
                    }
                }
            }
        }
    }
}

private fun levelName(level: GrooveEdit.VelocityLevel): String = when (level) {
    GrooveEdit.VelocityLevel.SOFT -> Copy.VEL_SOFT
    GrooveEdit.VelocityLevel.MED -> Copy.VEL_MED
    GrooveEdit.VelocityLevel.HARD -> Copy.VEL_HARD
    GrooveEdit.VelocityLevel.ACCENT -> Copy.VEL_ACCENT
}

/**
 * The thin row under the lanes for hits on pads no lane plays, so reassigning
 * a lane never hides a note: read-only cells, velocity as height, nothing to
 * tap (the pad's own lane is how to edit it). Not drawn when the whole program has none; drawn (and blank) on a bar that has none while another does, so switching bars does not resize the lanes.
 */
@Composable
internal fun OffLaneRow(cells: Map<Int, Float>?, barSteps: Int, scheme: Scheme) {
    if (cells == null) return
    Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.width(46.dp).fillMaxHeight().padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            FitTapeText(Copy.STEP_OTHER_ROW, TapeType.pixelSmall, scheme.lcdInk2)
        }
        Row(Modifier.weight(1f).fillMaxHeight()) {
            for (col in 0 until barSteps) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).velocityFill(cells[col], scheme.lcdInk2))
            }
        }
    }
}

/** Per lane, the velocity of each lit step of the bar on screen, by column. */
internal fun velocitiesByLane(clip: Mpc3Clip, bar: Int, barSteps: Int, pads: List<Int>): Map<GrooveEdit.Lane, Map<Int, Float>> =
    GrooveEdit.Lane.entries.associateWith { lane ->
        val note = GrooveEdit.noteFor(lane, pads)
        val out = HashMap<Int, Float>()
        for (n in clip.notes) {
            if (n.note != note) continue
            val at = n.timePulses
            if (at % GrooveEdit.STEP_PULSES != 0L) continue
            val col = (at / GrooveEdit.STEP_PULSES).toInt() - bar * barSteps
            if (col in 0 until barSteps) out[col] = maxOf(out[col] ?: 0f, n.velocity)
        }
        out
    }

/** The same for hits on pads no lane plays, one row for all of them. */
internal fun offLaneVelocities(clip: Mpc3Clip, bar: Int, barSteps: Int, pads: List<Int>): Map<Int, Float> {
    val out = HashMap<Int, Float>()
    for (n in GrooveEdit.offLaneNotes(clip, pads)) {
        val at = n.timePulses
        if (at % GrooveEdit.STEP_PULSES != 0L) continue
        val col = (at / GrooveEdit.STEP_PULSES).toInt() - bar * barSteps
        if (col in 0 until barSteps) out[col] = maxOf(out[col] ?: 0f, n.velocity)
    }
    return out
}

/**
 * A tap on a cell: the toggle, applied by the host to the live clip, and the
 * pad to sound when it turned a step on (judged from the clip on screen,
 * which only decides whether a preview plays).
 */
internal fun toggledStep(clip: Mpc3Clip, lane: GrooveEdit.Lane, step: Int, state: StepEditorState): StepEdit {
    val note = GrooveEdit.noteFor(lane, state.lanePads)
    val pulses = step * GrooveEdit.STEP_PULSES
    val turningOn = clip.notes.none { it.note == note && it.timePulses == pulses }
    return StepEdit(
        change = { GrooveEdit.toggleStep(it, lane, step, state.lanePads) },
        previewSlot = if (turningOn) state.lanePads[lane.ordinal] else null,
    )
}

/** CLEAR BAR for [bar], on the live clip and the live lane map. */
internal fun clearedBar(bar: Int, state: StepEditorState): StepEdit =
    StepEdit(change = { GrooveEdit.clearBar(it, bar, state.lanePads) }, toast = Copy.BAR_WIPED)
