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
import com.snipsnap.audio.Scales
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.OverlapPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapHeld
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private fun finiteReturnKey(patch: OverlapPatch) = "overlap.finite-return.${patch.topology.name}"

/** A deliberate mode change never reinterprets the finite HOLD slider or its saved model. */
@Composable
internal fun OverlapPerformanceControls(
    patch: OverlapPatch,
    settings: MutableMap<String, String>,
    fill: Color,
    scheme: Scheme,
    onEdit: ((OverlapPatch) -> OverlapPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Overlap.MIN_MIDI).toFloat() / (Overlap.MAX_MIDI - Overlap.MIN_MIDI),
        fillColor = fill, scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onEdit { current -> current.copy(midi = (Overlap.MIN_MIDI + value * (Overlap.MAX_MIDI - Overlap.MIN_MIDI))
                .roundToInt().coerceIn(Overlap.MIN_MIDI, Overlap.MAX_MIDI)) }
        },
    )
    MacroSlider("VELOCITY", patch.velocity, fill, scheme,
        onValueChange = { value -> onEdit { it.copy(velocity = value.coerceIn(0f, 1f)) } })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in OverlapPlayback.entries) {
            ActionButton(
                mode.name, scheme, enabled = true, lit = patch.playback == mode,
                modifier = Modifier.weight(1f).semantics { selected = patch.playback == mode },
                accessibilityLabel = "OVERLAP ${mode.name}",
            ) {
                onEdit { current ->
                    if (current.playback == mode) current
                    else if (mode == OverlapPlayback.HELD) {
                        // Keep the finite timbre/model even across navigation. The held
                        // proof supports a different contract, so entry is explicit.
                        settings[finiteReturnKey(current)] = current.toJsonText()
                        current.copy(model = 2, playback = mode,
                            macros = OverlapHeld.defaults(current.topology), heldVersion = OverlapHeld.VERSION)
                    } else {
                        val finite = settings[finiteReturnKey(current)]?.let { text ->
                            runCatching { OverlapPatch.fromJsonText(text) }.getOrNull()
                        }?.takeIf { it.playback == OverlapPlayback.FINITE && it.topology == current.topology }
                            ?: current.copy(playback = mode, model = 2,
                                macros = Overlap.defaults(current.topology, model = 2))
                        // Performance edits stay current; finite timbre/model return.
                        finite.copy(midi = current.midi, velocity = current.velocity,
                            releaseSeconds = current.releaseSeconds, heldVersion = current.heldVersion)
                    }
                }
            }
        }
    }
    if (patch.playback == OverlapPlayback.HELD) {
        MacroSlider("RELEASE", OverlapPadMaker.RELEASE.fraction(patch.releaseSeconds), fill, scheme,
            onValueChange = { value -> onEdit { current -> current.copy(releaseSeconds = OverlapPadMaker.RELEASE.value(value)
                .coerceIn(OverlapPadMaker.RELEASE.lo, OverlapPadMaker.RELEASE.hi)) } },
            readout = OverlapPadMaker.secondsLabel(patch.releaseSeconds))
        TapeText("STEADY HELD PREVIEW · LIFT TO RELEASE", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText("SEND MAKES A ONE-SHOT. MAKE INSTRUMENT SUSTAINS.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    } else {
        TapeText("ROOT IS THE REFERENCE NOTE. HOLD EXTENDS A FINITE SOUND.",
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
internal fun rememberOverlapHeldPreview(
    patch: OverlapPatch?,
    onFailure: () -> Unit,
): FlockHeldPreviewState? {
    if (patch?.playback != OverlapPlayback.HELD) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderPatch = patch.copy(name = "HELD PREVIEW", releaseSeconds = OverlapHeld.DEFAULT_RELEASE_SECONDS)
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
                OverlapHeld.render(renderPatch, cancelled = {
                    job?.isActive == false || Thread.currentThread().isInterrupted
                })
            }
            coroutineContext.ensureActive()
            require(held.loopStartFrame > 0 && held.loopStartFrame < held.loopEndExclusive &&
                held.loopEndExclusive == held.snip.frameCount) { "OVERLAP preview has no complete sustain loop" }
            val sample = "overlap-preview.wav"
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
            Log.e("SynthScreen", "held OVERLAP preview failed", e)
            failure.value()
        } finally {
            state.rendering = false
        }
    }
    return state
}
