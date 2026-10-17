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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snipsnap.shell.Look
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * The TapeOS surface language as modifiers.
 *
 * A 90s bevel is light falling from the top-left: raised surfaces catch it
 * on their top-left edge and shadow on the bottom-right; pressed and sunken
 * surfaces invert that. A diagonal gradient border reads exactly as that
 * split at 2dp, and keeps the handoff's rounded corners (square-corner
 * four-line bevels can't).
 *
 * Under `Look.MODERN` (`docs/MODERN_LOOK.md` §3) the same modifiers draw
 * the audition page's forms instead: a fill and a one-pixel line, no light
 * from anywhere. Each keeps its name and signature, so the hundreds of call
 * sites that already pass the scheme in need no edit — the look is a
 * property of the scheme, and the scheme is what they hand over. That is
 * the decision taken 2026-10-15: one app, two skins, drawn here.
 */

/** The modern look's corner: barely rounded, the page's `.sound-card`. */
private val MODERN_RADIUS = 2.dp

/** Whether [scheme] is drawn the modern way. */
private val Scheme.modern: Boolean get() = look == Look.MODERN

private fun bevelBrush(scheme: Scheme, raised: Boolean): Brush {
    val hi = scheme.grayHi.tape
    val dark = scheme.grayDark.tape
    return Brush.linearGradient(if (raised) listOf(hi, dark) else listOf(dark, hi))
}

/** A raised working surface: buttons, window chrome, empty pads. */
fun Modifier.raisedBevel(
    scheme: Scheme,
    radius: Dp = 4.dp,
    fill: Color? = null,
): Modifier = if (scheme.modern) {
    // `.sound-card`: the raised fill, a hairline, a 2dp corner.
    this
        .background(fill ?: scheme.raised.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))
} else {
    this
        .background(fill ?: scheme.gray.tape, RoundedCornerShape(radius))
        .border(2.dp, bevelBrush(scheme, raised = true), RoundedCornerShape(radius))
}

/**
 * The same surface, pushed in — selected menu items, held buttons.
 *
 * Selection state used to be carried by the fill alone: a 10%-darkened
 * background against [raisedBevel]'s plain [Scheme.gray]. Measured
 * contrast between the two fills is 1.02-1.07:1 in every scheme —
 * effectively invisible, and colour-only besides (accessibility audit
 * finding 7). The border below still inverts the bevel's light direction
 * (dark-to-hi instead of hi-to-dark) — that inversion is the house
 * language for "pushed in" and stays — but it's now 3dp instead of 2dp
 * (a weight change, not just a colour one) and tinted through
 * [Scheme.accent] instead of the neutral bevel highlight/shadow, so a
 * selected control reads as a distinctly thicker, distinctly-hued ring
 * even to someone who can't use the hue at all. See `ContrastTest` for
 * accent-vs-fill ratios (4.08-11.28:1 across every scheme, clearing the
 * 3:1 graphical-object threshold everywhere).
 */
