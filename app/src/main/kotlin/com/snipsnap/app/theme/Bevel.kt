package com.snipsnap.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snipsnap.shell.Scheme

/**
 * The TapeOS surface language as modifiers (`docs/MODERN_LOOK.md` §3): the
 * audition pages' forms, a fill and a one-pixel line, no light from
 * anywhere. Every modifier takes the scheme, so the hundreds of call sites
 * that already pass it in draw whichever scheme is open.
 *
 * The `radius` parameters are left from the bevelled look this file used to
 * draw, whose corner was the caller's to choose. The forms here have one
 * corner of their own ([MODERN_RADIUS]), so the parameter is not read; it
 * stays so the call sites that pass one need no edit, and is for a later
 * clean-up to drop.
 */

/** The corner: barely rounded, the page's `.sound-card`. */
private val MODERN_RADIUS = 2.dp

/** A raised working surface: buttons, window chrome, empty pads. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.raisedBevel(
    scheme: Scheme,
    radius: Dp = 4.dp,
    fill: Color? = null,
): Modifier =
    // `.sound-card`: the raised fill, a hairline, a 2dp corner.
    this
        .background(fill ?: scheme.raised.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))

/**
 * The same surface, pushed in — selected menu items, held buttons.
 *
 * Selection is carried by the border and not by the fill: the pressed fill
 * is the field, which is the raised fill or a hair from it, and a state
 * shown by a fill that close is invisible, and colour-only besides
 * (accessibility audit finding 7). So the pressed form is a 2dp
 * [Scheme.accent] line, a weight change and not just a hue one, which reads
 * as pressed even to someone who can't use the hue at all. `ContrastTest`
 * holds the accent against the field fill at the 3:1 graphical-object
 * threshold in every scheme.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.pressedBevel(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier =
    // The page inverts a pressed control (`ink` fill, `gray` text). Every
    // call site here picks its own text colour and most pick `ink` for the
    // selected state, so an inverted fill would swallow the word; the
    // selected form is the field fill under a 2dp accent line instead,
    // which reads as pressed without a second colour the sites would each
    // have to learn. Inverting is a second cut, with the 26 sites' text
    // colours.
    this
        .background(scheme.field.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(2.dp, scheme.accent.tape, RoundedCornerShape(MODERN_RADIUS))

/**
 * A group box (Pad Sheet v2): the window's own surface with a hairline
 * edge, so a pixel legend can sit on the line the way a Win9x group box
 * draws it.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.etchedBox(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier =
    this
        .background(scheme.win.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))

/** A sunken well: lists, fields, the status-bar cells. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.sunkenField(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier =
    // `.note-card textarea`: the field fill and a line, no inset.
    this
        .background(scheme.field.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))

/**
 * The rule between two groups of controls in a strip: one hairline at the
 * left edge (`.voice-strip`).
 *
 * It draws behind, and emits no semantics of its own - grouping is a
 * thing the eye does with a strip it can see all of at once, and a
 * screen reader walking the row tab by tab is not helped by being told
 * about a line between two of them.
 */
fun Modifier.etchedGroove(scheme: Scheme): Modifier = this.drawBehind {
    val line = 1.dp.toPx()
    drawRect(scheme.grayEdge.tape, topLeft = Offset(0f, 0f), size = Size(line, size.height))
}

/**
 * The dark surface where sound lives, scanlines included: a 1px line of
 * 18% black every 3px, drawn behind the content. A raised chip inside an
 * LCD (FULL, + KIT, RECORD, START YOURS, ORBIT) is a thing laid on the
 * glass, and a button with lines through its face reads as dirty rather
 * than as lit; the panel's own text is not struck through either, which at
 * the 12 sp floor is a kindness too. 18 % rather than 30 %, so small text
 * over the stripe loses a third of a stop less (DIRECTION §5).
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.lcdPanel(
    scheme: Scheme,
    radius: Dp = 6.dp,
): Modifier =
    this
        .background(scheme.lcd.tape, RoundedCornerShape(MODERN_RADIUS))
        .drawBehind { drawScanlines(0.18f) }

private fun DrawScope.drawScanlines(alpha: Float) {
    val step = 3.dp.toPx()
    val line = 1.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = Color.Black.copy(alpha = alpha),
            topLeft = Offset(0f, y),
            size = Size(size.width, line),
        )
        y += step
    }
}

/** The window's outer frame: one line of [Scheme.winFrame] (`.sound-card` border). */
@Suppress("UNUSED_PARAMETER")
fun Modifier.windowFrame(scheme: Scheme, radius: Dp = 4.dp): Modifier =
    this
        .background(scheme.gray.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.winFrame.tape, RoundedCornerShape(MODERN_RADIUS))

/**
 * The desk behind the window: the page's flat `desk1` lit from one corner
 * by the sun - `deskGlow` fading out over the width of a phone. No checker
 * and no ray wedges; the glow is the whole of it.
 */
@Composable
fun rememberDeskBrush(scheme: Scheme): Brush = remember(scheme.id) {
    Brush.radialGradient(
        colors = listOf(scheme.deskGlow.tape, scheme.desk1.tape),
        center = Offset(0f, 0f),
        radius = 900f,
    )
}
