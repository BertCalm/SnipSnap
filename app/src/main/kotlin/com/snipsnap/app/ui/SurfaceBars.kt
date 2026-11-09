package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.snipsnap.app.SurfaceEngine
import com.snipsnap.shell.Places
import com.snipsnap.shell.PrintLength
import com.snipsnap.shell.Scheme

/**
 * SURFACE's print length: the BARS button, now an opener (clarity spec 7.3, "Removed
 * patterns"). It said the current length and a tap stepped to the next of five; now it
 * says the current length with `▾` and a tap opens the five as a sheet, each with the
 * seconds it comes to at the kit's tempo.
 *
 * Its own file, and it holds its own open flag, because SurfaceScreen's composable is
 * far past 256 dex registers and gains no state of its own for this.
 *
 * [bpm] is null for a kit with no tempo, which prints FREE and has nothing to pick.
 */
@Composable
internal fun PrintBarsOpener(
    barsIndex: Int,
    bpm: Float?,
    printing: Boolean,
    scheme: Scheme,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ActionButton(
        if (bpm == null) "NO TEMPO" else Places.opener(PrintLength.label(PrintLength.BARS[barsIndex])),
        scheme,
        enabled = bpm != null && !printing,
        dimmed = barsIndex == 0,
        modifier = modifier,
    ) { open = true }
    if (open && bpm != null) {
        SheetMenu("HOW LONG IS A PRINT?", onDismiss = { open = false }) {
            for ((index, bars) in PrintLength.BARS.withIndex()) {
                val label = PrintLength.label(bars)
                SheetRow(
                    Places.pickedRow(label, index == barsIndex),
                    PrintLength.note(bars, bpm, SurfaceEngine.MAX_PRINT_SECONDS),
                    onClick = { open = false; onPick(index) },
                    modifier = Modifier.semantics { selected = index == barsIndex },
                    accessibilityLabel = label,
                )
            }
        }
    }
}
