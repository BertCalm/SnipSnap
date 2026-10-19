package com.snipsnap.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Places
import kotlinx.coroutines.delay

/**
 * Whether this process has had its boot yet - the one thing that tells a
 * cold start from a rotation. The activity is rebuilt on a config change
 * and its `remember`s with it, so a flag in composition would replay the
 * boot every time the phone turned; a flag on the process does not, and a
 * new process (the app swiped away, or killed in the background) is exactly
 * a cold start.
 */
object BootGate {
    private var spent = false

    /** True once per process: the first caller gets the boot, every later one does not. Main thread only, like composition. */
    fun claim(): Boolean {
        if (spent) return false
        spent = true
        return true
    }
}

/**
 * The boot text, drawn on the stage's LCD for the first moments of a cold
 * start: [Copy.BOOT_LINES] one at a time, then [Copy.BOOT_READY]. The same
 * words HELP keeps at its head, in the same LCD face, so the two cannot
 * disagree about what the app says it is doing.
 *
 * It lies over the stage and not in place of it: the title bar, the place
 * row and the dock are already drawn around it, and the screen under it is
 * composing meanwhile, so when the glass fades nothing jumps and nothing
 * waits to load. The whole run is [Places.BOOT_STEP_MS] per line and one
 * more for READY - under [Places.BOOT_MAX_MS] by `PlacesTest`'s count - and
 * a tap anywhere on it ends it early.
 *
 * Input is the point: the glass takes taps only while [booting]. Once it is
 * false - ended, skipped or timed out - the fade is a picture over a stage
 * that already answers touch, so nothing is held for the fade.
 *
 * @param booting true from the first frame of a cold start until it ends.
 * @param onDone called once, by the timer or by a tap; the caller flips
 *   [booting] to false.
 */
@Composable
fun BootText(booting: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val finish by rememberUpdatedState(onDone)
    var shown by remember { mutableIntStateOf(0) }
    val lines = Copy.BOOT_LINES
    LaunchedEffect(booting) {
        if (!booting) return@LaunchedEffect
        // One step per line and one for READY, the last held a step before
        // the glass lifts.
        for (n in 1..lines.size + 1) {
            shown = n
            delay(Places.BOOT_STEP_MS)
        }
        finish()
    }
    // Starts at its target, so a launch that skips the boot draws no glass
    // at all and one that boots starts opaque.
    val alpha by animateFloatAsState(if (booting) 1f else 0f, tween(Places.BOOT_FADE_MS.toInt()), label = "boot")
    if (alpha <= 0f) return
    Column(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .lcdPanel(scheme)
            .let { if (booting) it.tapeClick(label = Places.BOOT_SKIP, onClick = onDone) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEachIndexed { i, line ->
            if (shown > i) TapeText(line, TapeType.lcdSmall, scheme.lcdInk2)
        }
        if (shown > lines.size) {
            Spacer(Modifier.height(4.dp))
            TapeText(Copy.BOOT_READY, TapeType.lcdHeader, scheme.lcdInk.tape)
        }
    }
}
