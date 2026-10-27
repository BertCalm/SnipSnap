package com.snipsnap.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion

/*
 * What stands where a starter kit's pads will be while the kit renders.
 *
 * A starter takes a few seconds of offline DSP, and until it lands the pad
 * screen used to show whatever kit was open before it, or the empty-state
 * panel, with only the dock saying that anything was happening. This draws
 * the shape of the thing that is coming instead: a header line and four
 * rows of four pads, at the grid's own pad height and gap, breathing. It is
 * not the kit screen's whole layout (the bank row and the strip come with
 * the kit); it is the part the eye is waiting on.
 */

/** The lowest alpha a placeholder pad breathes down to. */
private const val PULSE_LOW = 0.45f

/** The alpha a placeholder pad holds under reduced motion: between the two ends of the breath. */
private const val PULSE_STILL = 0.6f

/** Rows and columns of the pad grid, as `KitScreen` draws a bank: 4 x 4. */
private const val GRID_SIDE = 4

/**
 * The pad grid of the kit called [name], not yet there: [GRID_SIDE] rows of
 * [GRID_SIDE] pad-shaped boxes under a header line, [Copy.loadingBusy]'s own
 * words, the same ones the dock carries for this job.
 *
 * The pads breathe between [PULSE_LOW] and full over [Motion.PLACEHOLDER_PULSE_MS]
 * and hold at [PULSE_STILL] when animations are off, so they still read as
 * "not yet" without moving. The animated form is a private composable of its
 * own, as the busy bar's and the spinner's are, so the still branch composes
 * no infinite transition at all.
 *
 * To TalkBack the whole grid is one node named [Copy.loadingBusy], in
 * progress with no end in sight (`progressSemantics`); the sixteen boxes
 * carry no semantics of their own and are not sixteen things to swipe past.
 */
@Composable
fun PadPlaceholderGrid(name: String, modifier: Modifier = Modifier) {
    val label = Copy.loadingBusy(name)
    Column(
        modifier
            .fillMaxWidth()
            // One modifier chain on one layout node, so the two collapse into
            // the single semantics node a test (and TalkBack) asks.
            .semantics(mergeDescendants = true) { contentDescription = label }
            .progressSemantics(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaceholderHeader(label)
        if (LocalReduceMotion.current) {
            PlaceholderPads { PULSE_STILL }
        } else {
            BreathingPads()
        }
    }
}

/** The header's place: an LCD panel the height of the real header's row, with the loading line in it. */
@Composable
private fun PlaceholderHeader(label: String) {
    val scheme = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .lcdPanel(scheme)
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TapeText(label, TapeType.lcdSmall, scheme.amber.tape, maxLines = 1)
    }
}

/** The moving form: alpha between [PULSE_LOW] and 1 and back, once per [Motion.PLACEHOLDER_PULSE_MS]. */
@Composable
private fun BreathingPads() {
    val transition = rememberInfiniteTransition(label = "placeholderPulse")
    val alpha = transition.animateFloat(
        initialValue = PULSE_LOW,
        targetValue = 1f,
        // Each direction is half a breath, so the full out-and-back is the
        // constant's own duration.
        animationSpec = infiniteRepeatable(
            tween(Motion.PLACEHOLDER_PULSE_MS / 2, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "placeholderPulseAlpha",
    )
    // Read inside the layer, so a frame redraws the pads and recomposes nothing.
    PlaceholderPads { alpha.value }
}

/** [GRID_SIDE] rows of [GRID_SIDE] raised boxes at the grid's own height and gap, each at [alpha]. */
@Composable
private fun PlaceholderPads(alpha: () -> Float) {
    val scheme = LocalScheme.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
        repeat(GRID_SIDE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                repeat(GRID_SIDE) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Layout.KIT_PAD_H.dp)
                            .graphicsLayer { this.alpha = alpha() }
                            .raisedBevel(scheme),
                    )
                }
            }
        }
    }
}
