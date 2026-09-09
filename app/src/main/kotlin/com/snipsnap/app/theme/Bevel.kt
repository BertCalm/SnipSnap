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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 */

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
): Modifier = this
    .background(fill ?: scheme.gray.tape, RoundedCornerShape(radius))
    .border(2.dp, bevelBrush(scheme, raised = true), RoundedCornerShape(radius))

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
 * accent-vs-fill ratios (4.08-11.28:1 across all 8 schemes, clearing the
 * 3:1 graphical-object threshold everywhere).
 */
fun Modifier.pressedBevel(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = this
    .background(Schemes.darken(scheme.gray, 0.10f).tape, RoundedCornerShape(radius))
    .border(
        3.dp,
        Brush.linearGradient(listOf(Schemes.darken(scheme.accent, 0.55f).tape, scheme.accent.tape)),
        RoundedCornerShape(radius),
    )

/**
 * A group box (Pad Sheet v2): the window's own surface with an etched
 * 2dp edge - light from the top-left falls into it - so a pixel legend
 * can sit on the line the way a Win9x group box draws it.
 */
fun Modifier.etchedBox(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = this
    .background(scheme.win.tape, RoundedCornerShape(radius))
    .border(2.dp, bevelBrush(scheme, raised = false), RoundedCornerShape(radius))

/** A sunken well: lists, fields, the status-bar cells. */
fun Modifier.sunkenField(
    scheme: Scheme,
    radius: Dp = 4.dp,
): Modifier = this
    .background(scheme.field.tape, RoundedCornerShape(radius))
    .border(1.dp, bevelBrush(scheme, raised = false), RoundedCornerShape(radius))

/**
 * The dark surface where sound lives, scanlines included: a 1px line of
 * 30% black every 3px, drawn over the content so text glows through it
 * the way the prototypes render.
 */
fun Modifier.lcdPanel(
    scheme: Scheme,
    radius: Dp = 6.dp,
): Modifier = this
    .background(scheme.lcd.tape, RoundedCornerShape(radius))
    .drawWithContent {
        drawContent()
        val step = 3.dp.toPx()
        val line = 1.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = 0.30f),
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
    if (scheme.id == SchemeId.OILSLICK) {
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
