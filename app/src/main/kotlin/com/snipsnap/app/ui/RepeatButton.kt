package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A held button waits this long before it runs, then steps every [REPEAT_EVERY_MS]. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 90L

/**
 * An [ActionButton] that steps once on touch and keeps stepping while
 * held — [REPEAT_AFTER_MS] before the run starts, then every
 * [REPEAT_EVERY_MS] — the way a hardware tempo button runs. The pointer
 * loop is KIT's own press-and-hold shape; for a screen reader the button
 * is a plain click that steps once, under [description].
 */
@Composable
internal fun RepeatButton(
    label: String,
    description: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The pointer loop is keyed once; the latest step reaches it through this.
    val step by rememberUpdatedState(onStep)
    Box(
        modifier
            .height(Layout.MIN_HIT_TARGET.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .semantics {
                contentDescription = description
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    step()
                    val run = scope.launch {
                        delay(REPEAT_AFTER_MS)
                        while (isActive) {
                            step()
                            delay(REPEAT_EVERY_MS)
                        }
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                    }
                    run.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink2.tape)
    }
}
