package com.snipsnap.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.shell.Copy

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
 *
 * A track with nothing sent to it yet draws as one dim, unlit cell: the session
 * always holds six tracks (see [SessionBuilder]), so an empty one has to be
 * shown as empty rather than left out of a grid that is meant to be counted
 * across.
 */
@Composable
fun LoopGrid(
    session: Session,
    interval: Int,
    onToggleTrack: (Int) -> Unit,
    onSelectBlock: (trackIndex: Int, blockIndex: Int) -> Unit,
    /** HOLD a block: the track goes back to empty. Nothing on screen can say this, so [Copy.LOOP_LEGEND] does. */
    onClearTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Tape.Desk).padding(8.dp),
        verticalArrangement = LayoutArrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = LayoutArrangement.spacedBy(6.dp),
        ) {
            session.tracks.forEachIndexed { t, track ->
                TrackColumn(
                    track = track,
                    playing = Arrangement.indexAt(track.chain.size, interval),
                    onToggle = { onToggleTrack(t) },
                    onSelect = { b -> onSelectBlock(t, b) },
                    onClear = { onClearTrack(t) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        androidx.compose.material3.Text(
            text = Copy.LOOP_LEGEND,
            color = Tape.Dim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * LOOP with no session on disk — which, until a snip is sent, is every time.
 *
 * Reached from the shelf only once a track holds something, so in practice this
 * is the `adb` entry point and the "the sidecar would not parse" case. It says
 * which door fills the grid rather than drawing an empty one, because an empty
 * grid looks like a bug.
 */
@Composable
fun LoopEmpty(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Tape.Desk).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = Copy.LOOP_EMPTY,
            color = Tape.Panel,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrackColumn(
    track: com.snipsnap.loop.Track,
    playing: Int,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val empty = SessionBuilder.isEmpty(track)
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
                // An empty track has nothing to clear, and its one cell is a
                // placeholder rather than a block anyone put there — so it
                // takes neither gesture.
                filled = !empty,
                onClick = { if (!empty) onSelect(b) },
                onLongClick = { if (!empty) onClear() },
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
 *
 * HOLD clears the whole track, not this one block: a chain is what a snip
 * became, and half a snip on the grid is not a state worth being able to
 * reach. `combinedClickable` rather than a raw `pointerInput` for the same
 * reason `KitsScreen`'s own room row uses it — it registers real onClick and
 * onLongClick actions for a screen reader, which a drag gesture does not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockCell(
    label: String,
    lit: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = label,
        // Dark ink on the panel, but the LCD green an empty cell draws on is
        // nearly the same value as that ink — so an empty cell takes the dim
        // panel colour instead of a number no one could read.
        color = if (lit) Tape.Ink else if (filled) Tape.Ink.copy(alpha = 0.55f) else Tape.Dim,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .aspectRatio(1.2f)
            .background(if (lit) Tape.Amber else if (filled) Tape.Panel else Tape.Lcd)
            .border(width = 2.dp, color = if (lit) Tape.BevelLight else Tape.BevelDark)
            .combinedClickable(
                enabled = filled,
                onLongClickLabel = "CLEAR THIS TRACK",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(4.dp),
    )
}
