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
 * only offered once the dial has moved, and re-deals from the stored previous settings (the
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
    val liveRobin by rememberUpdatedState(robin)
    LaunchedEffect(robin) {
        robin?.let {
            pendingAmount = it.amount
            pendingTakes = it.takes
        }
    }
    val classColor = scheme.amber.tape

    fun commit() {
        val current = liveRobin ?: return
        val next = KitRobin(pendingAmount, pendingTakes)
        if (next == current) return
        previous = current
        onSet(next)
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
            ActionButton(
                "UNDO", scheme, enabled = !busy && on && previous != null,
                accessibilityLabel = "KIT ROUND ROBIN UNDO", modifier = Modifier.weight(1f),
                onClick = {
                    val back = previous ?: return@ActionButton
                    previous = null
                    onSet(back)
                },
            )
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
            label = "TAKES",
            fraction = ((pendingTakes - KitRobin.MIN_TAKES).toFloat() / (KitRobin.MAX_TAKES - KitRobin.MIN_TAKES)).coerceIn(0f, 1f),
            valueText = "%d".format(java.util.Locale.ROOT, pendingTakes),
            fillColor = classColor, scheme = scheme, enabled = !busy && on,
            accessibilityLabel = "KIT ROUND ROBIN TAKES",
            onFractionChange = { f ->
                pendingTakes = KitRobin.MIN_TAKES + (f * (KitRobin.MAX_TAKES - KitRobin.MIN_TAKES)).roundToInt()
            },
            onFractionCommit = ::commit,
        )
    }
}
