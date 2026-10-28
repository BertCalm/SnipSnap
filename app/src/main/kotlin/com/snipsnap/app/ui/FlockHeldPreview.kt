package com.snipsnap.app.ui

import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.InstrumentPlayer
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Scales
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.FlockPadMaker
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** The held choice is separate from HOLD so every saved finite duration retains its meaning. */
@Composable
internal fun FlockPerformanceControls(
    patch: FlockPatch,
    fill: Color,
    scheme: Scheme,
    onEdit: (FlockPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Flock.MIN_MIDI).toFloat() / (Flock.MAX_MIDI - Flock.MIN_MIDI),
        fillColor = fill,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onEdit(patch.copy(midi = (Flock.MIN_MIDI + value * (Flock.MAX_MIDI - Flock.MIN_MIDI))
                .roundToInt().coerceIn(Flock.MIN_MIDI, Flock.MAX_MIDI)))
        },
    )
    MacroSlider("VELOCITY", patch.velocity, fill, scheme,
        onValueChange = { onEdit(patch.copy(velocity = it.coerceIn(0f, 1f))) })
    if (patch.voice == FlockVoice.RIBBON) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in FlockPlayback.entries) {
                ActionButton(
                    mode.name,
                    scheme,
                    enabled = true,
                    lit = patch.playback == mode,
                    modifier = Modifier.weight(1f).semantics { selected = patch.playback == mode },
                    accessibilityLabel = "RIBBON ${mode.name}",
                ) { onEdit(patch.copy(playback = mode)) }
            }
        }
    }
    if (patch.playback == FlockPlayback.HELD) {
        MacroSlider(
            "RELEASE", FlockPadMaker.RELEASE.fraction(patch.releaseSeconds), fill, scheme,
            onValueChange = { onEdit(patch.copy(releaseSeconds = FlockPadMaker.RELEASE.value(it)
                .coerceIn(FlockPadMaker.RELEASE.lo, FlockPadMaker.RELEASE.hi))) },
            readout = FlockPadMaker.secondsLabel(patch.releaseSeconds),
        )
        TapeText("DRY HELD PREVIEW · LIFT TO RELEASE", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText("SEND MAKES A ONE-SHOT. MAKE INSTRUMENT SUSTAINS.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    } else {
        TapeText("FINITE GESTURE · HOLD SETS LENGTH", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    }
}

/** One cached root capture. A press only starts the sample player; it never runs FLOCK. */
internal class FlockHeldPreviewState(
    private val player: InstrumentPlayer,
    private val midi: Int,
    private val scope: CoroutineScope,
) : AudioVoice {
    var snip by mutableStateOf<Snip?>(null)
    var rendering by mutableStateOf(true)
    var ready by mutableStateOf(false)
    var sounding by mutableStateOf(false)
        private set
    var releaseSeconds = .6f
    private var focusTail: Job? = null

    fun noteOn() {
        if (!ready) return
        focusTail?.cancel()
        sounding = true
        AudioFocus.acquire(this)
        // A denied focus request calls silence synchronously.
        if (!sounding) return
        if (!player.noteOn(midi)) {
            sounding = false
            AudioFocus.release(this)
        }
    }

    fun noteOff() {
        if (!sounding) return
        player.noteOff(midi)
        sounding = false
        focusTail?.cancel()
        focusTail = scope.launch {
            delay((releaseSeconds * 1000).toLong() + 50)
            AudioFocus.release(this@FlockHeldPreviewState)
        }
    }

    override fun silence() {
        focusTail?.cancel()
        player.allOff()
        sounding = false
        AudioFocus.release(this)
    }
}

/**
 * Rendering and the native sample-bank copy finish before AUDITION becomes available.
 * Release is deliberately outside the render key: it changes note-off, not captured motion.
 */
@Composable
internal fun rememberFlockHeldPreview(
    patch: FlockPatch?,
    onFailure: () -> Unit,
): FlockHeldPreviewState? {
    if (patch?.playback != FlockPlayback.HELD) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderPatch = patch.copy(name = "HELD PREVIEW", releaseSeconds = .6f,
        macros = patch.macros + ("HOLD" to 0f))
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
        // Match SYNTH's trailing-edge debounce while invalidating the old sound immediately.
        delay(100)
        try {
            val job = coroutineContext[Job]
            val held = offMainInterruptible(Dispatchers.Default) {
                FlockHeld.render(renderPatch) {
                    job?.isActive == false || Thread.currentThread().isInterrupted
                }
            }
            val sample = "ribbon-preview.wav"
            val instrument = InstrumentStore.Instrument(
                name = renderPatch.name,
                release = release.value,
                zones = listOf(InstrumentStore.Zone(
                    lowNote = patch.midi,
                    highNote = patch.midi,
                    rootNote = patch.midi,
                    sample = sample,
                    frameCount = held.snip.frameCount.toLong(),
                    loopStartFrame = held.loopStartFrame.toLong(),
                )),
            )
            offMain { player.open(instrument, mapOf(sample to held.snip)) }
            player.setReleaseSeconds(release.value)
            state.snip = held.snip
            state.ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "held RIBBON preview failed", e)
            failure.value()
        } finally {
            state.rendering = false
        }
    }
    return state
}

/** Finger-up and gesture cancellation both send a real note-off to the existing sample player. */
@Composable
internal fun FlockHeldAudition(
    state: FlockHeldPreviewState,
    fill: Color,
    scheme: Scheme,
    accessibilityLabel: String = "AUDITION",
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.PRIMARY_ACTION_H.dp)
            .raisedBevel(scheme, fill = fill.copy(alpha = if (state.ready) .85f else .35f))
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = when {
                    state.sounding -> "HELD"
                    state.ready -> "READY"
                    state.rendering -> "RENDERING"
                    else -> "UNAVAILABLE"
                }
                if (!state.ready) disabled()
                else onClick(label = if (state.sounding) "RELEASE NOTE" else "HOLD NOTE") {
                    // TalkBack supplies clicks rather than a duration: a second click releases.
                    if (state.sounding) state.noteOff() else state.noteOn()
                    true
                }
            }
            .then(if (!state.ready) Modifier else Modifier.pointerInput(state) {
                detectTapGestures(onPress = {
                    state.noteOn()
                    try {
                        tryAwaitRelease()
                    } finally {
                        state.noteOff()
                    }
                })
            })
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            when {
                state.rendering -> "RENDERING…"
                !state.ready -> "PREVIEW UNAVAILABLE"
                state.sounding -> "HELD · LIFT TO RELEASE"
                else -> "HOLD TO AUDITION"
            },
            TapeType.pixel,
            scheme.titleInk.tape,
        )
    }
}
