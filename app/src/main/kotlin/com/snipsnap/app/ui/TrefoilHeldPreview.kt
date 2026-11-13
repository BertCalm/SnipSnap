package com.snipsnap.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.InstrumentPlayer
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.TrefoilPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Trefoil
import com.snipsnap.synth.TrefoilHeld
import com.snipsnap.synth.TrefoilPatch
import com.snipsnap.synth.TrefoilPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private fun finiteReturnKey(patch: TrefoilPatch) = "trefoil.finite-return.${patch.voice.name}"

/** A deliberate mode change never reinterprets the finite HOLD slider or its saved model. */
@Composable
internal fun TrefoilPerformanceControls(
    patch: TrefoilPatch,
    settings: MutableMap<String, String>,
    fill: Color,
    scheme: Scheme,
    onEdit: ((TrefoilPatch) -> TrefoilPatch) -> Unit,
) {
    MacroSlider("VELOCITY", patch.velocity, fill, scheme,
        onValueChange = { value -> onEdit { it.copy(velocity = value.coerceIn(0f, 1f)) } })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in TrefoilPlayback.entries) {
            ActionButton(
                mode.name, scheme, enabled = true, lit = patch.playback == mode,
                modifier = Modifier.weight(1f).semantics { selected = patch.playback == mode },
                accessibilityLabel = "TREFOIL ${mode.name}",
            ) {
                onEdit { current ->
                    if (current.playback == mode) current
                    else if (mode == TrefoilPlayback.HELD) {
                        // Keep the finite timbre/model even across navigation. The held
                        // proof supports a different contract, so entry is explicit.
                        settings[finiteReturnKey(current)] = current.toJsonText()
                        current.copy(model = 2, playback = mode,
                            macros = TrefoilHeld.defaults(current.voice), heldVersion = TrefoilHeld.VERSION)
                    } else {
                        val finite = settings[finiteReturnKey(current)]?.let { text ->
                            runCatching { TrefoilPatch.fromJsonText(text) }.getOrNull()
                        }?.takeIf { it.playback == TrefoilPlayback.FINITE && it.voice == current.voice }
                            ?: current.copy(playback = mode, model = 2,
                                macros = Trefoil.defaults(current.voice))
                        // Performance edits stay current; finite timbre/model return.
                        finite.copy(midi = current.midi, velocity = current.velocity,
                            releaseSeconds = current.releaseSeconds, heldVersion = current.heldVersion)
                    }
                }
            }
        }
    }
    if (patch.playback == TrefoilPlayback.HELD) {
        MacroSlider("RELEASE", TrefoilPadMaker.RELEASE.fraction(patch.releaseSeconds), fill, scheme,
            onValueChange = { value -> onEdit { current -> current.copy(releaseSeconds = TrefoilPadMaker.RELEASE.value(value)
                .coerceIn(TrefoilPadMaker.RELEASE.lo, TrefoilPadMaker.RELEASE.hi)) } },
            readout = TrefoilPadMaker.secondsLabel(patch.releaseSeconds))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (moving in listOf(true, false)) ActionButton(
                if (moving) "MOVING" else "STILL", scheme, enabled = true,
                lit = (patch.macros["WEAVE"] != 0f) == moving,
                modifier = Modifier.weight(1f), accessibilityLabel = if (moving) "TREFOIL moving geometry" else "TREFOIL stationary geometry",
            ) { onEdit { it.copy(macros = it.macros + ("WEAVE" to if (moving) .42f else 0f)) } }
        }
        if (patch.macros["WEAVE"] != 0f) MacroSlider(
            "WEAVE", patch.macros.getValue("WEAVE"), fill, scheme,
            valueRange = TrefoilHeld.MIN_MOVING_WEAVE..1f,
            onValueChange = { value -> onEdit { it.copy(macros = it.macros + ("WEAVE" to value.coerceIn(TrefoilHeld.MIN_MOVING_WEAVE, 1f))) } },
        )
        TapeText("ORDERED ORBIT · NUDGE IS FIXED AT ZERO", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText("STEADY HELD PREVIEW · LIFT TO RELEASE", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText("Send and spread make finite pads. MAKE INSTRUMENT sustains.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    } else {
        MacroSlider("CONTACT", (patch.activeSeconds - .05f) / (patch.seconds - .15f), fill, scheme,
            onValueChange = { value -> onEdit { it.copy(activeSeconds = (.05f + value * (it.seconds - .15f)).coerceIn(.05f, it.seconds - .1f)) } },
            readout = "%.2fs".format(java.util.Locale.ROOT, patch.activeSeconds))
        TapeText("Root is the reference note. Contact controls the finite gesture.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    }
}

/**
 * Reuse the existing sample-only held controller and audition gesture from FLOCK.
 * They contain no engine DSP: a press starts the preloaded InstrumentPlayer, and
 * release updates that player's envelope. Capture and native-bank copying happen
 * off the UI thread before ready becomes true. Name/release edits do not rerender.
 */
@Composable
internal fun rememberTrefoilHeldPreview(
    patch: TrefoilPatch?,
    onFailure: () -> Unit,
): FlockHeldPreviewState? {
    if (patch?.playback != TrefoilPlayback.HELD) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderPatch = patch.copy(name = "HELD PREVIEW", releaseSeconds = TrefoilHeld.DEFAULT_RELEASE_SECONDS)
    val player = remember(renderPatch) { InstrumentPlayer(context) }
    val state = remember(player) { FlockHeldPreviewState(player, patch.midi, scope) }
    val release = rememberUpdatedState(patch.releaseSeconds)
    val failure = rememberUpdatedState(onFailure)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.silence()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.ready = false
            state.silence()
            player.close()
        }
    }
    LaunchedEffect(player, patch.releaseSeconds) {
        state.releaseSeconds = patch.releaseSeconds
        player.setReleaseSeconds(patch.releaseSeconds)
    }
    LaunchedEffect(player) {
        delay(100)
        try {
            val job = coroutineContext[Job]
            val held = offMainInterruptible(Dispatchers.Default) {
                TrefoilHeld.render(renderPatch, cancelled = {
                    job?.isActive == false || Thread.currentThread().isInterrupted
                })
            }
            coroutineContext.ensureActive()
            require(held.loopStartFrame > 0 && held.loopStartFrame < held.loopEndExclusive &&
                held.loopEndExclusive == held.snip.frameCount) { "TREFOIL preview has no complete sustain loop" }
            val sample = "trefoil-preview.wav"
            val instrument = InstrumentStore.Instrument(
                name = renderPatch.name, release = release.value,
                zones = listOf(InstrumentStore.Zone(patch.midi, patch.midi, patch.midi,
                    sample, held.snip.frameCount.toLong(), held.loopStartFrame.toLong())),
            )
            offMain { player.open(instrument, mapOf(sample to held.snip)) }
            coroutineContext.ensureActive()
            player.setReleaseSeconds(release.value)
            state.snip = held.snip
            state.ready = true
        } catch (e: CancellationException) {
            state.ready = false
            state.silence()
            player.close()
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "held TREFOIL preview failed", e)
            failure.value()
        } finally {
            state.rendering = false
        }
    }
    return state
}
