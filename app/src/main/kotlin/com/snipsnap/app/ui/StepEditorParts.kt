package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.drawWithContent
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
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.GrooveBars
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.StepHistory
import com.snipsnap.kit.StepShape
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
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

/** DOUBLE stops here: the editor's bar tabs hold this many. A clip may hold up to 64 ([GrooveBars.MAX_BARS]) and HALF works on any even count. */
internal const val STEP_EDITOR_MAX_BARS = 8

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

    /** What one undo goes back to: the clip and the lane map as they were before an edit. */
    private class Before(val clip: Mpc3Clip, val pads: List<Int>)

    private val history = StepHistory<Before>()

    /** How many step edits can be undone now; read by the UNDO STEP EDIT button. */
    var undoDepth: Int by mutableIntStateOf(0)
        private set

    /** The bar COPY BAR took, for PASTE BAR; none until one is copied. Kept for this program's visit to the editor. */
    var copied: GrooveBars.BarCopy? by mutableStateOf(null)
        private set

    /**
     * [change] with one more thing done when the host applies it: the clip it
     * was applied to goes on the undo stack, unless the change did nothing.
     * The host calls this with the live clip, so the stack holds what was
     * really there, not what the editor last drew.
     */
    fun recorded(change: (Mpc3Clip) -> Mpc3Clip): (Mpc3Clip) -> Mpc3Clip = { before ->
        val after = change(before)
        if (after != before) pushBefore(before, lanePads)
        after
    }

    private fun pushBefore(clip: Mpc3Clip, pads: List<Int>) {
        history.push(Before(clip, pads))
        undoDepth = history.size
    }

    /** Lane [lane] now plays [slot]; written to groove.json beside the clips, off the main thread. */
    fun assign(lane: GrooveEdit.Lane, slot: Int) {
        touched = true
        val before = lanePads
        val next = GrooveEdit.setLanePad(before, lane, slot)
        lanePads = next
        pickLane = null
        selLane = null
        selStep = -1
        // A lane change is undoable too, though the clip does not change: the change rides along only to record it.
        val record: ((Mpc3Clip) -> Mpc3Clip)? = if (next == before) null else { clip -> pushBefore(clip, before); clip }
        onEdit(StepEdit(change = record, previewSlot = slot))
        if (next != before) persistPads()
    }

    private fun persistPads() {
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

    /** UNDO STEP EDIT: the clip and the lane map go back to before the last edit. */
    fun undo() {
        if (history.size == 0) {
            onEdit(StepEdit(toast = Copy.STEP_NOTHING_TO_UNDO))
            return
        }
        pickLane = null
        clearSelection()
        onEdit(
            StepEdit(
                change = { live ->
                    val back = history.pop()
                    undoDepth = history.size
                    if (back == null) {
                        live
                    } else {
                        if (back.pads != lanePads) {
                            touched = true
                            lanePads = back.pads
                            persistPads()
                        }
                        back.clip
                    }
                },
                toast = Copy.STEP_UNDONE,
            ),
        )
    }

    fun copyBar(clip: Mpc3Clip, bar: Int) {
        copied = GrooveBars.copyBar(clip, bar)
        onEdit(StepEdit(toast = Copy.stepBarCopied(bar + 1)))
    }

    fun pasteBar(clip: Mpc3Clip, bar: Int) {
        val bars = copied ?: return
        if (!GrooveBars.fits(clip, bars)) {
            onEdit(StepEdit(toast = Copy.STEP_PASTE_WRONG_LENGTH))
            return
        }
        clearSelection()
        onEdit(
            StepEdit(
                change = recorded { live -> if (bar in 0 until live.bars) GrooveBars.pasteBar(live, bar, bars) else live },
                toast = Copy.stepBarPasted(bar + 1),
            ),
        )
    }

    fun doubleLength(clip: Mpc3Clip) {
        if (!GrooveBars.canDouble(clip, STEP_EDITOR_MAX_BARS)) {
            onEdit(StepEdit(toast = Copy.stepDoubleFull(STEP_EDITOR_MAX_BARS)))
            return
        }
        onEdit(StepEdit(change = recorded { GrooveBars.doubled(it, STEP_EDITOR_MAX_BARS) }, toast = Copy.stepDoubled(clip.bars * 2)))
    }

    fun halveLength(clip: Mpc3Clip) {
        if (!GrooveBars.canHalve(clip)) {
            onEdit(StepEdit(toast = Copy.STEP_CANT_HALVE))
            return
        }
        pickLane = null
        clearSelection()
        onEdit(StepEdit(change = recorded { GrooveBars.halved(it) }, toast = Copy.stepHalved(clip.bars / 2)))
    }

    /** APPLY SWING: [percent] baked into the notes, from their straight positions ([GrooveEdit.bakeSwing]). */
    fun applySwing(clip: Mpc3Clip, percent: Int) {
        onEdit(StepEdit(change = recorded { GrooveEdit.bakeSwing(it, percent) }, toast = Copy.stepSwingBaked(percent, StepShape.rollsIn(clip))))
    }

    /** Back with a panel open closes that panel; false when there was none, so the caller leaves the editor. */
    fun closePanel(): Boolean {
        if (pickLane != null) {
            pickLane = null
            return true
        }
        if (selLane != null && selStep >= 0) {
            clearSelection()
            return true
        }
        return false
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
 * [velocityFill] for a cell that may be rolled or nudged: a tick for every hit,
 * where it sits in the cell (the step line is a quarter of the way in: a cell
 * starts [GrooveEdit.CELL_EARLY] pulses before it and is one step wide). A plain step draws only its fill. Each tick
 * is a dark bar with a light one on it, so it reads on any lane colour.
 */
internal fun Modifier.velocityFill(cell: StepCell?, color: Color): Modifier =
    if (cell == null) this else velocityFill(cell.velocity, color).let { base ->
        if (!cell.shaped) base else base.drawWithContent {
            drawContent()
            for (pulses in cell.hits) {
                val x = size.width * ((GrooveEdit.CELL_EARLY + pulses) / GrooveEdit.STEP_PULSES.toFloat()).coerceIn(0.04f, 0.96f)
                drawRect(Color.Black.copy(alpha = 0.75f), Offset(x - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), 7.dp.toPx()))
                drawRect(Color.White, Offset(x - 0.5.dp.toPx(), 1.dp.toPx()), Size(1.dp.toPx(), 5.dp.toPx()))
            }
        }
    }

/**
 * The pad picker (a lane's name was tapped) or the step-detail strip (a step
 * was held) when one is open, and the tools under it always. Shown below the
 * grid. System back closes an open picker or strip, and only then leaves the
 * editor ([onDone]).
 */
@Composable
internal fun StepEditorPanel(
    state: StepEditorState,
    clip: Mpc3Clip,
    kit: Kit,
    scheme: Scheme,
    laneNames: Map<GrooveEdit.Lane, String>,
    editorBar: Int,
    swingPercent: Int,
    onEdit: (StepEdit) -> Unit,
    onDone: () -> Unit,
) {
    // Registered after the screen's own, so it is asked first; it hands back to DONE's own function when no panel is open.
    BackHandler(onBack = { if (!state.closePanel()) onDone() })
    val pick = state.pickLane
    val sel = state.selLane
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pick != null) {
            LanePadPicker(state, pick, clip, kit, scheme, laneNames.getValue(pick))
        } else if (sel != null && state.selStep >= 0) {
            StepDetailStrip(state, sel, clip, scheme, laneNames.getValue(sel), editorBar, onEdit)
        }
        StepTools(state, clip, scheme, editorBar, swingPercent)
    }
}

