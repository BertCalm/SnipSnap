package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.AquariumControl
import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumEffect
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A staged PAD editor. Sound/context changes leave through [onEditorChange]; the host owns
 * source validation, background preview, persistence and audition lifetime. Tabs and folds
 * are presentation state only. A preview never blocks another edit or a return to BEFORE.
 */
@Composable
internal fun AquariumPanel(
    editor: AquariumEditor,
    applied: Boolean,
    canPreview: Boolean,
    canKeep: Boolean,
    canRemove: Boolean,
    canUndo: Boolean,
    busy: Boolean,
    previewing: Boolean,
    sourceStatus: String,
    status: String?,
    error: String?,
    disabledReason: String?,
    scheme: Scheme,
    color: Color,
    onEditorChange: (AquariumEditor) -> Unit,
    onBefore: () -> Unit,
    onHear: () -> Unit,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    composedOffMainThread("AquariumPanel")
    var groupId by remember { mutableStateOf(AquariumControls.groups.first().id) }
    var expandedEffects by remember { mutableStateOf(emptySet<String>()) }
    var printSettingsOpen by remember { mutableStateOf(false) }
    val enabled = !busy
    val group = AquariumControls.groups.first { it.id == groupId }

    Column(
        modifier.fillMaxWidth().testTag("aquarium-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().lcdPanel(scheme).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TapeText(
                    "AQUARIUM · PAD", TapeType.lcdHeader, scheme.lcdInk.tape,
                    Modifier.weight(1f), maxLines = 2,
                )
                Spacer(Modifier.width(8.dp))
                ActionButton(
                    "◄ PAD", scheme, enabled = enabled,
                    accessibilityLabel = "Close Aquarium",
                    modifier = Modifier.testTag("aquarium-cancel"), onClick = onCancel,
                )
            }
            TapeText(sourceStatus, TapeType.pixelSmall, scheme.lcdInk.tape, maxLines = Int.MAX_VALUE)
            TapeText(
                when {
                    busy -> status ?: "SAVING AQUARIUM…"
                    previewing -> "RENDERING PREVIEW…"
                    editor.render.isBypass -> "DRY · ALL SIX EFFECTS BYPASSED"
                    else -> editor.presetId?.let { AquariumControls.preset(it).name } ?: "CUSTOM SETTINGS"
                },
                TapeType.pixelSmall, scheme.amber.tape, maxLines = 2,
            )
            if (!busy && !previewing && status != null) {
                TapeText(status, TapeType.pixelSmall, scheme.lcdInk.tape, maxLines = Int.MAX_VALUE)
            }
        }

        if (disabledReason != null) {
            TapeText(disabledReason, TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE)
        }
        if (error != null) {
            TapeText(
                error, TapeType.pixelSmall, scheme.warn.tape,
                Modifier.testTag("aquarium-error"), maxLines = Int.MAX_VALUE,
            )
        }

        TapeText("PRESETS", TapeType.pixelSmall, scheme.ink2.tape)
        for (row in AquariumControls.presets.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (preset in row) {
                    val chosen = editor.presetId == preset.id
                    ActionButton(
                        preset.name, scheme, enabled = enabled, lit = chosen,
                        accessibilityLabel = "Aquarium preset ${preset.name}",
                        modifier = Modifier.weight(1f).testTag("aquarium-preset-${preset.id}")
                            .semantics { selected = chosen },
                        onClick = { onEditorChange(editor.selectPreset(preset.id)) },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (choice in AquariumControls.groups) {
                val chosen = choice.id == groupId
                ActionButton(
                    choice.name.uppercase(Locale.ROOT), scheme, enabled = enabled, lit = chosen,
                    accessibilityLabel = "Aquarium ${choice.name}",
                    modifier = Modifier.weight(1f).testTag("aquarium-group-${choice.id}")
                        .semantics { selected = chosen },
                    onClick = { groupId = choice.id },
                )
            }
        }
        for (effect in group.effects) {
            AquariumEffectPanel(
                effect = effect, editor = editor, enabled = enabled,
                detailsOpen = effect.id in expandedEffects, scheme = scheme, color = color,
                onEditorChange = onEditorChange,
                onDetailsToggle = {
                    expandedEffects = if (effect.id in expandedEffects) expandedEffects - effect.id
                        else expandedEffects + effect.id
                },
            )
        }

        Box(Modifier.testTag("aquarium-print-settings")) {
            GroupBox(
                legend = "PRINT SETTINGS",
                summary = "${printNumber(editor.render.tailSeconds)}S TAIL · ${gainText(editor.render.outputGain)}",
                open = printSettingsOpen, onToggle = { printSettingsOpen = !printSettingsOpen },
                scheme = scheme, enabled = enabled,
            ) {
                AquariumPrintSlider(
                    "OUTPUT", "aquarium-output", ((20.0 * log10(editor.render.outputGain.toDouble()) + 40.0) / 40.0).toFloat(),
                    gainText(editor.render.outputGain), enabled, scheme, color,
                ) { fraction ->
                    val gain = 10.0.pow((-40.0 + 40.0 * fraction) / 20.0).toFloat()
                    onEditorChange(editor.withRender(editor.render.copy(outputGain = gain)))
                }
                AquariumPrintSlider(
                    "TAIL", "aquarium-tail", (editor.render.tailSeconds / 30.0).toFloat(),
                    "${printNumber(editor.render.tailSeconds)}s", enabled, scheme, color,
                ) { fraction ->
                    onEditorChange(editor.withRender(editor.render.copy(tailSeconds = (fraction * 300f).roundToInt() / 10.0)))
                }
                AquariumPrintSlider(
                    "TEMPO", "aquarium-tempo", (editor.render.bpm / 1000.0).toFloat(),
                    if (editor.render.bpm == 0.0) "FREE" else "${printNumber(editor.render.bpm)} BPM", enabled, scheme, color,
                ) { fraction ->
                    onEditorChange(editor.withRender(editor.render.copy(bpm = (fraction * 1000f).roundToInt().toDouble())))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionButton(
                        "-1 BPM", scheme, enabled = enabled && editor.render.bpm > 0.0,
                        accessibilityLabel = "Decrease Aquarium tempo by 1 BPM",
                        modifier = Modifier.weight(1f).testTag("aquarium-tempo-down"),
                        onClick = {
                            onEditorChange(editor.withRender(editor.render.copy(bpm = (editor.render.bpm - 1.0).coerceIn(0.0, 1000.0))))
                        },
                    )
                    ActionButton(
                        "+1 BPM", scheme, enabled = enabled && editor.render.bpm < 1000.0,
                        accessibilityLabel = "Increase Aquarium tempo by 1 BPM",
                        modifier = Modifier.weight(1f).testTag("aquarium-tempo-up"),
                        onClick = {
                            onEditorChange(editor.withRender(editor.render.copy(bpm = (editor.render.bpm + 1.0).coerceIn(0.0, 1000.0))))
                        },
                    )
                }
                AquariumPrintSlider(
                    "FADE", "aquarium-fade", (editor.render.fadeSeconds / .1).toFloat(),
                    "${printNumber(editor.render.fadeSeconds * 1000.0)}ms", enabled, scheme, color,
                ) { fraction ->
                    onEditorChange(editor.withRender(editor.render.copy(fadeSeconds = (fraction * 100f).roundToInt() / 1000.0)))
                }
                TapeText(
                    "SEED ${editor.render.settings.seed} · PEAK CEILING ${printNumber(editor.render.peakCeiling.toDouble())}",
                    TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2,
                )
                TapeText(
                    "A finite stereo print. If a peak is too high, lower OUTPUT. TEMPO 0 uses free time.",
                    TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE,
                )
            }
        }

        TapeText(
            "PREVIEW PLAYS THE MAIN SAMPLE. KEEP UPDATES EVERY VELOCITY LAYER.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton(
                "BEFORE", scheme, enabled = enabled && canPreview,
                accessibilityLabel = "Aquarium before",
                modifier = Modifier.weight(1f).testTag("aquarium-before"), onClick = onBefore,
            )
            ActionButton(
                "PREVIEW", scheme, enabled = enabled && canPreview,
                accessibilityLabel = "Preview Aquarium",
                modifier = Modifier.weight(1f).testTag("aquarium-hear"), onClick = onHear,
            )
        }
        TapeText(
            when {
                editor.render.isBypass && applied -> "KEEP DRY restores the saved source. Nothing changes until KEEP."
                applied -> "Edits start from the saved source. Nothing changes until KEEP."
                else -> "Nothing changes until KEEP. UNDO reverses the last kept edit."
            },
            TapeType.pixelSmall, scheme.ink3.tape, maxLines = Int.MAX_VALUE,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton(
                if (editor.render.isBypass && applied) "KEEP DRY" else "KEEP", scheme,
                enabled = enabled && canKeep, accessibilityLabel = "Keep Aquarium",
                modifier = Modifier.weight(1f).testTag("aquarium-keep"), onClick = onKeep,
            )
            ActionButton(
                "REMOVE", scheme, enabled = enabled && canRemove,
                accessibilityLabel = "Remove Aquarium",
                modifier = Modifier.weight(1f).testTag("aquarium-remove"), onClick = onRemove,
            )
            ActionButton(
                "UNDO", scheme, enabled = enabled && canUndo,
                accessibilityLabel = "Undo Aquarium",
                modifier = Modifier.weight(1f).testTag("aquarium-undo"), onClick = onUndo,
            )
        }
    }
}