fun Modifier.pressedBevel(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = if (scheme.modern) {
    // The page inverts a pressed control (`ink` fill, `gray` text). Every
    // call site here picks its own text colour and most pick `ink` for the
    // selected state, so an inverted fill would swallow the word; the
    // modern selected form is the field fill under a 2dp accent line
    // instead, which reads as pressed without a second colour the sites
    // would each have to learn. Inverting is a second cut, with the 26
    // sites' text colours.
    this
        .background(scheme.field.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(2.dp, scheme.accent.tape, RoundedCornerShape(MODERN_RADIUS))
} else {
    this
        .background(Schemes.darken(scheme.gray, 0.10f).tape, RoundedCornerShape(radius))
        .border(
            3.dp,
            Brush.linearGradient(listOf(Schemes.darken(scheme.accent, 0.55f).tape, scheme.accent.tape)),
            RoundedCornerShape(radius),
        )
}

/**
 * A group box (Pad Sheet v2): the window's own surface with an etched
 * 2dp edge - light from the top-left falls into it - so a pixel legend
 * can sit on the line the way a Win9x group box draws it.
 */
fun Modifier.etchedBox(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = if (scheme.modern) {
    this
        .background(scheme.win.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))
} else {
    this
        .background(scheme.win.tape, RoundedCornerShape(radius))
        .border(2.dp, bevelBrush(scheme, raised = false), RoundedCornerShape(radius))
}

/** A sunken well: lists, fields, the status-bar cells. */
fun Modifier.sunkenField(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = if (scheme.modern) {
    // `.note-card textarea`: the field fill and a line, no inset.
    this
        .background(scheme.field.tape, RoundedCornerShape(MODERN_RADIUS))
        .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(MODERN_RADIUS))
} else {
    this
        .background(scheme.field.tape, RoundedCornerShape(radius))
        .border(1.dp, bevelBrush(scheme, raised = false), RoundedCornerShape(radius))
}

/**
 * The rule a Win9x toolbar draws between two groups of buttons: a 1dp
 * shadow line with a 1dp highlight to its right, so the same top-left
 * light that raises a button reads this as a notch cut into the strip
 * rather than a bar laid on top of it. Dark-then-light is [bevelBrush]'s
 * own sunken order, for the same reason.
 *
 * Two flat rects rather than a two-stop gradient across 2dp: a gradient
 * would interpolate, and what makes a groove read at this width is that
 * the two lines are flat and adjacent.
 *
 * It draws behind, and emits no semantics of its own - grouping is a
 * thing the eye does with a strip it can see all of at once, and a
 * screen reader walking the row tab by tab is not helped by being told
 * about a line between two of them.
 */
fun Modifier.etchedGroove(scheme: Scheme): Modifier = this.drawBehind {
    val line = 1.dp.toPx()
    if (scheme.modern) {
        // `.voice-strip`: one hairline, no notch.
        drawRect(scheme.grayEdge.tape, topLeft = Offset(0f, 0f), size = Size(line, size.height))
    } else {
        drawRect(scheme.grayDark.tape, topLeft = Offset(0f, 0f), size = Size(line, size.height))
        drawRect(scheme.grayHi.tape, topLeft = Offset(line, 0f), size = Size(line, size.height))
    }
}

/**
 * The dark surface where sound lives, scanlines included: a 1px line of
 * 30% black every 3px, drawn over the content so text glows through it
 * the way the prototypes render.
 */
fun Modifier.lcdPanel(
    scheme: Scheme,
    radius: Dp = 6.dp,
): Modifier = this
    .background(scheme.lcd.tape, RoundedCornerShape(if (scheme.modern) MODERN_RADIUS else radius))
    .drawWithContent {
        drawContent()
        val step = 3.dp.toPx()
        val line = 1.dp.toPx()
        // The modern stripe is the sun's: 18 % rather than 30 %, so small
        // text under it loses a third of a stop less (DIRECTION §5).
        val alpha = if (scheme.modern) 0.18f else 0.30f
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

/**
 * OILSLICK's signature sweep (titlebar, window frame, primary-button
 * rims). Compose's sweep shader is available on every supported API, so
 * the handoff's API<33 caveat (about CSS conic angle offsets) needs no
 * branch here.
 */
fun oilslickSweep(): Brush = Brush.sweepGradient(Schemes.OILSLICK_SWEEP.map { it.tape })

/** The window's outer frame: OILSLICK gets its sweep, the rest a bevel. */
fun Modifier.windowFrame(scheme: Scheme, radius: Dp = 4.dp): Modifier =
    if (scheme.modern) {
        // `.sound-card` border: the frame is one line of `winFrame`.
        this
            .background(scheme.gray.tape, RoundedCornerShape(MODERN_RADIUS))
            .border(1.dp, scheme.winFrame.tape, RoundedCornerShape(MODERN_RADIUS))
    } else if (scheme.id == SchemeId.OILSLICK) {
        this
            .background(scheme.gray.tape, RoundedCornerShape(radius))
            .border(3.dp, oilslickSweep(), RoundedCornerShape(radius))
    } else {
        raisedBevel(scheme, radius)
    }

/**
 * The desk checkerboard behind the window, as a tiled 2x2-cell shader —
 * one draw call, not thirty thousand rects. Cell size 3px per the design.
 */
@Composable
fun rememberDeskBrush(scheme: Scheme): Brush = remember(scheme.id) {
    if (scheme.modern) {
        // The page's desk: flat `desk1` lit from one corner by the sun —
        // `deskGlow` fading out over the width of a phone. The checker and
        // the ray wedges are not drawn; the glow is the whole of it.
        return@remember Brush.radialGradient(
            colors = listOf(scheme.deskGlow.tape, scheme.desk1.tape),
            center = Offset(0f, 0f),
            radius = 900f,
        )
    }
    val cell = 3
    val bmp = ImageBitmap(cell * 2, cell * 2)
    val canvas = Canvas(bmp)
    val p1 = Paint().apply { color = scheme.desk1.tape }
    val p2 = Paint().apply { color = scheme.desk2.tape }
    val c = cell.toFloat()
    canvas.drawRect(0f, 0f, c, c, p1)
    canvas.drawRect(c, 0f, c * 2, c, p2)
    canvas.drawRect(0f, c, c, c * 2, p2)
    canvas.drawRect(c, c, c * 2, c * 2, p1)
    ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}
