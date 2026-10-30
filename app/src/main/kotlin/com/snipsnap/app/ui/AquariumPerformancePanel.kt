package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRenderRecipe
import java.util.Locale
import kotlin.math.log10

/** Live controls reuse the PAD sound surface; finite print context never enters the saved value. */
@Composable
internal fun AquariumPerformancePanel(
    settings: AquariumPerformanceSettings,
    dirty: Boolean,
    busy: Boolean,
    streamUp: Boolean,
    pending: Boolean,
    faulted: Boolean,
    limitedFrames: Long,
    bpm: Double,
    warning: String?,
    status: String?,
    scheme: Scheme,
    color: Color,
    onChange: (AquariumPerformanceSettings) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
) {
    var editor by remember { mutableStateOf(AquariumEditor(AquariumRenderRecipe(settings = settings.settings))) }
    LaunchedEffect(settings.settings) {
        if (editor.render.settings != settings.settings) {
            editor = editor.withRender(editor.render.copy(settings = settings.settings))
        }
    }
    Column(Modifier.fillMaxWidth().testTag("aquarium-performance-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TapeText("AQUARIUM · LIVE KIT MIX", TapeType.lcdHeader, scheme.lcdInk.tape)
        TapeText(
            "PADS + BEAT/GROOVE + their PRINT/BOUNCE. Count-in clicks share the effects. Other players and MPC kit exports use their own routes.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE,
        )
        TapeText(
            when {
                !streamUp -> "NO STREAM · SETTINGS CAN STILL BE SAVED"
                faulted -> "AQUARIUM FAULT · RESET TAIL OR REOPEN KIT"
                pending -> "WAITING TO QUEUE SETTINGS…"
                !settings.enabled -> "GLOBAL BYPASS · DRY MIX"
                settings.settings.isBypass -> "DRY · ALL SIX EFFECT MIXES ARE ZERO"
                settings.outputGain == 0f -> "LIVE WET MIX MUTED"
                else -> "LIVE · ${if (bpm == 0.0) "FREE TIME" else String.format(Locale.ROOT, "%.1f BPM", bpm)}"
            }, TapeType.pixelSmall, scheme.amber.tape,
            Modifier.testTag("performance-state"), maxLines = Int.MAX_VALUE,
        )
        if (warning != null) TapeText(warning, TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE)
        if (status != null) TapeText(status, TapeType.pixelSmall, scheme.ink2.tape,
            Modifier.testTag("performance-status"), maxLines = Int.MAX_VALUE)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                if (settings.enabled) "LIVE ON" else "BYPASSED", scheme, enabled = !busy, lit = settings.enabled,
                accessibilityLabel = "${if (settings.enabled) "Bypass" else "Enable"} Aquarium performance",
                modifier = Modifier.weight(1f).testTag("performance-enable").semantics { selected = settings.enabled },
                onClick = { onChange(settings.copy(enabled = !settings.enabled)) },
            )
            ActionButton("RESET TAIL", scheme, enabled = !busy,
                modifier = Modifier.weight(1f).testTag("performance-reset"), onClick = onReset)
        }
        val gainText = if (settings.outputGain == 0f) "MUTE" else
            String.format(Locale.ROOT, "%.1f dB", 20.0 * log10(settings.outputGain.toDouble()))
        Column(Modifier.testTag("performance-output").semantics(mergeDescendants = true) {
            contentDescription = "Aquarium performance output"
            stateDescription = gainText
        }) {
            StepperSlider("OUTPUT", settings.outputGain, gainText, color, scheme, !busy,
                onFractionChange = { onChange(settings.copy(outputGain = it)) }, onFractionCommit = {})
        }
        TapeText(
            "OUTPUT applies when an effect mix is above zero. Protection reduced $limitedFrames frames on this player. Sound edits briefly fade the wet mix. Closing restores saved settings.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE,
        )
        TapeText("SEED ${settings.settings.seed} · Individual effect bypass retains its paused history.",
            TapeType.pixelSmall, scheme.ink3.tape, maxLines = Int.MAX_VALUE)
        // Keep the save doors above the deep controls on a phone.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("SAVE TO KIT", scheme, enabled = !busy && dirty,
                modifier = Modifier.weight(1f).testTag("performance-save"), onClick = onSave)
            ActionButton("REVERT", scheme, enabled = !busy && dirty,
                modifier = Modifier.weight(1f).testTag("performance-revert"), onClick = onRevert)
        }
        AquariumSoundControls(editor, !busy, scheme, color) {
            editor = it
            onChange(settings.copy(settings = it.render.settings))
        }
    }
}