@Composable
private fun AquariumEffectPanel(
    effect: AquariumEffect,
    editor: AquariumEditor,
    enabled: Boolean,
    detailsOpen: Boolean,
    scheme: Scheme,
    color: Color,
    onEditorChange: (AquariumEditor) -> Unit,
    onDetailsToggle: () -> Unit,
) {
    val active = editor.render.settings.parameters.getValue(effect.mixId) > 0f
    Column(Modifier.fillMaxWidth().testTag("aquarium-effect-${effect.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText(effect.name.uppercase(Locale.ROOT), TapeType.display, scheme.ink.tape, Modifier.weight(1f))
            ActionButton(
                if (active) "ON" else "BYPASSED", scheme, enabled = enabled, lit = active,
                accessibilityLabel = "${if (active) "Bypass" else "Enable"} Aquarium ${effect.name}",
                modifier = Modifier.testTag("aquarium-bypass-${effect.id}").semantics {
                    selected = active
                    stateDescription = if (active) "On" else "Bypassed"
                },
                onClick = { onEditorChange(editor.toggle(effect.id)) },
            )
        }
        for (control in effect.primary) AquariumParameter(control, effect, editor, enabled, scheme, color, onEditorChange)
        if (effect.details.isNotEmpty()) {
            Box(Modifier.testTag("aquarium-details-${effect.id}")) {
                GroupBox(
                    legend = "${effect.name.uppercase(Locale.ROOT)} DETAILS",
                    summary = "${effect.details.size} MORE CONTROLS",
                    open = detailsOpen, onToggle = onDetailsToggle, scheme = scheme, enabled = enabled,
                ) {
                    for (control in effect.details) AquariumParameter(control, effect, editor, enabled, scheme, color, onEditorChange)
                }
            }
        }
    }
}