/**
 * The tools, two rows of three: undo, bar copy and paste, then length and
 * swing. A tool that cannot do anything now is dimmed, not hidden, so the
 * rows do not move.
 */
@Composable
private fun StepTools(state: StepEditorState, clip: Mpc3Clip, scheme: Scheme, editorBar: Int, swingPercent: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepToolButton(Copy.STEP_UNDO, scheme, state.undoDepth > 0, Modifier.weight(1.3f)) { state.undo() }
            StepToolButton(Copy.STEP_COPY_BAR, scheme, true, Modifier.weight(1f)) { state.copyBar(clip, editorBar) }
            StepToolButton(Copy.STEP_PASTE_BAR, scheme, state.copied != null, Modifier.weight(1f)) { state.pasteBar(clip, editorBar) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepToolButton(Copy.STEP_DOUBLE, scheme, GrooveBars.canDouble(clip, STEP_EDITOR_MAX_BARS), Modifier.weight(1f)) { state.doubleLength(clip) }
            StepToolButton(Copy.STEP_HALF, scheme, GrooveBars.canHalve(clip), Modifier.weight(1f)) { state.halveLength(clip) }
            StepToolButton(Copy.stepApplySwing(swingPercent), scheme, true, Modifier.weight(1.3f)) { state.applySwing(clip, swingPercent) }
        }
    }
}

@Composable
private fun StepToolButton(label: String, scheme: Scheme, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(label = label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitTapeText(label, TapeType.pixelSmall, if (enabled) scheme.ink.tape else scheme.ink3.tape)
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
                                        change = state.recorded { GrooveEdit.setStepVelocity(it, lane, step, level.velocity, state.lanePads) },
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
            StepShapeRows(state, lane, clip, scheme, onEdit)
        }
    }
}

