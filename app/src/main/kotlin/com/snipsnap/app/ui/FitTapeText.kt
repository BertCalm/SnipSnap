package com.snipsnap.app.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * The font-size steps [FitTapeText] walks down, as shares of the style's own size. The first is
 * the style as designed; the last is the floor, below which the word is ellipsized instead of
 * shrunk further. Five steps from 1.0 to 0.65 keep a 12 sp body label above 7.8 sp, which is as
 * small as a name may go before it is clearer to cut it than to squint at it.
 */
internal val FIT_TEXT_STEPS: List<Float> = listOf(1f, 0.85f, 0.75f, 0.7f, 0.65f)

/**
 * [TapeText] that stays on ONE line: when the text does not fit the width it was given, the
 * font steps down through [FIT_TEXT_STEPS] until it does, and at the floor it ellipsizes.
 *
 * It exists for names that other people chose (a pad's name on the pad grid, a lane's row label
 * in the step editor), where the cell is a fixed size and a long name used to either wrap to a
 * second line and crowd the cell or be cut with no sign it was cut. The step resets when [text]
 * or the style's size changes, so a renamed pad measures afresh; it only ever moves down, which
 * bounds the relayouts at [FIT_TEXT_STEPS].size - 1 per text.
 *
 * Needs a bounded width from its parent (a fixed-width Box, a grid cell). In an unbounded
 * width nothing overflows and it draws at full size.
 */
@Composable
internal fun FitTapeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var step by remember(text, style.fontSize) { mutableIntStateOf(0) }
    val share = FIT_TEXT_STEPS[step]
    val sized = if (share == 1f || !style.fontSize.isSp) style else style.copy(fontSize = (style.fontSize.value * share).sp)
    BasicText(
        text = text,
        modifier = modifier,
        style = sized.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.didOverflowWidth && step < FIT_TEXT_STEPS.lastIndex) step += 1 },
    )
}
