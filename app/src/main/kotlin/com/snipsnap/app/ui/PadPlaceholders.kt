package com.snipsnap.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.PlaceholderPulse

/*
 * What stands where a starter kit's pads will be while the kit renders.
 *
 * A starter takes a few seconds of offline DSP (a minute on a slow phone),
 * and until it lands the pad screen used to show whatever kit was open
 * before it, or the empty-state panel, with only the dock saying that
 * anything was happening. This draws the shape of the thing that is coming
 * instead: four rows of four pads, at the grid's own pad height and gap,
 * pulsing in a wave. It is not the kit screen's whole layout (the bank row
 * and the strip come with the kit); it is the part the eye is waiting on.
 * The card that says what is loading is `LoadingCard`, laid over it.
 */

/** Rows and columns of the pad grid, as `KitScreen` draws a bank: 4 x 4. */
private const val GRID_SIDE = 4

/** The corner of a placeholder tile: the same barely-rounded 2 dp as every raised surface. */
private val TILE_RADIUS = 2.dp

/**
 * The pad grid of a kit not yet there: [GRID_SIDE] rows of [GRID_SIDE]
 * pad-shaped tiles at the grid's own pad height and gap.
 *
 * Each tile's fill is the scheme's raised surface part of the way to its ink
 * ([PlaceholderPulse.amount]), under a 1 dp gray edge line, and the amount
 * runs as a wave: one loop of [Motion.PLACEHOLDER_PULSE_MS] times two, each
 * tile a sixteenth behind the one before it in reading order, so the swing
 * is a few tens of levels of 255 and crosses the grid. (The first version
 * breathed the alpha of a fill that was nearly the stage's own colour and
 * moved about three levels, which no one saw.) Under reduced motion every
 * tile holds [PlaceholderPulse.STILL], the middle of the swing. The moving
 * form is a composable of its own, so the still branch composes no infinite
 * transition at all.
 *
 * It says nothing to TalkBack and carries no text: it is the skeleton, and
 * what is loading is named by the card over it - which appears only once the
 * job has run [com.snipsnap.shell.BusyLatch.SHOW_AFTER_MS], so before then
 * this is all there is, and silent.
 */
@Composable
fun PadPlaceholderGrid(modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        PlaceholderTiles(modifier) { PlaceholderPulse.STILL }
    } else {
        PulsingTiles(modifier)
    }
}

/** The moving form: one phase 0..1 over two pulses, linear and repeating, each tile reading it at its own offset. */
@Composable
private fun PulsingTiles(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "placeholderPulse")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2 * Motion.PLACEHOLDER_PULSE_MS, easing = LinearEasing)),
        label = "placeholderPulsePhase",
    )
    // Read inside the draw, so a frame redraws the tiles and recomposes nothing.
    PlaceholderTiles(modifier) { tile -> PlaceholderPulse.amount(phase.value, tile) }
}

/** [GRID_SIDE] rows of [GRID_SIDE] tiles, tile `row * GRID_SIDE + col` filled [amount] of the way to the ink. */
@Composable
private fun PlaceholderTiles(modifier: Modifier, amount: (tile: Int) -> Float) {
    val scheme = LocalScheme.current
    val raised = scheme.raised.tape
    val ink = scheme.ink.tape
    val edge = scheme.grayEdge.tape
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
        repeat(GRID_SIDE) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                repeat(GRID_SIDE) { col ->
                    val tile = row * GRID_SIDE + col
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Layout.KIT_PAD_H.dp)
                            .drawBehind {
                                drawRoundRect(lerp(raised, ink, amount(tile)), cornerRadius = CornerRadius(TILE_RADIUS.toPx()))
                            }
                            .border(1.dp, edge, RoundedCornerShape(TILE_RADIUS)),
                    )
                }
            }
        }
    }
}
