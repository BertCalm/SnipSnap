package com.snipsnap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Bouncer
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.app.ui.tapeClick
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.SnipStore

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
    /** HOLD a block: the track goes back to empty. Nothing on screen can say this, so [Copy.LOOP_LEGEND] does. */
    onClearTrack: (Int) -> Unit,
    /** BOUNCE: what the grid is doing, rendered offline into SNIPS. */
    onBounce: () -> Unit,
    /** True while that render runs — it is seconds of work, and the button says so rather than looking dead. */
    bouncing: Boolean = false,
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
                    onClear = { onClearTrack(t) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = LayoutArrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text = Copy.LOOP_LEGEND,
                color = Tape.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            BounceButton(session = session, bouncing = bouncing, onBounce = onBounce)
        }
    }
}

/**
 * BOUNCE, with the length it will actually render on it.
 *
 * The number is [Bouncer.intervalsWithin]'s, not the raw cycle: a cycle is the
 * least common multiple of the chain lengths and can run for half an hour,
 * which no snip can hold. `LoopActivity` renders through the same function
 * when the button is pressed, so what is printed here and what lands in SNIPS
 * cannot drift apart — the shared quantity is one function, not one number
 * copied twice.
 */
@Composable
private fun BounceButton(session: Session, bouncing: Boolean, onBounce: () -> Unit) {
    val bars = Bouncer.intervalsWithin(session, SnipStore.IMPORT_MAX_SEC) * session.barsPerInterval
    // A Box with the text centred in it, not a Text with a minimum height:
    // `heightIn` on the text itself makes the box taller and leaves the
    // glyphs at the top of it. `ActionButton` is built this way for the same
    // reason.
    Box(
        modifier = Modifier
            // The app's own minimum touch target, not whatever 10sp plus
            // padding happens to come to: this is the one control on the
            // screen a finger has to find, and every other action row in the
            // app is built to this floor.
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(if (bouncing) Tape.Lcd else Tape.Panel)
            .border(width = 2.dp, color = Tape.BevelDark)
            // Through the app's own wrapper rather than a raw `clickable`: it
            // drops Compose's ripple (TapeOS draws its own feedback) and makes
            // the accessible-name decision explicit. Null, because the text
            // inside this control already says what it does.
            //
            // Enabled even while bouncing, which is the same call SNIPS' own
            // → LOOP makes: a dead button says only "no". This one is already
            // labelled BOUNCING…, and pressing it says a bounce is running and
            // where it will land — which is the answer someone pressing a
            // second time is actually looking for.
            .tapeClick(label = null, onClick = onBounce)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = if (bouncing) Copy.LOOP_BOUNCE_BUSY else "BOUNCE ▸ $bars BARS",
            color = if (bouncing) Tape.Dim else Tape.Ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * LOOP with no session to draw.
 *
 * Reached from the shelf only once a track holds something, so in practice this
 * is the `adb` entry point and the "the sidecar would not parse" case — which
 * is why the caller passes the [line]: those two are the same `null` here and
 * must not read the same on screen. It says something in words rather than
 * drawing an empty grid, because an empty grid looks like a bug.
 */
@Composable
fun LoopEmpty(line: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Tape.Desk).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = line,
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
                // takes no gesture at all.
                filled = !empty,
                onClear = onClear,
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
 * reach.
 *
 * The hold is the cell's ONLY gesture, and the accessibility tree says so.
 * This used to be a `clickable` with an empty lambda — block editing lands in
 * a later plan — which told a screen reader there was something to activate
 * here and then did nothing when it was. `pointerInput` alone would swing too
 * far the other way (the fault `KitsScreen`'s own room row was fixed for:
 * operable by touch, invisible to TalkBack), so the long press is declared as
 * a real semantics action beside it. When a tap does something, it comes back
 * as a real action too.
 */
@Composable
private fun BlockCell(
    label: String,
    lit: Boolean,
    filled: Boolean,
    /**
     * Named for what it does rather than for the gesture: inside the
     * `semantics` block below, a parameter called `onLongClick` would sit in
     * the same scope as `SemanticsPropertyReceiver.onLongClick`, whose own
     * arguments are all optional — so `onLongClick()` there could resolve to
     * either one.
     */
    onClear: () -> Unit,
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
            .pointerInput(filled) {
                if (!filled) return@pointerInput
                detectTapGestures(onLongPress = { onClear() })
            }
            .semantics(mergeDescendants = true) {
                if (filled) onLongClick(label = "CLEAR THIS TRACK") { onClear(); true }
            }
            .padding(4.dp),
    )
}
