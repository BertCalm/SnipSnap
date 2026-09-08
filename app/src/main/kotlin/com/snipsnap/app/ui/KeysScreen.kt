package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snipsnap.app.InstrumentPlayer
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.KeysLayout
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Schemes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KEYS: an instrument the shop made, played on the same 4×4 the drums
 * use (UI_DESIGN "keys on the grid") — the instrument's root on A01,
 * ascending left to right and bottom to top, LAYOUT the chromatic or a
 * scale, OCTAVE moving the whole grid. Press to sound, let go to
 * release: a looped pad holds while the finger does. Over
 * `InstrumentPlayer`, `:shell`'s `InstrumentEngine` on the device.
 */
private val GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
fun KeysScreen(
    sidecar: File,
    instrument: InstrumentStore.Instrument,
    onBack: () -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    // Keyed on both: a re-read sidecar (same file, new contents) reloads the
    // player. The zones' WAVs are read off the main thread; until they land
    // a key plays nothing, never a stale instrument.
    val player = remember(sidecar, instrument) { InstrumentPlayer(context) }
    DisposableEffect(player) { onDispose { player.close() } }
    LaunchedEffect(player) {
        withContext(Dispatchers.IO) { player.open(sidecar, instrument) }
    }

    var layout by remember(sidecar) { mutableStateOf(KeysLayout.DEFAULT_LAYOUT) }
    var octave by remember(sidecar) { mutableIntStateOf(0) }
    val root = instrument.rootNote

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(instrument.name.uppercase(java.util.Locale.ROOT), TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f, fill = false))
            TapeText("ROOT ${KeysLayout.label(root)}  OCT ${if (octave >= 0) "+" else ""}$octave", TapeType.lcdSmall, scheme.amber.tape)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("◄ SHELF", scheme, enabled = true, onClick = { player.allOff(); onBack() })
            ActionButton("OCT −", scheme, enabled = octave > KeysLayout.OCTAVE_MIN, modifier = Modifier.weight(1f), onClick = { player.allOff(); octave-- })
            ActionButton("OCT +", scheme, enabled = octave < KeysLayout.OCTAVE_MAX, modifier = Modifier.weight(1f), onClick = { player.allOff(); octave++ })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in KeysLayout.LAYOUTS) {
                val selected = l == layout
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        .tapeClick { player.allOff(); layout = l }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(l, TapeType.pixelSmall, if (selected) scheme.titleInk.tape else scheme.ink2.tape, maxLines = 2)
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
            for (row in GRID_ROWS) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                    for (slot in row) {
                        val note = KeysLayout.noteFor(slot, root, layout, octave)
                        val covered = note != null && instrument.zoneFor(note) != null
                        KeyPad(
                            label = note?.let { KeysLayout.label(it) } ?: "—",
                            enabled = covered,
                            onPress = { v -> note?.let { player.noteOn(it, v) } },
                            onRelease = { note?.let { player.noteOff(it) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Touch-Y velocity, like PLAY's pads: the top of the pad is softest. */
private const val MIN_VELOCITY = 0.35f

@Composable
private fun KeyPad(
    label: String,
    enabled: Boolean,
    onPress: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    var down by remember { mutableStateOf(false) }
    val ink = scheme.amber.tape
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .background(ink.copy(alpha = if (down) 0.45f else if (enabled) 0.12f else 0.03f), shape)
            .border(2.dp, if (enabled) ink else ink.copy(alpha = 0.3f), shape)
            .let { m ->
                if (!enabled) m else m.pointerInput(label) {
                    while (true) {
                        val first = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val t = (first.position.y / height).coerceIn(0f, 1f)
                        down = true
                        onPress(MIN_VELOCITY + (1f - MIN_VELOCITY) * t)
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                if (!change.pressed) break
                            }
                        }
                        down = false
                        onRelease()
                    }
                }
            }
            .padding(5.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        TapeText(label, TapeType.marker, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}
