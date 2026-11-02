package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.ScannedChainEditor
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Draw
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainSampleMode
import com.snipsnap.synth.ScannedChainShape
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Source audio is only needed while choosing a window; the patch stores the resulting shape. */
private data class ScannedSource(val snip: Snip, val label: String, val truncated: Boolean)

private const val SCANNED_SOURCE_MAX_SECONDS = 30f

/** Edits arriving before the next composition must start from the preceding complete edit. */
private class ScannedPatchEdit(var patch: ScannedChainPatch)

/** Slider travel changes editor precision; recipes continue to store the physical macro value. */
@Composable
internal fun ScannedMacroSlider(
    name: String,
    value: Float,
    fillColor: Color,
    scheme: Scheme,
    onValueChange: (Float) -> Unit,
) {
    MacroSlider(
        label = name,
        value = ScannedChainEditor.macroPosition(name, value),
        fillColor = fillColor,
        scheme = scheme,
        readout = "${(value * 100).roundToInt()}",
        onValueChange = { onValueChange(ScannedChainEditor.macroValue(name, it)) },
    )
}

private fun scannedTimeReadout(seconds: Float): String = if (seconds < 1f) {
    "${(seconds * 1000).roundToInt()} ms"
} else String.format(Locale.ROOT, "%.2f s", seconds)

/**
 * The fields outside SCANNED CHAIN's four physical macros. Each callback supplies a complete
 * patch, so changing a shape never loses the note, physical configuration or current controls.
 * The parent keys this panel on the voice; an outstanding source read cannot land on another voice.
 */