@Composable
private fun AquariumParameter(
    control: AquariumControl,
    effect: AquariumEffect,
    editor: AquariumEditor,
    enabled: Boolean,
    scheme: Scheme,
    color: Color,
    onEditorChange: (AquariumEditor) -> Unit,
) {
    val value = editor.render.settings.parameters.getValue(control.id)
    val name = "Aquarium ${effect.name} ${control.label}"
    if (control.spec.isDiscrete) {
        val count = ((control.spec.maximum - control.spec.minimum) / control.spec.step).roundToInt() + 1
        val columns = if ((0 until count).any {
            control.format(control.spec.minimum + it * control.spec.step).length > 7
        }) 2 else 3
        Column(
            Modifier.fillMaxWidth().testTag("aquarium-control-${control.id}").semantics {
                contentDescription = name
                stateDescription = control.format(value)
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TapeText(control.label.uppercase(Locale.ROOT), TapeType.pixelSmall, scheme.ink2.tape)
            for (row in (0 until count).toList().chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (index in row) {
                        val option = control.spec.minimum + index * control.spec.step
                        val chosen = option == value
                        val label = control.format(option)
                        ActionButton(
                            label.uppercase(Locale.ROOT), scheme, enabled = enabled, lit = chosen,
                            accessibilityLabel = "$name: $label",
                            modifier = Modifier.weight(1f).testTag("aquarium-option-${control.id}-$index")
                                .semantics { selected = chosen },
                            onClick = { onEditorChange(editor.set(control.id, option)) },
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().testTag("aquarium-control-${control.id}").semantics(mergeDescendants = true) {
            contentDescription = name
            stateDescription = control.format(value)
        }) {
            // Physical-unit readouts can be longer than the shared slider's 56 dp slot.
            // Give the saved value its own line rather than shortening or hiding its units.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TapeText(control.format(value), TapeType.lcdSmall, scheme.ink.tape, maxLines = 2)
            }
            StepperSlider(
                label = control.label.uppercase(Locale.ROOT), fraction = control.toNormalized(value),
                valueText = "", fillColor = color, scheme = scheme, enabled = enabled,
                onFractionChange = { onEditorChange(editor.setNormalized(control.id, it)) },
                onFractionCommit = {}, labelWidth = 104.dp,
            )
        }
    }
}

@Composable
private fun AquariumPrintSlider(
    label: String,
    tag: String,
    fraction: Float,
    readout: String,
    enabled: Boolean,
    scheme: Scheme,
    color: Color,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {
        contentDescription = "Aquarium print $label"
        stateDescription = readout
    }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TapeText(readout, TapeType.lcdSmall, scheme.ink.tape, maxLines = 2)
        }
        StepperSlider(
            label = label, fraction = fraction.coerceIn(0f, 1f), valueText = "",
            fillColor = color, scheme = scheme, enabled = enabled,
            onFractionChange = onChange, onFractionCommit = {}, labelWidth = 104.dp,
        )
    }
}

private fun printNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
private fun gainText(gain: Float): String = "${printNumber(20.0 * log10(gain.toDouble()))}dB"