/** A step's shape as the strip edits it: what [StepShape] reads off the clip for the held step. */
private fun shapeEdit(state: StepEditorState, change: (Mpc3Clip, Int) -> Mpc3Clip): StepEdit {
    val step = state.selStep
    return StepEdit(change = state.recorded { change(it, step) })
}

/**
 * ROLL, NUDGE, LENGTH and CHANCE for the held step: four rows of buttons under the
 * velocity levels, plus a line saying where the hits sit and what APPLY SWING
 * will do to them when there is something for it to do. Private and not
 * inline, like everything new here, to stay under the dex register ceiling.
 */
@Composable
private fun StepShapeRows(state: StepEditorState, lane: GrooveEdit.Lane, clip: Mpc3Clip, scheme: Scheme, onEdit: (StepEdit) -> Unit) {
    val step = state.selStep
    val pads = state.lanePads
    val roll = StepShape.rollOf(clip, lane, step, pads)
    val offset = StepShape.offsetOf(clip, lane, step, pads) ?: 0
    val length = StepShape.lengthOf(clip, lane, step, pads)
    val chance = StepShape.chanceOf(clip, lane, step, pads)
    val early = StepShape.nudge(clip, lane, step, -StepShape.NUDGE_PULSES, pads) != clip
    val late = StepShape.nudge(clip, lane, step, StepShape.NUDGE_PULSES, pads) != clip
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShapeRow(Copy.STEP_ROLL, scheme) {
            for (n in 1..StepShape.MAX_ROLL) {
                ShapeButton(Copy.stepRollName(n), Copy.stepRollSpoken(n), roll == n, true, scheme, Modifier.weight(1f)) {
                    onEdit(shapeEdit(state) { c, st -> StepShape.setRoll(c, lane, st, n, state.lanePads) })
                }
            }
        }
        ShapeRow(Copy.STEP_NUDGE, scheme) {
            ShapeButton(Copy.STEP_EARLY, Copy.STEP_EARLY, false, early, scheme, Modifier.weight(1f)) {
                onEdit(shapeEdit(state) { c, st -> StepShape.nudge(c, lane, st, -StepShape.NUDGE_PULSES, state.lanePads) })
            }
            ShapeButton(Copy.STEP_LATE, Copy.STEP_LATE, false, late, scheme, Modifier.weight(1f)) {
                onEdit(shapeEdit(state) { c, st -> StepShape.nudge(c, lane, st, StepShape.NUDGE_PULSES, state.lanePads) })
            }
            ShapeButton(Copy.STEP_CENTRE, Copy.STEP_CENTRE, false, offset != 0, scheme, Modifier.weight(1f)) {
                onEdit(shapeEdit(state) { c, st -> StepShape.centre(c, lane, st, state.lanePads) })
            }
        }
        ShapeRow(Copy.STEP_LENGTH, scheme) {
            for (choice in StepShape.NoteLength.entries) {
                val name = lengthName(choice)
                ShapeButton(name, Copy.stepLengthSpoken(name), length == choice, true, scheme, Modifier.weight(1f)) {
                    onEdit(shapeEdit(state) { c, st -> StepShape.setLength(c, lane, st, choice, state.lanePads) })
                }
            }
        }
        ShapeRow(Copy.STEP_CHANCE, scheme) {
            for (c in StepShape.CHANCES) {
                ShapeButton(Copy.stepChanceName(c), Copy.stepChanceSpoken(c), chance == c, true, scheme, Modifier.weight(1f)) {
                    onEdit(shapeEdit(state) { cl, st -> StepShape.setChance(cl, lane, st, c, state.lanePads) })
                }
            }
        }
        if (offset != 0) TapeText(Copy.stepNudgeReading(offset), TapeType.pixelSmall, scheme.lcdInk2, Modifier.fillMaxWidth())
        if (offset != 0 || roll > 1) TapeText(Copy.STEP_SWING_RESETS, TapeType.pixelSmall, scheme.lcdInk2, Modifier.fillMaxWidth(), maxLines = 2)
    }
}

@Composable
private fun ShapeRow(caption: String, scheme: Scheme, buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(56.dp)) { FitTapeText(caption, TapeType.pixelSmall, scheme.lcdInk2) }
        buttons()
    }
}

@Composable
private fun ShapeButton(label: String, spoken: String, chosen: Boolean, enabled: Boolean, scheme: Scheme, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .let { if (chosen) it.pressedBevel(scheme, 4.dp) else it.sunkenField(scheme, 4.dp) }
            .semantics { selected = chosen }
            .tapeClick(label = label, enabled = enabled, spoken = spoken, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitTapeText(label, TapeType.pixelSmall, if (chosen) scheme.lcd.tape else if (enabled) scheme.ink2.tape else scheme.ink3.tape)
    }
}

