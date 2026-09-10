package com.snipsnap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Session

/**
 * TapeOS palette for this screen.
 *
 * Fixed rather than themed for now — this becomes a `Scheme` lookup like every
 * other screen later, and hardcoding one keeps this task about the grid. The
 * roster it will read is `SchemeId`, whose source of truth is
 * `design/Schemes.dc.html`. This comment used to point at `docs/UI_DESIGN.md`
 * instead, naming a set of schemes that no longer exists — see that file's own
 * superseded note.
 */
private object Tape {
    val Desk = Color(0xFF2B2B2B)
    val Panel = Color(0xFFBFBFB4)
    val BevelLight = Color(0xFFE8E8DE)
    val BevelDark = Color(0xFF6E6E64)
    val Lcd = Color(0xFF17251C)
    val Amber = Color(0xFFE8A33A)
    val Ink = Color(0xFF1C1C18)
    val Dim = Color(0xFF8A8A80)
}

/**
 * The six-column grid.
 *
 * Each column is a track; each cell a block in its chain. The cell playing right
 * now is lit amber, which is the only moving part on the screen — the phasing is
 * meant to be read at a glance, not counted.
 */
@Composable
fun LoopGrid(
    session: Session,
    interval: Int,
    onToggleTrack: (Int) -> Unit,
    onSelectBlock: (trackIndex: Int, blockIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().background(Tape.Desk).padding(8.dp),
        horizontalArrangement = LayoutArrangement.spacedBy(6.dp),
    ) {
        session.tracks.forEachIndexed { t, track ->
            TrackColumn(
                track = track,
                playing = Arrangement.indexAt(track.chain.size, interval),
                onToggle = { onToggleTrack(t) },
                onSelect = { b -> onSelectBlock(t, b) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackColumn(
    track: com.snipsnap.loop.Track,
    playing: Int,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = LayoutArrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrackHeader(name = track.name, engaged = track.engaged, onToggle = onToggle)
        track.chain.forEachIndexed { b, _ ->
            BlockCell(
                label = "${b + 1}",
                lit = track.engaged && b == playing,
                onClick = { onSelect(b) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrackHeader(name: String, engaged: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.Text(
        text = name.uppercase(),
        color = if (engaged) Tape.Panel else Tape.Dim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(Tape.Lcd)
            .border(width = 1.dp, color = Tape.BevelDark)
            .clickable { onToggle() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

/**
 * One block.
 *
 * Raised bevel when idle, lit when playing. Square corners and no shadow: the
 * TapeOS rule is that depth comes from bevels, never from blur.
 */
@Composable
private fun BlockCell(
    label: String,
    lit: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = label,
        color = if (lit) Tape.Ink else Tape.Ink.copy(alpha = 0.55f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .aspectRatio(1.2f)
            .background(if (lit) Tape.Amber else Tape.Panel)
            .border(width = 2.dp, color = if (lit) Tape.BevelLight else Tape.BevelDark)
            .clickable { onClick() }
            .padding(4.dp),
    )
}
