package com.snipsnap.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Layout

/**
 * The fullscreen landscape view, and the only thing this file is now.
 * PLAY was a mode of PADS, not a place (docs/SCREEN_PLAN_2026_10.md
 * section 5, decision 4): its in-window grid, bank tabs, PANIC, MIDI line
 * and engine moved onto KitScreen, which owns the one PadEngine and
 * VoiceAllocator for the kit. What stays here is the part of PLAY that was
 * different from KIT - both banks at once, 4x4 each, in landscape -
 * because KitScreen opens it from its header's FULL chip inside an in-app
 * Popup and draws it from that same engine, allocator and glow map, so a
 * voice started in either keeps its choke group and its glow. The pad
 * grid itself (`PlayPad`/`PlayBank`/`BankRow` and their row/velocity
 * constants) lives in `PadGrid.kt`, shared with GROOVE.
 *
 * One LCD strip (kit name, the engine's [status] line, EXIT FULL) over
 * [BankRow]. Pure drawing: the hit, the release and the glow are
 * KitScreen's, passed in, so this grid has no engine of its own to drift
 * from the window's.
 */
@Composable
internal fun FullscreenPlayGrid(
    kit: Kit,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    status: String,
    onHit: (Int, Float, Long) -> Unit,
    onRelease: (Int, Long) -> Unit,
    onExit: () -> Unit,
) {
    val scheme = LocalScheme.current

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.win.tape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // A floor, not a height: the 26dp this was (STATUS_BAR_H)
                // clamped the 48dp EXIT FULL chip, the only way out.
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(kit.name, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f, fill = false))
            TapeText(
                status,
                TapeType.lcdSmall,
                scheme.amber.tape,
                Modifier.padding(horizontal = 8.dp),
            )
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "EXIT FULLSCREEN", onClick = onExit)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("EXIT FULL", TapeType.pixel, scheme.ink2.tape)
            }
        }

        BankRow(kit, glow, onHit, onRelease, Modifier.weight(1f).fillMaxWidth())
    }
}
