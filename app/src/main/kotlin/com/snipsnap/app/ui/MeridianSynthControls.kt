package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.MeridianSynth
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.MeridianPatch
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
        label = "VELOCITY", value = patch.velocity, fillColor = fillColor, scheme = scheme,
        onValueChange = { onChange(MeridianSynth.withVelocity(patch, it.coerceIn(0f, 1f))) },
    )
    TapeText("Root is the base note; tune transposes it.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    TapeText(MeridianSynth.holdCaption(patch), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    if (patch.model >= 2) {
        TapeText("Hold 97.5–100%: fixed-alignment sustain.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    }
    if (MeridianSynth.isHeld(patch)) {
        TapeText("Audition and SEND TO PAD play a short capture. MAKE INSTRUMENT keeps sustain.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}

internal fun meridianInstrumentReadiness(patch: MeridianPatch): String =
    if (patch.model < 2) "This original sound is finite. Load a current preset for sustain."
    else "Set hold to 97.5% or above to make a sustained instrument."
