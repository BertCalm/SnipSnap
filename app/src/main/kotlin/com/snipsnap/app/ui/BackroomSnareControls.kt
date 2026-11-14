package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.BackroomSnare
import com.snipsnap.synth.Skin
import com.snipsnap.synth.SkinPatch
import com.snipsnap.synth.SkinVoice

/** Explicit recipe loads: changing mode starts its sound, including its own treatment. */
@Composable
internal fun BackroomSnareControls(backroom: Boolean, scheme: Scheme, onLoad: (SkinPatch) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton("ORIGINAL", scheme, enabled = true, modifier = Modifier.weight(1f)) {
                onLoad(SkinPatch("Snare", SkinVoice.SNARE, Skin.defaults(SkinVoice.SNARE)))
            }
            LabButton("BACKROOM · MORE SNAP", scheme, enabled = true, modifier = Modifier.weight(1f)) {
                onLoad(SkinPatch("More Snap", SkinVoice.SNARE, BackroomSnare.defaults(), model = 2))
            }
        }
        if (backroom) TapeText("BACKROOM · 50% RECALLS MORE SNAP", TapeType.pixelSmall, scheme.ink2.tape)
    }
}
