package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Scales
import com.snipsnap.shell.MeridianSynth
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import kotlin.math.roundToInt
import java.util.Locale

/** Native performance edits preserve the saved material, model and noise context. */
@Composable
internal fun MeridianPerformanceControls(
    patch: MeridianPatch,
    fillColor: Color,
    scheme: Scheme,
    onChange: (MeridianPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Meridian.MIDI_MIN).toFloat() / (Meridian.MIDI_MAX - Meridian.MIDI_MIN),
        fillColor = fillColor,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            val midi = (Meridian.MIDI_MIN + value * (Meridian.MIDI_MAX - Meridian.MIDI_MIN)).roundToInt()
                .coerceIn(Meridian.MIDI_MIN, Meridian.MIDI_MAX)
            onChange(MeridianSynth.withMidi(patch, midi))
        },
    )
    MacroSlider(
        label = "VELOCITY", value = patch.velocity, fillColor = fillColor, scheme = scheme,
        onValueChange = { onChange(MeridianSynth.withVelocity(patch, it.coerceIn(0f, 1f))) },
    )
    TapeText("ROOT IS THE BASE NOTE; TUNE TRANSPOSES IT.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    TapeText(MeridianSynth.holdCaption(patch).uppercase(Locale.ROOT), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    if (patch.model >= 2) {
        TapeText("HOLD 97.5–100%: FIXED-ALIGNMENT SUSTAIN.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    }
    if (MeridianSynth.isHeld(patch)) {
        TapeText("AUDITION AND SEND TO PAD PLAY A SHORT CAPTURE. MAKE INSTRUMENT KEEPS SUSTAIN.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}

internal fun meridianInstrumentReadiness(patch: MeridianPatch): String =
    if (patch.model < 2) "THIS ORIGINAL SOUND IS FINITE. LOAD A CURRENT PRESET FOR SUSTAIN."
    else "SET HOLD TO 97.5% OR ABOVE TO MAKE A SUSTAINED INSTRUMENT."