@Composable
internal fun ScannedShapeControls(
    patch: ScannedChainPatch,
    entry: KitShelf.Entry?,
    onPatchChanged: (ScannedChainPatch) -> Unit,
    onRecallPatch: (PadRecipe) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val edit = remember { ScannedPatchEdit(patch) }
    edit.patch = patch
    val onChange by rememberUpdatedState(onPatchChanged)
    fun changePatch(changed: ScannedChainPatch) {
        edit.patch = changed
        onChange(changed)
    }
    var drawing by remember { mutableStateOf(false) }
    var pickingSource by remember { mutableStateOf(false) }
    var recalling by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<ScannedSource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    val points = remember(patch.shape) { patch.shape.points }
    val pads = entry?.kit?.pads.orEmpty().sortedBy { it.slot }
    val recallable = remember(entry?.kit) {
        pads.mapNotNull { pad ->
            val recalled = pad.recipe?.let { recipe ->
                runCatching { PadRecipe.fromJsonValue(recipe).takeIf { it.patch is ScannedChainPatch } }.getOrNull()
            }
            recalled?.let { pad to it }
        }
    }

    fun readSource(pad: KitPad) {
        val kitEntry = entry ?: return
        sourceJob?.cancel()
        error = null
        busy = true
        pickingSource = false
        sourceJob = scope.launch {
            try {
                val loaded = offMain {
                    val read = WavReader.readCapped(File(kitEntry.dir, pad.sampleFile), SCANNED_SOURCE_MAX_SECONDS)
                    ScannedSource(read.snip, (pad.displayName ?: PadBanks.tag(pad.slot)).take(256), read.truncated)
                }
                source = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "This pad could not be opened."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (patch.model == ScannedChain.LEGACY_MODEL) {
            TapeText("ORIGINAL CONTROL RESPONSE", TapeType.pixelSmall, scheme.ink2.tape)
            LabButton("USE UPDATED CONTROLS", scheme, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                changePatch(edit.patch.copy(model = ScannedChain.MODEL_VERSION))
            }
            TapeText(
                "Keeps this source and settings with a stronger BEND and source-aware BLOOM.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
            )
        }
        TapeText("INITIAL SHAPE · ${patch.shape.source.name}", TapeType.pixel, scheme.amber.tape)
        TapeText(patch.shape.label, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        patch.shape.sample?.let { sample ->
            val mode = if (sample.mode == ScannedChainSampleMode.TONAL_CYCLE) "TONAL CYCLE" else "20 MS IMPRINT"
            TapeText(
                "$mode · ${String.format(Locale.ROOT, "%.3f s", sample.startFrame.toDouble() / sample.sampleRate)}",
                TapeType.pixelSmall, scheme.ink2.tape,
            )
        }
        ScannedShapeCanvas(points, "INITIAL CHAIN SHAPE", Modifier.fillMaxWidth().height(82.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabButton("FACTORY", scheme, enabled = !busy, modifier = Modifier.weight(1f)) {
                error = null
                changePatch(edit.patch.copy(shape = ScannedChainShape.factory(edit.patch.voice)))
            }
            LabButton("DRAW", scheme, enabled = !busy, modifier = Modifier.weight(1f)) {
                error = null
                drawing = true
            }
            LabButton("FROM PAD ▸", scheme, enabled = !busy && pads.isNotEmpty(), modifier = Modifier.weight(1.4f)) {
                error = null
                pickingSource = true
            }
        }
        if (pads.isEmpty()) {
            TapeText("Open a kit with a sample to use FROM PAD.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        }
        if (busy && source == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TapeText("READING SOURCE…", TapeType.pixelSmall, scheme.amber.tape, Modifier.weight(1f))
                LabButton("CANCEL", scheme, enabled = true) { sourceJob?.cancel() }
            }
        }
        if (source == null) error?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = 4) }
        MacroSlider(
            label = "VELOCITY", value = patch.velocity, fillColor = scheme.amber.tape, scheme = scheme,
            onValueChange = { changePatch(edit.patch.copy(velocity = it.coerceIn(0f, 1f))) },
        )
        MacroSlider(
            label = "LENGTH", value = patch.config.seconds, valueRange = .25f..4f,
            fillColor = scheme.amber.tape, scheme = scheme,
            readout = String.format(Locale.ROOT, "%.2f s", patch.config.seconds),
            onValueChange = { changePatch(edit.patch.copy(config = edit.patch.config.copy(seconds = it.coerceIn(.25f, 4f)))) },
        )
        if (recallable.isNotEmpty()) {
            LabButton("RECALL SCANNED PAD ▸", scheme, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                recalling = true
            }
        }
        MacroSlider(
            label = "ATTACK", value = ScannedChainEditor.attackPosition(patch.config.attackSeconds),
            fillColor = scheme.amber.tape, scheme = scheme,
            readout = scannedTimeReadout(patch.config.attackSeconds), readoutWidth = 66.dp,
            onValueChange = {
                changePatch(edit.patch.copy(config = edit.patch.config.copy(attackSeconds = ScannedChainEditor.attackSeconds(it))))
            },
        )
        MacroSlider(
            label = "RELEASE", value = ScannedChainEditor.releasePosition(patch.config.releaseSeconds),
            fillColor = scheme.amber.tape, scheme = scheme,
            readout = scannedTimeReadout(patch.config.releaseSeconds), readoutWidth = 66.dp,
            onValueChange = {
                changePatch(edit.patch.copy(config = edit.patch.config.copy(releaseSeconds = ScannedChainEditor.releaseSeconds(it))))
            },
        )
        TapeText(
            "MOTION at 0 freezes the shape. DAMP settles movement, not volume. BEND changes stiffness, not the played note.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4,
        )
        TapeText(
            "BLOOM develops the body later. Low MOTION, short LENGTH or long RELEASE can make it subtle.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4,
        )
        val controls = ScannedChain.defaults(patch.voice) + patch.macros
        if (controls.getValue("MOTION") == 0f) {
            TapeText(
                "Shape frozen: raise MOTION to hear DAMP, BEND and BLOOM change its movement.",
                TapeType.pixelSmall, scheme.amber.tape, maxLines = 3,
            )
        } else if (controls.getValue("BLOOM") > 0f && patch.config.seconds - patch.config.releaseSeconds < .5f) {
            TapeText(
                "BLOOM has little time to build. Try a longer LENGTH or shorter RELEASE.",
                TapeType.pixelSmall, scheme.amber.tape, maxLines = 3,
            )
        }
    }

    if (drawing) {
        ScannedDrawDialog(
            initial = points,
            onUse = { shape ->
                changePatch(edit.patch.copy(shape = shape))
                drawing = false
            },
            onCancel = { drawing = false },
        )
    }
    if (pickingSource) {
        ScannedPadDialog(
            title = "CHOOSE A SOURCE PAD", pads = pads,
            onPick = ::readSource, onCancel = { pickingSource = false },
        )
    }
    if (recalling) {
        ScannedPadDialog(
            title = "RECALL SCANNED PAD", pads = recallable.map { it.first },
            onPick = { pad ->
                recallable.firstOrNull { it.first.slot == pad.slot }?.second?.let(onRecallPatch)
                recalling = false
            },
            onCancel = { recalling = false },
        )
    }
    source?.let { loaded ->
        ScannedSourceDialog(
            source = loaded, busy = busy, error = error,
            onUse = { startFrame, mode ->
                if (!busy) {
                    error = null
                    busy = true
                    sourceJob = scope.launch {
                        try {
                            val shape = offMain(Dispatchers.Default) {
                                ScannedChainShape.fromSample(loaded.snip, startFrame, mode, loaded.label)
                            }
                            changePatch(edit.patch.copy(shape = shape))
                            source = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "This window could not form a shape."
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            onCancel = { if (!busy) { source = null; error = null } },
        )
    }
}

@Composable
private fun ScannedPadDialog(title: String, pads: List<KitPad>, onPick: (KitPad) -> Unit, onCancel: () -> Unit) {
    val scheme = LocalScheme.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(14.dp).raisedBevel(scheme).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TapeText(title, TapeType.lcdSmall, scheme.ink.tape, maxLines = 2)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pads, key = { it.slot }) { pad ->
                    LabButton(
                        "${PadBanks.tag(pad.slot)} · ${pad.displayName ?: pad.sampleFile}",
                        scheme, enabled = true, modifier = Modifier.fillMaxWidth(),
                    ) { onPick(pad) }
                }
            }
            LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}

/** A draft stays separate from the patch until USE SHAPE; back and CANCEL discard it. */
@Composable
private fun ScannedDrawDialog(initial: FloatArray, onUse: (ScannedChainShape) -> Unit, onCancel: () -> Unit) {
    val scheme = LocalScheme.current
    var points by remember {
        mutableStateOf(IntArray(initial.size) { ((initial[it] / .35f * .5f + .5f) * 255f).roundToInt().coerceIn(0, 255) })
    }
    var error by remember { mutableStateOf<String?>(null) }
    val canUse = (points.maxOrNull() ?: 0) - (points.minOrNull() ?: 0) >= 4
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(14.dp).raisedBevel(scheme).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("DRAW INITIAL SHAPE", TapeType.lcdSmall, scheme.ink.tape)
            TapeText("Draw across the chain. The physics will move this starting shape.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            ScannedDrawCanvas(points, Modifier.fillMaxWidth().weight(1f)) { x0, y0, x1, y1 ->
                points = Draw.stroke(points, x0, y0, x1, y1)
                error = null
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (wave in listOf(Draw.Wave.SINE, Draw.Wave.SAW)) {
                    LabButton(wave.name, scheme, enabled = true, modifier = Modifier.weight(1f)) {
                        val table = Draw.wave(wave)
                        points = IntArray(points.size) { table[it * table.size / points.size] }
                        error = null
                    }
                }
                LabButton("SMOOTH", scheme, enabled = true, modifier = Modifier.weight(1.2f)) {
                    points = Draw.smooth(points, circular = true, passes = 2)
                }
                LabButton("CLEAR", scheme, enabled = true, modifier = Modifier.weight(1f)) {
                    points = IntArray(points.size) { Draw.REST }
                    error = null
                }
            }
            if (!canUse) TapeText("Draw a shape or choose SINE or SAW.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            error?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = 3) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                LabButton("USE SHAPE", scheme, enabled = canUse, modifier = Modifier.weight(1f)) {
                    try {
                        onUse(ScannedChainShape.drawn(FloatArray(points.size) { (points[it] / 127.5f - 1f) * .35f }))
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message ?: "Draw a changing shape before using it."
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedSourceDialog(
    source: ScannedSource, busy: Boolean, error: String?,
    onUse: (Int, ScannedChainSampleMode) -> Unit, onCancel: () -> Unit,
) {
    val scheme = LocalScheme.current
    var mode by remember { mutableStateOf(ScannedChainSampleMode.TIMBRAL_WINDOW) }
    var position by remember { mutableStateOf(0f) }
    val minimumWindowSeconds = if (mode == ScannedChainSampleMode.TONAL_CYCLE) .25f else .02f
    val maximumStart = (source.snip.frameCount - (source.snip.sampleRate * minimumWindowSeconds).roundToInt()).coerceAtLeast(0)
    val startFrame = (position * maximumStart).roundToInt()
    val preview = remember(source) {
        FloatArray(256) { i ->
            val first = i * source.snip.frameCount / 256
            val end = ((i + 1) * source.snip.frameCount / 256).coerceAtLeast(first + 1).coerceAtMost(source.snip.frameCount)
            var peak = 0f
            for (frame in first until end) {
                var value = 0f
                for (channel in 0 until source.snip.channels) value += source.snip.samples[frame * source.snip.channels + channel]
                value /= source.snip.channels
                if (abs(value) > abs(peak)) peak = value
            }
            peak
        }
    }
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp).raisedBevel(scheme).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("SHAPE FROM A PAD", TapeType.lcdSmall, scheme.ink.tape)
            TapeText(source.label, TapeType.pixel, scheme.amber.tape, maxLines = 2)
            ScannedShapeCanvas(preview, "SOURCE WAVEFORM", Modifier.fillMaxWidth().height(100.dp),
                marker = if (source.snip.frameCount == 0) 0f else startFrame.toFloat() / source.snip.frameCount)
            MacroSlider(
                label = "WINDOW", value = position, fillColor = scheme.amber.tape, scheme = scheme,
                readout = String.format(Locale.ROOT, "%.3f s", startFrame.toDouble() / source.snip.sampleRate),
                onValueChange = { if (!busy) position = it.coerceIn(0f, 1f) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LabButton("20 MS IMPRINT", scheme, enabled = !busy, modifier = Modifier.weight(1f).semantics { selected = mode == ScannedChainSampleMode.TIMBRAL_WINDOW }) { mode = ScannedChainSampleMode.TIMBRAL_WINDOW }
                LabButton("TONAL CYCLE", scheme, enabled = !busy, modifier = Modifier.weight(1f).semantics { selected = mode == ScannedChainSampleMode.TONAL_CYCLE }) { mode = ScannedChainSampleMode.TONAL_CYCLE }
            }
            TapeText(
                if (mode == ScannedChainSampleMode.TIMBRAL_WINDOW) "20 MS IMPRINT · A short window becomes a timbral shape. It does not replay the source."
                else "TONAL CYCLE · Uses one detected cycle. If the pitch is unclear, choose another window or 20 MS IMPRINT.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 5,
            )
            if (source.truncated) TapeText("Showing the first 30 seconds. Trim the source pad to use a later section.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            error?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = 5) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabButton("CANCEL", scheme, enabled = !busy, modifier = Modifier.weight(1f), onClick = onCancel)
                LabButton(if (busy) "READING…" else "USE WINDOW", scheme, enabled = !busy, modifier = Modifier.weight(1f),
                    accessibilityLabel = "USE WINDOW") { onUse(startFrame, mode) }
            }
        }
    }
}

@Composable
private fun ScannedShapeCanvas(points: FloatArray, description: String, modifier: Modifier, marker: Float? = null, normalize: Boolean = true) {
    val scheme = LocalScheme.current
    Canvas(modifier.lcdPanel(scheme).semantics { contentDescription = description }) {
        drawLine(scheme.ink3.tape, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        val peak = if (normalize) maxOf(.000001f, points.maxOfOrNull { abs(it) } ?: 0f) else 1f
        val path = Path()
        points.forEachIndexed { i, value ->
            val x = i.toFloat() / (points.size - 1).coerceAtLeast(1) * size.width
            val y = size.height * (.5f - (if (normalize) .42f else .5f) * value / peak)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, scheme.lcdInk.tape, style = Stroke(2.dp.toPx()))
        marker?.let { drawLine(scheme.amber.tape, Offset(it * size.width, 0f), Offset(it * size.width, size.height), 2.dp.toPx()) }
    }
}

@Composable
private fun ScannedDrawCanvas(points: IntArray, modifier: Modifier, onStroke: (Float, Float, Float, Float) -> Unit) {
    val scheme = LocalScheme.current
    val stroke by rememberUpdatedState(onStroke)
    val line = FloatArray(points.size) { points[it] / 127.5f - 1f }
    ScannedShapeCanvas(
        line, "DRAW INITIAL SHAPE",
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun x(at: Offset) = (at.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                fun y(at: Offset) = (1f - at.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                var previous = down.position
                down.consume()
                stroke(x(previous), y(previous), x(previous), y(previous))
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    stroke(x(previous), y(previous), x(change.position), y(change.position))
                    previous = change.position
                    change.consume()
                    if (!change.pressed) break
                }
            }
        }, normalize = false,
    )
}