private fun lengthName(choice: StepShape.NoteLength): String = when (choice) {
    StepShape.NoteLength.SHORT -> Copy.STEP_LEN_SHORT
    StepShape.NoteLength.HALF -> Copy.STEP_LEN_HALF
    StepShape.NoteLength.STEP -> Copy.STEP_LEN_STEP
    StepShape.NoteLength.TIE_2 -> Copy.STEP_LEN_TIE_2
    StepShape.NoteLength.TIE_4 -> Copy.STEP_LEN_TIE_4
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

/**
 * What a lit cell draws: its velocity (the fill's height) and where its hits sit in pulses from the step line,
 * so a roll draws a tick per hit and a nudged step draws its tick off-centre. [shaped] is false for the plain
 * case, which draws nothing extra.
 */
internal class StepCell(val velocity: Float, val hits: List<Int>, val chance: Int = Mpc3Note.ALWAYS) {
    val shaped: Boolean get() = hits.size > 1 || hits.firstOrNull() != 0

    /** The step does not always sound: its edge turns amber, as an ORBIT hit with a condition does. */
    val chancy: Boolean get() = chance < Mpc3Note.ALWAYS
}

/** What the grid says of a cell to a screen reader: lane, step, on or off, and the chance when it is under 100. */
internal fun stepCellLabel(laneWord: String, col: Int, cell: StepCell?): String =
    "$laneWord STEP ${col + 1}: ${if (cell != null) "ON" else "OFF"}" + (if (cell?.chancy == true) " ${Copy.stepChanceName(cell.chance)}" else "")

/** The grid cell's edge: amber when selected or chancy, the playhead's ink, else gray. */
internal fun stepEdgeColor(selected: Boolean, playhead: Boolean, cell: StepCell?, scheme: Scheme): Color =
    if (selected || cell?.chancy == true) scheme.amber.tape else if (playhead) scheme.lcdInk.tape else scheme.grayEdge.tape

internal fun stepEdgeWidth(selected: Boolean, cell: StepCell?): androidx.compose.ui.unit.Dp =
    if (selected || cell?.chancy == true) 2.dp else 1.dp

/** Per lane, the [StepCell] of each lit step of the bar on screen, by column. */
internal fun velocitiesByLane(clip: Mpc3Clip, bar: Int, barSteps: Int, pads: List<Int>): Map<GrooveEdit.Lane, Map<Int, StepCell>> =
    GrooveEdit.Lane.entries.associateWith { lane ->
        val note = GrooveEdit.noteFor(lane, pads)
        val vel = HashMap<Int, Float>()
        val hits = HashMap<Int, MutableSet<Int>>()
        val chance = HashMap<Int, Int>()
        for (n in clip.notes) {
            if (n.note != note) continue
            val cell = GrooveEdit.cellOf(n.timePulses)
            val col = cell - bar * barSteps
            if (col in 0 until barSteps) {
                vel[col] = maxOf(vel[col] ?: 0f, n.velocity)
                chance[col] = minOf(chance[col] ?: Mpc3Note.ALWAYS, n.chance)
                hits.getOrPut(col) { sortedSetOf() }.add((n.timePulses - cell * GrooveEdit.STEP_PULSES).toInt())
            }
        }
        vel.mapValues { (col, v) -> StepCell(v, hits.getValue(col).toList(), chance.getValue(col)) }
    }

/** The same for hits on pads no lane plays, one row for all of them. */
internal fun offLaneVelocities(clip: Mpc3Clip, bar: Int, barSteps: Int, pads: List<Int>): Map<Int, Float> {
    val out = HashMap<Int, Float>()
    for (n in GrooveEdit.offLaneNotes(clip, pads)) {
        val col = GrooveEdit.cellOf(n.timePulses) - bar * barSteps
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
    val turningOn = clip.notes.none { it.note == note && GrooveEdit.cellOf(it.timePulses) == step }
    return StepEdit(
        change = state.recorded { GrooveEdit.toggleStep(it, lane, step, state.lanePads) },
        previewSlot = if (turningOn) state.lanePads[lane.ordinal] else null,
    )
}

/** CLEAR BAR for [bar], on the live clip and the live lane map. */
internal fun clearedBar(bar: Int, state: StepEditorState): StepEdit =
    StepEdit(change = state.recorded { GrooveEdit.clearBar(it, bar, state.lanePads) }, toast = Copy.BAR_WIPED)
