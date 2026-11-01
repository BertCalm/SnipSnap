package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.ArtFont
import com.snipsnap.shell.Mark

/**
 * The one-line SnipSnap lockup for Compose chrome: SNIP, the pad-cluster
 * symbol, SNAP, in Unbounded ExtraBold, the symbol exactly cap height.
 * [height] is the cap height, which is also the symbol's height, as in
 * [Mark.draw]'s LOCKUP: the words are sized through [Mark.CAP_RATIO] and
 * the symbol's bottom sits on their baseline.
 *
 * The words are sized in density pixels, not scaled pixels: the lockup
 * is a drawing with words in it, and the symbol would not follow a font
 * scale. TalkBack hears the one word "SNIPSNAP", as it did when the bar
 * carried a wordmark. The row measures itself unbounded so a crowded bar
 * never ellipsises it; the project name yields first.
 */
@Composable
fun MarkLockup(height: Dp, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val (ink, accent) = Mark.tones(scheme)
    val assets = LocalContext.current.assets
    val family = remember(assets) { FontFamily(Font(ArtFont.UNBOUNDED_EXTRABOLD.resourcePath, assets)) }
    val fontSize = with(LocalDensity.current) { (height / Mark.CAP_RATIO).toSp() }
    val gap = height * Mark.LOCKUP_GAP
    Row(
        modifier
            .wrapContentWidth(unbounded = true)
            .semantics(mergeDescendants = true) { contentDescription = "SNIPSNAP" },
    ) {
        val style = TextStyle(fontFamily = family, fontSize = fontSize)
        BasicText("SNIP", Modifier.alignByBaseline(), style.copy(color = scheme.titleInk.tape), maxLines = 1, softWrap = false)
        Canvas(
            Modifier
                .padding(horizontal = gap)
                .size(height)
                .alignBy { it.measuredHeight },
        ) {
            val tones = intArrayOf(accent, ink, ink, accent)
            Mark.symbol(0f, 0f, size.height).forEachIndexed { i, d ->
                val path = Path().apply {
                    moveTo(d.cx, d.cy - d.r)
                    lineTo(d.cx + d.r, d.cy)
                    lineTo(d.cx, d.cy + d.r)
                    lineTo(d.cx - d.r, d.cy)
                    close()
                }
                drawPath(path, tones[i].tape)
            }
        }
        BasicText("SNAP", Modifier.alignByBaseline(), style.copy(color = accent.tape), maxLines = 1, softWrap = false)
    }
}
