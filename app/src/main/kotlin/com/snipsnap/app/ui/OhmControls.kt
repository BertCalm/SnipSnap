package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadBanks
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmPatch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Legacy encounters change model only through the player's explicit EXTEND action. */
internal fun extendedOhmPatch(patch: OhmPatch): OhmPatch = if (patch.model >= 3) patch else patch.copy(
    model = Ohm.MODEL_VERSION,
    macros = Ohm.defaults(Ohm.MODEL_VERSION) + patch.macros,
)

/**
 * OHM's complete source/performance controls live outside the large engine picker composable.
 * Every edit supplies the full recipe; a capture keeps its own PCM after the source pad changes.
 */
@Composable
internal fun OhmControls(
    patch: OhmPatch,
    entry: KitShelf.Entry?,
    fillColor: Color,
    onPatchChanged: (OhmPatch) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val currentPatch by rememberUpdatedState(patch)
    val changePatch by rememberUpdatedState(onPatchChanged)
    var pickingSource by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    val pads = entry?.kit?.pads.orEmpty().sortedBy { it.slot }

    fun readSource(pad: KitPad) {
        val kitEntry = entry ?: return
        sourceJob?.cancel()
        pickingSource = false
        busy = true
        error = null
        sourceJob = scope.launch {
            try {
                val capture = offMain {
                    val read = WavReader.readCapped(File(kitEntry.dir, pad.sampleFile), 2f)
                    OhmCapture.fromSnip(read.snip, (pad.displayName ?: PadBanks.tag(pad.slot)).take(64))
                }
                val latest = extendedOhmPatch(currentPatch)
                changePatch(latest.copy(options = latest.options.copy(exciter = OhmExciter.CAPTURED, capture = capture)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "This pad could not supply an exciter."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MacroSlider(
            label = "VELOCITY", value = patch.velocity, fillColor = fillColor, scheme = scheme,
            onValueChange = { changePatch(currentPatch.copy(velocity = it.coerceIn(0f, 1f))) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (gesture in OhmGesture.entries) {
                ActionButton(
                    gesture.name, scheme, enabled = !busy, lit = patch.gesture == gesture,
                    modifier = Modifier.weight(1f).semantics { selected = patch.gesture == gesture },
                    accessibilityLabel = "OHM ${gesture.name}",
                ) { changePatch(currentPatch.copy(gesture = gesture)) }
            }
        }
        if (patch.model < 3) {
            TapeText(
                when {
                    (patch.macros["MEDDLING"] ?: .55f) == 0f -> "ONE FINITE NOTE. MEDDLING AT ZERO PLAYS SOLO."
                    patch.gesture == OhmGesture.GATHER -> "ONE FINITE NOTE. COMPANY ARRIVES AS IT RINGS."
                    else -> "ONE FINITE NOTE. LENGTH SHAPES THE RING."
                }, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
            )
            LabButton("EXTEND ▸ SUSTAIN + SOURCES", scheme, enabled = true,
                modifier = Modifier.fillMaxWidth(), accessibilityLabel = "OHM EXTEND") {
                changePatch(extendedOhmPatch(currentPatch))
            }
        } else {
            if (patch.model == 3) {
                LabButton("UPDATE SOURCES", scheme, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), accessibilityLabel = "OHM UPDATE SOURCES") {
                    changePatch(currentPatch.copy(model = Ohm.MODEL_VERSION))
                }
            }
            TapeText("EXCITER", TapeType.pixelSmall, scheme.ink2.tape)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (exciter in OhmExciter.entries) {
                    ActionButton(
                        exciter.name, scheme,
                        enabled = !busy && (exciter != OhmExciter.CAPTURED || patch.options.capture != null),
                        lit = patch.options.exciter == exciter,
                        modifier = Modifier.weight(1f).semantics { selected = patch.options.exciter == exciter },
                        accessibilityLabel = "OHM EXCITER ${exciter.name}",
                    ) { changePatch(currentPatch.copy(options = currentPatch.options.copy(exciter = exciter))) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabButton("FROM PAD ▸", scheme, enabled = !busy && pads.isNotEmpty(),
                    modifier = Modifier.weight(1f), accessibilityLabel = "OHM EXCITER FROM PAD") {
                    pickingSource = true
                }
                if (patch.options.capture != null) {
                    LabButton("CLEAR EXCITER", scheme, enabled = !busy, modifier = Modifier.weight(1f)) {
                        val latest = currentPatch
                        changePatch(latest.copy(options = latest.options.copy(capture = null,
                            exciter = if (latest.options.exciter == OhmExciter.CAPTURED) OhmExciter.PLUCK else latest.options.exciter)))
                    }
                }
            }
            patch.options.capture?.let { capture ->
                TapeText("${capture.label ?: "CAPTURED ATTACK"} · ${capture.frameCount * 1000 / WavWriter.MPC_SAMPLE_RATE} MS",
                    TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            }
            if (pads.isEmpty()) TapeText("OPEN A KIT TO CAPTURE A PAD'S ATTACK.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TapeText("CAPTURING ATTACK…", TapeType.pixelSmall, scheme.amber.tape, Modifier.weight(1f))
                    LabButton("CANCEL", scheme, enabled = true) { sourceJob?.cancel() }
                }
            }
            error?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = 3) }
            MacroSlider("HOLD", patch.macros["HOLD"] ?: 0f, fillColor, scheme, onValueChange = {
                changePatch(currentPatch.copy(macros = currentPatch.macros + ("HOLD" to it.coerceIn(0f, 1f))))
            })
            TapeText(if (Ohm.isHeld(patch)) "AUDITION PLAYS THE SUSTAIN. MAKE INSTRUMENT KEEPS THE ATTACK AND HOLDS THE NOTE."
                else "HOLD AT THE TOP SUSTAINS. LOWER SETTINGS PLAY ONE FINITE NOTE.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
            MacroSlider("ELDER", patch.options.elder, fillColor, scheme, onValueChange = {
                changePatch(currentPatch.copy(options = currentPatch.options.copy(elder = it.coerceIn(0f, 1f))))
            })
            MacroSlider("GRAINS", patch.options.grains, fillColor, scheme, onValueChange = {
                changePatch(currentPatch.copy(options = currentPatch.options.copy(grains = it.coerceIn(0f, 1f))))
            })
            MacroSlider("FREEZE", patch.options.freeze, fillColor, scheme, onValueChange = {
                changePatch(currentPatch.copy(options = currentPatch.options.copy(freeze = it.coerceIn(0f, 1f))))
            })
            if (patch.options.elder > 0f) {
                MacroSlider("FM RATIO", patch.options.fmRatio, fillColor, scheme,
                    valueRange = .5f..2f, readout = String.format(Locale.ROOT, "%.2f", patch.options.fmRatio),
                    onValueChange = { changePatch(currentPatch.copy(options = currentPatch.options.copy(fmRatio = it.coerceIn(.5f, 2f)))) })
                MacroSlider("FM INDEX", patch.options.fmIndex, fillColor, scheme,
                    valueRange = 0f..3f, readout = String.format(Locale.ROOT, "%.2f RAD", patch.options.fmIndex),
                    onValueChange = { changePatch(currentPatch.copy(options = currentPatch.options.copy(fmIndex = it.coerceIn(0f, 3f)))) })
            }
            if (patch.options.grains > 0f) {
                MacroSlider("GRAIN SIZE", patch.options.grainSeconds, fillColor, scheme,
                    valueRange = .02f.. .20f, readout = "${(patch.options.grainSeconds * 1000).roundToInt()} MS",
                    onValueChange = { changePatch(currentPatch.copy(options = currentPatch.options.copy(grainSeconds = it.coerceIn(.02f, .20f)))) })
            }
            if (patch.options.freeze > 0f) TapeText("FREEZE KEEPS A SHORT CAPTURE OF THE SOURCE AUDIO.",
                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
        }
    }
    if (pickingSource) OhmSourcePadDialog(pads, ::readSource) { pickingSource = false }
}

@Composable
private fun OhmSourcePadDialog(pads: List<KitPad>, onPick: (KitPad) -> Unit, onCancel: () -> Unit) {
    val scheme = LocalScheme.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(14.dp).raisedBevel(scheme).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText("CHOOSE AN EXCITER PAD", TapeType.lcdSmall, scheme.ink.tape)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pads, key = { it.slot }) { pad ->
                    LabButton("${PadBanks.tag(pad.slot)} · ${pad.displayName ?: pad.sampleFile}", scheme,
                        enabled = true, modifier = Modifier.fillMaxWidth()) { onPick(pad) }
                }
            }
            LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}
