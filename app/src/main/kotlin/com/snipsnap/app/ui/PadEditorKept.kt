package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.snipsnap.shell.PadEditorKeys
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumRenderRecipe

/*
 * The pad editor's state that is kept when GRAIN FIELD, SPLICE or STACK opens over it (spec §7.3.1).
 *
 * App draws the editor inside a `SaveableStateHolder`, so what is held with `rememberSaveable`
 * survives the editor leaving composition under a tool and comes back on Back. These helpers are the
 * only place the editor does that, for two reasons: the editor's composable is already past the point
 * where more registers are safe on device (a helper call is one call, a vararg `rememberSaveable` is an
 * array and a lambda), and each helper takes the same two keys the `remember` it replaced had, so a
 * pad that is stepped to inside the editor still starts from its own defaults.
 *
 * What is NOT kept, on purpose: anything running (a preview, a trip, a render, a job), the pressed
 * state of a control, and the values that are re-read from the pad itself after a refresh.
 */

/** An open/closed fold, closed until opened. [a] and [b] are the keys the `remember` it replaces used. */
@Composable
internal fun rememberKeptFlag(a: Any?, b: Any?): MutableState<Boolean> =
    rememberSaveable(a, b) { mutableStateOf(false) }

/** A staged knob position, 0..1. */
@Composable
internal fun rememberKeptFraction(a: Any?, b: Any?, initial: Float): MutableState<Float> =
    rememberSaveable(a, b) { mutableStateOf(initial) }

/** A chosen move or mode, by name. */
@Composable
internal fun rememberKeptName(a: Any?, b: Any?, initial: String): MutableState<String> =
    rememberSaveable(a, b) { mutableStateOf(initial) }

/** One staged knob value per move, by the move's name. */
@Composable
internal fun rememberKeptKnobs(a: Any?, b: Any?): SnapshotStateMap<String, Float> =
    rememberSaveable(a, b, saver = KnobsSaver) { mutableStateMapOf() }

/**
 * The Aquarium panel's unapplied draft. Saved as the recipe's own JSON beside [fingerprint], a hash of
 * what the draft was started from (the pad and its applied recipe): if a tool changed the pad meanwhile,
 * the draft is dropped and the panel starts again from what is applied, rather than showing a draft
 * made against a sound that is gone.
 */
@Composable
internal fun rememberKeptAquariumEditor(fingerprint: Int, init: () -> AquariumEditor): MutableState<AquariumEditor> =
    // The `saver =` overload, not `stateSaver =`: that one wraps this in a saver that never lets a
    // refused restore fall through to `init`, and a draft dropped for a changed pad must start again.
    rememberSaveable(fingerprint, saver = aquariumStateSaver(fingerprint), init = { mutableStateOf(init()) })

private fun aquariumStateSaver(fingerprint: Int): Saver<MutableState<AquariumEditor>, String> {
    val inner = aquariumEditorSaver(fingerprint)
    return Saver(
        save = { state -> with(inner) { save(state.value) } },
        restore = { saved -> inner.restore(saved)?.let { mutableStateOf(it) } },
    )
}

internal fun aquariumEditorSaver(fingerprint: Int): Saver<AquariumEditor, String> = Saver(
    save = { "$fingerprint\n${it.render.toJsonText()}" },
    restore = { saved ->
        val cut = saved.indexOf('\n')
        if (cut < 0 || saved.substring(0, cut) != fingerprint.toString()) {
            null
        } else {
            try {
                AquariumEditor(AquariumRenderRecipe.fromJsonText(saved.substring(cut + 1)))
            } catch (e: Exception) {
                null
            }
        }
    },
)

private val KnobsSaver: Saver<SnapshotStateMap<String, Float>, Any> = Saver(
    save = { map ->
        ArrayList<Any>(map.size * 2).also { out -> map.forEach { (k, v) -> out.add(k); out.add(v) } }
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val flat = saved as List<Any>
        mutableStateMapOf<String, Float>().also { map ->
            for (i in 0 until flat.size - 1 step 2) map[flat[i] as String] = flat[i + 1] as Float
        }
    },
)

/** Drops everything the pad editor parked once it has left the stack (Back out of it, or a place switch). */
@Composable
internal fun ReleasePadEditorWhenGone(inStack: Boolean, keys: PadEditorKeys) {
    LaunchedEffect(inStack) { if (!inStack) keys.release() }
}

/**
 * The [PadEditorKeys] for [holder], saved with the holder. The holder's parked state survives process
 * death, so the key list must too: otherwise the restored process cannot release what it holds and
 * the next pad opened on the same kit inherits the old pad's folds and knobs.
 */
@Composable
internal fun rememberPadEditorKeys(holder: SaveableStateHolder): PadEditorKeys {
    val saver = remember(holder) {
        Saver<PadEditorKeys, Any>(
            save = { ArrayList(it.heldKeys()) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                PadEditorKeys(saved as List<String>, holder::removeState)
            },
        )
    }
    return rememberSaveable(holder, saver = saver) { PadEditorKeys(removeState = holder::removeState) }
}
