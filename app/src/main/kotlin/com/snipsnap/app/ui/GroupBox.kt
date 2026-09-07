package com.snipsnap.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.etchedBox
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme

/**
 * A Win9x group box, TapeOS-sized (Pad Sheet v2): a pixel [legend] on
 * the etched edge, a 44dp strip reading [summary] - what the pad already
 * carries from this bench - with a drawn chevron, and [content] below it
 * only while [open]. The design language's own rule: group boxes with
 * pixel-font legends replace cards.
 */
@Composable
fun GroupBox(
    legend: String,
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    scheme: Scheme,
    enabled: Boolean = true,
    summaryColor: Color = scheme.ink2.tape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .etchedBox(scheme)
                .padding(top = 6.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .let { if (enabled) it.tapeClick(onToggle) else it }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TapeText(summary, TapeType.pixelSmall, summaryColor, Modifier.weight(1f), maxLines = 1)
                Chevron(open, scheme.ink2.tape)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content,
                )
            }
        }
        // The legend sits on the edge, on the window's own colour, so the
        // etched line breaks under it.
        TapeText(
            legend,
            TapeType.pixelSmall,
            scheme.ink2.tape,
            Modifier.padding(start = 10.dp).background(scheme.win.tape).padding(horizontal = 4.dp),
            maxLines = 1,
        )
    }
}

/** A drawn chevron: down while open, right while closed. Never a glyph. */
@Composable
private fun Chevron(open: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val s = size.minDimension
        val path = Path()
        if (open) {
            path.moveTo(s * 0.25f, s * 0.375f)
            path.lineTo(s * 0.5f, s * 0.625f)
            path.lineTo(s * 0.75f, s * 0.375f)
        } else {
            path.moveTo(s * 0.375f, s * 0.25f)
            path.lineTo(s * 0.625f, s * 0.5f)
            path.lineTo(s * 0.375f, s * 0.75f)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Two reels turning on an LCD strip beside [text] - the one motion rule,
 * reels spin whenever audio moves. OUTSIDE shows it while a trip is out,
 * so a five-second wait is visibly alive rather than a dead button.
 */
@Composable
fun ReelsStrip(text: String, scheme: Scheme, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "reels")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "reelAngle",
    )
    val ink = scheme.amber.tape
    Row(
        modifier.fillMaxWidth().height(28.dp).lcdPanel(scheme).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(width = 40.dp, height = 16.dp)) {
            val r = 6.dp.toPx()
            val stroke = 1.5.dp.toPx()
            val cy = size.height / 2f
            val left = Offset(8.dp.toPx(), cy)
            val right = Offset(size.width - 8.dp.toPx(), cy)
            for (c in listOf(left, right)) {
                drawCircle(color = ink, radius = r, center = c, style = Stroke(width = stroke))
                rotate(degrees = angle, pivot = c) {
                    drawLine(color = ink, start = Offset(c.x, c.y - r), end = Offset(c.x, c.y + r), strokeWidth = stroke)
                    drawLine(color = ink, start = Offset(c.x - r, c.y), end = Offset(c.x + r, c.y), strokeWidth = stroke)
                }
            }
            drawLine(
                color = ink,
                start = Offset(left.x + r, cy),
                end = Offset(right.x - r, cy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
            )
        }
        TapeText(text, TapeType.lcdSmall, ink, Modifier.weight(1f), maxLines = 1)
    }
}
