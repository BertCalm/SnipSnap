package com.snipsnap.app.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three bevel states of a TapeOS control. Two-pixel edges, light from
 * the top-left — the 1996 convention the whole design system is built on.
 *
 * Each takes its colours from [LocalScheme], so a control written once
 * looks right in all six schemes.
 */
private fun Modifier.bevel(
    body: Color,
    topLeft: Color,
    bottomRight: Color,
    inset: Dp,
): Modifier = this.drawBehind {
    val w = inset.toPx()
    drawRect(color = body)
    // Top and left edges.
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(size.width, w))
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(w, size.height))
    // Bottom and right edges.
    drawRect(
        color = bottomRight,
        topLeft = Offset(0f, size.height - w),
        size = Size(size.width, w),
    )
    drawRect(
        color = bottomRight,
        topLeft = Offset(size.width - w, 0f),
        size = Size(w, size.height),
    )
}

// All three add exactly `inset` to the content box in each axis — only
// *which* side differs. That is what makes a press look like a press:
// the content travels down-right by `inset` while the node's measured
// size never changes. Pad only the pressed state and a wrap-content
// button grows 2dp when you touch it and shrinks when you let go, which
// reads as a flinch rather than a click.

/** A button at rest: light top-left, dark bottom-right. */
@Composable
fun Modifier.raised(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.gray.toColor(), s.grayHi.toColor(), s.grayDark.toColor(), inset)
        .padding(bottom = inset, end = inset)
}

/** The same button held down: the light source flips, content shifts in. */
@Composable
fun Modifier.pressed(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.grayMid.toColor(), s.grayDark.toColor(), s.grayHi.toColor(), inset)
        .padding(top = inset, start = inset)
}

/** A well: lists, fields, anything content sits *inside*. */
@Composable
fun Modifier.sunken(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.field.toColor(), s.grayDark.toColor(), s.grayEdge.toColor(), inset)
        .padding(bottom = inset, end = inset)
}
