package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.audio.PadSound
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.KitBuilderModel
import java.io.File

/** The KIT screen: one bank of the open kit, audible. */
@Composable
fun KitScreen(model: KitBuilderModel, sound: PadSound, onHit: (Int) -> Unit) {
    val s = LocalScheme.current
    val bank = model.bank(0)

    // Every pad in the bank is loaded once, when the kit opens. The reset
    // matters: loads are keyed by slot, so without it pad 1 of this kit
    // would still be playing pad 1 of the last one.
    LaunchedEffect(model.kitDir) {
        sound.reset()
        for (pad in bank) {
            if (pad != null) sound.load(pad.slot, File(model.kitDir, pad.sampleFile))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = model.name, right = "BANK A")

        val empty = model.emptyStateLine
        if (empty != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    text = empty,
                    style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
                )
            }
        }

        // `model.bank(0)` already returns slots 1..16 in order, nulls for
        // empty pads — exactly what PadGrid indexes.
        PadGrid(pads = bank) { slot ->
            sound.play(slot)
            onHit(slot)
        }
    }
}
