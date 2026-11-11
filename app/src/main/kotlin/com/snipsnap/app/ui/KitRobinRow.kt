package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitRobin
import com.snipsnap.shell.Scheme
import kotlin.math.roundToInt

/**
 * The kit-wide ROUND ROBIN row on the KIT screen: the switch, the kit's AMOUNT and TAKES
 * (what every pad that follows the kit is dealt), and UNDO for the last dial change. UNDO is
 * shown only once the dial has moved, and re-deals from the stored previous settings (the
 * deal is deterministic, so that restores the old bytes). The work itself is the caller's.
 */
@Composable
internal fun KitRobinRow(
    robin: KitRobin?,
    /** Resets the remembered UNDO when the screen moves to another kit. */
    kitKey: Any?,
    busy: Boolean,
    scheme: Scheme,
    onOn: () -> Unit,
    onSet: (KitRobin) -> Unit,
    onOff: () -> Unit,
) {
    val on = robin != null
    var previous by remember(kitKey) { mutableStateOf<KitRobin?>(null) }
    // Keyed on the kit only: the slider's gesture callbacks outlive a recomposition, so the
    // pending values live in state that survives the kit's robin changing underneath them,
    // and follow it through the effect below.
    var pendingAmount by remember(kitKey) { mutableFloatStateOf(robin?.amount ?: KitRobin.DEFAULT_AMOUNT) }
    var pendingTakes by remember(kitKey) { mutableIntStateOf(robin?.takes ?: KitRobin.DEFAULT_TAKES) }
    val classColor = scheme.amber.tape
    val liveRobin by rememberUpdatedState(robin)
    LaunchedEffect(robin) {
        robin?.let {
            pendingAmount = it.amount
            pendingTakes = it.takes
        }
    }

    // The change in flight: what was asked for and the UNDO to put back if it didn't land.
    // Settled once the work is over (busy is down); a refused or failed change
    // that left the kit's settings elsewhere puts the dial and UNDO back as they were.
    var attempt by remember(kitKey) { mutableStateOf<KitRobin?>(null) }
    var attemptPrevious by remember(kitKey) { mutableStateOf<KitRobin?>(null) }
    LaunchedEffect(busy, robin, attempt) {
        // The caller raises its busy flag in the same step as the callback, so the first frame
        // after a submit already reads busy (or the work is already over).
        if (!busy && attempt != null) {
            if (robin != attempt) {
                previous = attemptPrevious
                robin?.let {
                    pendingAmount = it.amount
                    pendingTakes = it.takes
                }
            }
            attempt = null
        }
    }

    fun submit(next: KitRobin, undoAfter: KitRobin?, undoIfFailed: KitRobin?) {
        attempt = next
        attemptPrevious = undoIfFailed
        previous = undoAfter
        onSet(next)
    }

    fun commit() {
        val current = liveRobin ?: return
        val next = KitRobin(pendingAmount, pendingTakes)
        if (next == current) return
        submit(next, undoAfter = current, undoIfFailed = previous)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(
                "KIT ROUND ROBIN", on, classColor, scheme,
                enabled = !busy, modifier = Modifier.weight(2f),
                onToggle = {
                    previous = null
                    if (on) onOff() else onOn()
                },
            )
            // Only offered once the dial has moved.
            val back = previous
            if (on && back != null) {
                ActionButton(
                    "UNDO", scheme, enabled = !busy,
                    accessibilityLabel = "KIT ROUND ROBIN UNDO", modifier = Modifier.weight(1f),
                    onClick = { submit(back, undoAfter = null, undoIfFailed = back) },
                )
            }
        }
        StepperSlider(
            label = "AMOUNT",
            fraction = pendingAmount,
            valueText = "%d".format(java.util.Locale.ROOT, (pendingAmount * 100).roundToInt()),
            fillColor = classColor, scheme = scheme, enabled = !busy && on,
            accessibilityLabel = "KIT ROUND ROBIN AMOUNT",
            onFractionChange = { f -> pendingAmount = (f * 100).roundToInt() / 100f },
            onFractionCommit = ::commit,
        )
        StepperSlider(
            label = "ALTERNATES",
            fraction = ((pendingTakes - KitRobin.MIN_TAKES).toFloat() / (KitRobin.MAX_TAKES - KitRobin.MIN_TAKES)).coerceIn(0f, 1f),
            valueText = "%d".format(java.util.Locale.ROOT, pendingTakes),
            fillColor = classColor, scheme = scheme, enabled = !busy && on,
            accessibilityLabel = "KIT ROUND ROBIN ALTERNATES",
            onFractionChange = { f ->
                pendingTakes = KitRobin.MIN_TAKES + (f * (KitRobin.MAX_TAKES - KitRobin.MIN_TAKES)).roundToInt()
            },
            onFractionCommit = ::commit,
        )
    }
}
