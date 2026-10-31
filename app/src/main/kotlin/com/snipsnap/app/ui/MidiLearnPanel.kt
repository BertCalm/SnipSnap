package com.snipsnap.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.PREFS
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.MidiBinding
import com.snipsnap.shell.MidiLearn
import com.snipsnap.shell.MidiLearnStore

/**
 * One screen's MIDI learn: the [learn] rules over that screen's bindings,
 * and what its panel shows (the target waiting for a control, the
 * bindings, the last message heard), saved beside every other screen's
 * under [MidiLearnStore.PREFS_KEY] after each learn or forget.
 *
 * A class rather than a screen's own locals so the screens stay small:
 * SURFACE's composable is already the size at which Android's verifier
 * stopped accepting it once a panel's worth of learn state and callbacks
 * was inlined there. Main thread only, like the screens that feed it.
 *
 * @param prefer the mode each target wants of a control learned for it.
 */
@Stable
internal class MidiLearnState(
    private val prefs: SharedPreferences,
    private val screen: String,
    private val prefer: (String) -> MidiBinding.Mode?,
) {
    val learn = MidiLearn(MidiLearnStore.load(prefs.getString(MidiLearnStore.PREFS_KEY, null), screen))

    /** The target waiting for a control, or null. */
    var armed by mutableStateOf<String?>(null)
        private set
    var bindings by mutableStateOf(learn.bindings)
        private set
    var lastHeard by mutableStateOf<String?>(null)
        private set

    /**
     * Whether the panel is on screen: only then is [lastHeard] kept, so a
     * knob swept elsewhere does not recompose the screen per message.
     * Hiding the panel stops a learn still waiting, so the next knob is
     * never bound without the player seeing it.
     */
    var showing = false
        set(value) {
            field = value
            if (!value) arm(null)
        }

    /** Wait for a control for [target], or stop waiting with null. */
    fun arm(target: String?) {
        if (target == null) learn.disarm() else learn.arm(target, prefer(target))
        armed = target
    }

    fun forget(target: String) {
        learn.forget(target)
        arm(null)
        save()
    }

    /** Note [event] for the panel's live line. */
    fun heard(event: MidiWire.Event) {
        if (showing) MidiLearn.heard(event)?.let { lastHeard = it }
    }

    /**
     * [binding] was just learned (null: nothing was): keep it as [settle]
     * says the screen wants it, stop waiting and save.
     */
    fun learned(binding: MidiBinding?, settle: (MidiBinding) -> MidiBinding = { it }) {
        if (binding == null) return
        settle(binding).takeIf { it != binding }?.let(learn::update)
        armed = null
        save()
    }

    private fun save() {
        bindings = learn.bindings
        val saved = MidiLearnStore.save(prefs.getString(MidiLearnStore.PREFS_KEY, null), screen, learn.bindings)
        prefs.edit().putString(MidiLearnStore.PREFS_KEY, saved).apply()
    }
}

/** [screen]'s [MidiLearnState], read from the app's preferences once. */
@Composable
internal fun rememberMidiLearn(screen: String, prefer: (String) -> MidiBinding.Mode?): MidiLearnState {
    val context = LocalContext.current
    return remember(screen) { MidiLearnState(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), screen, prefer) }
}

/**
 * MIDI learn's panel, the same on every screen that offers it: one chip
 * per target, then FORGET; a line saying what to do next; one line per
 * target naming the control it has; and the last message heard, live.
 * Tap a chip and it lights, waiting for a control; tap it again to stop
 * waiting. FORGET drops the lit target's control.
 *
 * @param targets each target's name and its chip, in the panel's order.
 * @param hint what the controls do here, under the status line.
 */
@Composable
internal fun MidiLearnPanel(
    state: MidiLearnState,
    targets: List<Pair<String, String>>,
    supported: Boolean,
    connected: Boolean,
    hint: String,
) {
    val scheme = LocalScheme.current
    val armed = state.armed
    val bindings = state.bindings
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((t, chip) in targets) {
                ActionButton(
                    chip,
                    scheme,
                    enabled = supported,
                    dimmed = armed != t,
                    modifier = Modifier.weight(1f).semantics { selected = armed == t },
                ) { state.arm(if (armed == t) null else t) }
            }
            ActionButton(
                "FORGET",
                scheme,
                enabled = armed != null && bindings.any { it.target == armed },
                modifier = Modifier.weight(1.4f),
            ) { armed?.let(state::forget) }
        }

        Spacer(Modifier.height(6.dp))

        val armedChip = targets.firstOrNull { it.first == armed }?.second
        TapeText(
            when {
                !supported -> "THIS PHONE HAS NO MIDI"
                !connected -> "PLUG IN A KEYBOARD TO LEARN"
                armedChip != null -> "MOVE A CONTROL FOR $armedChip"
                else -> "TAP A TARGET, THEN MOVE A CONTROL"
            },
            TapeType.pixel,
            scheme.ink.tape,
        )
        TapeText(hint, TapeType.pixel, scheme.ink.tape)
        for ((t, chip) in targets) {
            val bound = bindings.firstOrNull { it.target == t }
            TapeText("$chip  ${bound?.let { MidiLearn.describe(it.source) } ?: "-"}", TapeType.pixel, scheme.amber.tape)
        }
        TapeText("LAST  ${state.lastHeard ?: "-"}", TapeType.pixel, scheme.ink.tape)
    }
}
