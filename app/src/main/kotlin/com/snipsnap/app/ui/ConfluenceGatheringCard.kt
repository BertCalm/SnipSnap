package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.ConfluenceWorkshop
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.ConfluenceBankVoice
import java.util.Locale
import kotlin.math.roundToInt

/** Local gathering settings only: HEAR previews them, MAKE writes a separate ordinary pad. */
@Composable
internal fun ConfluenceGatheringCard(
    controls: ConfluenceWorkshop.Controls?,
    foundationVoices: List<ConfluenceBankVoice>,
    seconds: Float?,
    performers: Int?,
    destination: String?,
    loading: Boolean,
    ready: Boolean,
    busy: Boolean,
    status: String?,
    refusal: String?,
    scheme: Scheme,
    fillColor: Color,
    onControlsChange: (ConfluenceWorkshop.Controls) -> Unit,
    onHear: () -> Unit,
    onMake: () -> Unit,
) {
    val enabled = controls != null && ready && !loading && !busy
    Column(Modifier.fillMaxWidth().testTag("confluence-gathering"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TapeText("GATHERING · A NEW VARIATION", TapeType.pixelSmall, scheme.ink.tape, maxLines = 2)
        TapeText(
            "REBUILDS THE SAVED GATHERING. THIS PAD'S CURRENT EDITS AND FX STAY ON THE ORIGINAL.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE,
        )
        if (seconds != null && performers != null) {
            TapeText(
                "$performers PERFORMERS · ${seconds.roundToInt()}S · NATIVE PITCHES, WHOLE PHRASES",
                TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2,
            )
        }
        if (controls != null) {
            TapeText("LOW VOICE", TapeType.pixelSmall, scheme.ink2.tape)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (voice in foundationVoices) {
                    ActionButton(
                        voice.title, scheme, enabled = enabled,
                        lit = voice.id == controls.foundationVoiceId,
                        accessibilityLabel = "Low voice: ${voice.title}",
                        modifier = Modifier.weight(1f),
                        onClick = { onControlsChange(controls.copy(foundationVoiceId = voice.id)) },
                    )
                }
            }
            GatheringSlider("INDIVIDUALITY", controls.divergence, percent(controls.divergence), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(divergence = (it * 40f).roundToInt() / 40f))
            }
            GatheringSlider("ROOM", controls.space, percent(controls.space), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(space = (it * 40f).roundToInt() / 40f))
            }
            GatheringSlider("ANSWER GAP", fraction(controls.answerGapSeconds), "%.2fS".format(Locale.ROOT, controls.answerGapSeconds), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(answerGapSeconds = positiveValue(it)))
            }
            GatheringSlider("LOW", fraction(controls.foundationGain), gain(controls.foundationGain), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(foundationGain = positiveValue(it)))
            }
            GatheringSlider("AIR", fraction(controls.airyGain), gain(controls.airyGain), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(airyGain = positiveValue(it)))
            }
            GatheringSlider("ANSWER", fraction(controls.answerGain), gain(controls.answerGain), fillColor, scheme, enabled) {
                onControlsChange(controls.copy(answerGain = positiveValue(it)))
            }
        }
        val line = refusal ?: status ?: if (loading) "OPENING THE SAVED GATHERING…" else null
        if (line != null) {
            TapeText(line, TapeType.pixelSmall, if (refusal == null) scheme.ink2.tape else scheme.warn.tape, maxLines = Int.MAX_VALUE)
        }
        if (!loading && controls != null && destination == null) {
            TapeText("NO EMPTY PAD. MAKE ROOM BEFORE SAVING A VARIATION.", TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE)
        }
        ActionButton("HEAR", scheme, enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = onHear)
        ActionButton(
            destination?.let { "MAKE VARIATION → $it" } ?: "MAKE VARIATION",
            scheme, enabled = enabled, dimmed = destination == null,
            modifier = Modifier.fillMaxWidth(), onClick = onMake,
        )
    }
}

@Composable
private fun GatheringSlider(
    label: String,
    fraction: Float,
    text: String,
    fillColor: Color,
    scheme: Scheme,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    StepperSlider(
        label = label, fraction = fraction.coerceIn(0f, 1f), valueText = text,
        fillColor = fillColor, scheme = scheme, enabled = enabled,
        onFractionChange = onChange, onFractionCommit = {}, labelWidth = 104.dp,
    )
}

private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"
private fun gain(value: Float): String = "%.2fX".format(Locale.ROOT, value)
private fun fraction(value: Float): Float = ((value - .05f) / 1.95f).coerceIn(0f, 1f)
private fun positiveValue(fraction: Float): Float = (.05f + (fraction.coerceIn(0f, 1f) * 39f).roundToInt() * .05f).coerceIn(.05f, 2f)
