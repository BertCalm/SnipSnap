package com.snipsnap.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A pad's finger: [onPress] the moment it goes down, [onRelease] when it
 * lifts, and, the reason this exists, [onRelease] if the pad is torn down
 * with the finger still on it.
 *
 * Each finger is followed by its own id, so a second finger on another pad
 * never ends this one's press. Written with [awaitEachGesture] rather than
 * a loop of separate `awaitPointerEventScope` blocks: the loop leaves a gap
 * between one gesture's end and the next wait in which an event can be
 * missed, and a gesture cancelled outright (the screen goes fullscreen, the
 * grid is rebuilt, a kit swap) skipped the lines after the wait. For a
 * pad that sounds a held note, that skipped line was the note's release: a
 * note left ringing with nobody touching it. The release is in a `finally`
 * for that reason.
 *
 * [onPress] gets the touch's y and the pad's height, for the velocity the
 * screen takes from where on the pad the finger landed, and the down event's
 * own `uptimeMillis`; [onRelease] gets the up event's (or, if the press was
 * cancelled, the last event's). The lambdas are read when each gesture
 * starts, so a caller that changes them wants them behind
 * `rememberUpdatedState`, as before. [key] restarts the tracking, and a
 * press in progress is released first.
 */
internal fun Modifier.pressTracking(
    key: Any?,
    onPress: (y: Float, height: Float, uptimeMillis: Long) -> Unit,
    onRelease: (uptimeMillis: Long) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress(down.position.y, size.height.toFloat().coerceAtLeast(1f), down.uptimeMillis)
        var last = down.uptimeMillis
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                last = change.uptimeMillis
                if (!change.pressed) break
            }
        } finally {
            onRelease(last)
        }
    }
}
