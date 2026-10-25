package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.amberOnChrome
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.StrikeStrip
import kotlin.math.roundToInt

/** One live object belongs to KIT; its normal grid and fullscreen popup read the same gesture. */
@Stable
internal class StrikeState(
    enabled: Boolean = false,
    right: Boolean = false,
    returns: Boolean = false,
    volume: Boolean = false,
    strength: Float = StrikeStrip.DEFAULT_STRENGTH,
) {
    var enabled by mutableStateOf(enabled)
    var right by mutableStateOf(right)
    var returns by mutableStateOf(returns)
    var volume by mutableStateOf(volume)
    var strength by mutableFloatStateOf(strength)

    fun hit(touchVelocity: Float): StrikeStrip.Hit = StrikeStrip.hit(touchVelocity, enabled, strength, volume)
    fun releaseTouch() { if (returns) strength = 0f }

    companion object {
        val Saver = listSaver<StrikeState, Any>(
            save = { listOf(it.enabled, it.right, it.returns, it.volume, it.strength) },
            restore = { StrikeState(it[0] as Boolean, it[1] as Boolean, it[2] as Boolean, it[3] as Boolean, it[4] as Float) },
        )
    }
}

/** Four short controls fit both a narrow phone and the fullscreen header. */
@Composable
internal fun StrikeControls(state: StrikeState, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    @Composable
    fun Control(text: String, label: String, active: Boolean, modifier: Modifier, click: () -> Unit) {
        Box(
            modifier.heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .let { if (active) it.sunkenField(scheme) else it.raisedBevel(scheme) }
                .semantics { selected = active; stateDescription = text }
                .tapeClick(label = label, onClick = click)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(text, TapeType.pixelSmall, scheme.ink.tape, maxLines = 1)
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Control(if (state.enabled) "STRIKE ON" else "STRIKE OFF", "STRIKE STRIP", state.enabled, Modifier.weight(1f)) {
            state.releaseTouch()
            state.enabled = !state.enabled
        }
        Control(if (state.right) "RIGHT" else "LEFT", "STRIKE THUMB SIDE", state.right, Modifier.weight(1f)) {
            state.releaseTouch()
            state.right = !state.right
        }
        Control(if (state.returns) "RETURN" else "STAY", "STRIKE ON RELEASE", state.returns, Modifier.weight(1f)) {
            state.returns = !state.returns
        }
        Control(if (state.volume) "VOL ON" else "VOL OFF", "STRIKE CHANGES VOLUME", state.volume, Modifier.weight(1f)) {
            state.volume = !state.volume
        }
    }
}

/** The caller supplies the grid's height; enabling the strip only takes a little horizontal space. */
@Composable
internal fun StrikePadArea(state: StrikeState, modifier: Modifier = Modifier, pads: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
        if (state.enabled && !state.right) StrikeFader(state, Modifier.width(60.dp).fillMaxHeight())
        Box(Modifier.weight(1f)) { pads() }
        if (state.enabled && state.right) StrikeFader(state, Modifier.width(60.dp).fillMaxHeight())
    }
}

@Composable
internal fun StrikeFader(state: StrikeState, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("STRIKE", TapeType.pixelSmall, scheme.ink2.tape)
        TapeText("${(state.strength * 100).roundToInt()}%", TapeType.lcdSmall, scheme.amberOnChrome)
        Box(
            Modifier.weight(1f).fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .semantics {
                    contentDescription = "STRIKE STRENGTH"
                    stateDescription = "${(state.strength * 100).roundToInt()} percent, top hard, bottom soft"
                    progressBarRangeInfo = ProgressBarRangeInfo(state.strength, 0f..1f)
                    setProgress { value ->
                        if (value.isFinite()) { state.strength = value.coerceIn(0f, 1f); true } else false
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else {
                        val value = when (event.key) {
                            Key.DirectionUp, Key.DirectionRight -> state.strength + 0.05f
                            Key.DirectionDown, Key.DirectionLeft -> state.strength - 0.05f
                            Key.MoveHome -> 0f
                            Key.MoveEnd -> 1f
                            else -> null
                        }
                        if (value == null) false else { state.strength = value.coerceIn(0f, 1f); true }
                    }
                }
                .focusable()
                // Key on the owner, never on the value: a moving thumb must
                // not cancel/restart its pointer coroutine on recomposition.
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inset = minOf(28.dp.toPx(), size.height / 4f)
                        fun move(y: Float) {
                            state.strength = StrikeStrip.strengthAt(y - inset, size.height - 2f * inset)
                        }
                        down.consume()
                        move(down.position.y)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                move(change.position.y)
                                change.consume()
                            }
                        } finally {
                            // Cancellation (backgrounding or leaving fullscreen)
                            // must release a returning strip just like finger-up.
                            state.releaseTouch()
                        }
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val inset = minOf(28.dp.toPx(), size.height / 4f)
                val travel = size.height - 2f * inset
                val y = inset + travel * (1f - state.strength)
                drawLine(scheme.grayEdge.tape, Offset(center.x, inset), Offset(center.x, size.height - inset), 5.dp.toPx())
                drawLine(scheme.lcdInk.tape, Offset(center.x, y), Offset(center.x, size.height - inset), 5.dp.toPx())
                for (tick in 1..3) {
                    val at = inset + travel * tick / 4f
                    drawLine(scheme.ink2.tape, Offset(10.dp.toPx(), at), Offset(16.dp.toPx(), at), 1.dp.toPx())
                }
                drawRoundRect(
                    scheme.lcdInk.tape,
                    topLeft = Offset(8.dp.toPx(), y - 5.dp.toPx()),
                    size = Size(size.width - 16.dp.toPx(), 10.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            TapeText("HARD", TapeType.pixelSmall, scheme.lcdInk.tape, Modifier.align(Alignment.TopCenter).padding(top = 5.dp))
            TapeText("SOFT", TapeType.pixelSmall, scheme.lcdInk.tape, Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp))
        }
    }
}
