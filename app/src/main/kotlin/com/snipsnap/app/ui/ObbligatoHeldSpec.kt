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
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Scales
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.ObbligatoPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoHeld
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

private const val FINITE_RETURN_KEY = "obbligato.finite-return"

/** An explicit performance choice keeps older finite recipes available for a return. */
internal fun obbligatoPlaybackChange(
    current: ObbligatoPatch,
    playback: ObbligatoPlayback,
    settings: MutableMap<String, String>,
): ObbligatoPatch {
    if (current.playback == playback) return current
    if (playback == ObbligatoPlayback.HELD) {
        settings[FINITE_RETURN_KEY] = current.toJsonText()
        return current.copy(model = Obbligato.PERFORMANCE_MODEL, playback = playback,
            macros = current.macros + ("HOLD" to 1f), heldVersion = ObbligatoHeld.VERSION)
    }
    val previous = settings[FINITE_RETURN_KEY]?.let { text ->
        runCatching { ObbligatoPatch.fromJsonText(text) }.getOrNull()
    }?.takeIf { it.playback == ObbligatoPlayback.FINITE }
    // A newly selected wide note stays at that pitch. Older finite models retain
    // their own register, so a return outside it deliberately uses the wide renderer.
    val model = previous?.model?.takeIf { current.midi in Obbligato.midiRange(it) }
        ?: Obbligato.PERFORMANCE_MODEL
    return current.copy(model = model, playback = playback,
        macros = current.macros + ("HOLD" to (previous?.macros?.get("HOLD")
            ?: Obbligato.defaults().getValue("HOLD"))))
}

@Composable
internal fun ObbligatoPerformanceControls(
    patch: ObbligatoPatch,
    settings: MutableMap<String, String>,
    fill: Color,
    scheme: Scheme,
    onEdit: ((ObbligatoPatch) -> ObbligatoPatch) -> Unit,
) {
    MacroSlider("VELOCITY", patch.velocity, fill, scheme,
        onValueChange = { value -> onEdit { it.copy(velocity = value.coerceIn(0f, 1f)) } })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in ObbligatoPlayback.entries) {
            val label = if (mode == ObbligatoPlayback.FINITE) "PHRASE" else "HELD"
            ActionButton(label, scheme, enabled = true, lit = patch.playback == mode,
                modifier = Modifier.weight(1f).semantics { selected = patch.playback == mode },
                accessibilityLabel = "OBBLIGATO $label",
            ) { onEdit { current -> obbligatoPlaybackChange(current, mode, settings) } }
        }
    }
    if (patch.playback == ObbligatoPlayback.HELD) {
        MacroSlider("RELEASE", ObbligatoPadMaker.RELEASE.fraction(patch.releaseSeconds), fill, scheme,
            onValueChange = { fraction -> onEdit { current ->
                current.copy(releaseSeconds = ObbligatoPadMaker.RELEASE.value(fraction)
                    .coerceIn(ObbligatoPadMaker.RELEASE.lo, ObbligatoPadMaker.RELEASE.hi))
            } }, readout = ObbligatoPadMaker.secondsLabel(patch.releaseSeconds))
        TapeText("PHRASES CONTINUE WHILE HELD. LIFT TO RELEASE.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText("SEND AND SPREAD MAKE FINITE PADS. MAKE INSTRUMENT SUSTAINS.",
            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    } else {
        TapeText("HOLD EXTENDS A FINITE PHRASE", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/** The shared instrument sheet retains the complete duet recipe while rendering one root at a time. */
internal class ObbligatoHeldSpec(private val spec: ObbligatoPadMaker.Spec) : HeldSpec {
    override val zoneMidis get() = ObbligatoPadMaker.zoneMidis(spec)
    override val hasAttack get() = false
    override val releaseFraction get() = ObbligatoPadMaker.RELEASE.fraction(spec.releaseSeconds)
    override val knobLabel get() = "RELEASE ${ObbligatoPadMaker.secondsLabel(spec.releaseSeconds)}"
    override val keyRangeLabel get() = "${Scales.nameOf(zoneMidis.first())}–${Scales.nameOf(zoneMidis.last())}"
    override val instrumentNote get() = "THE OPENING PLAYS ONCE, THEN PHRASES CONTINUE. LIFT A KEY TO RELEASE."
    override fun renderZone(midi: Int, cancelled: () -> Boolean): List<KeyNote> =
        listOf(ObbligatoPadMaker.renderZone(spec, midi, cancelled))
    override fun preview(cancelled: () -> Boolean): Snip = ObbligatoPadMaker.preview(spec, cancelled)
    override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
        ObbligatoPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
}

/** Prepare the root capture off-thread; a held press only starts the sample player. */
@Composable
internal fun rememberObbligatoHeldPreview(
    patch: ObbligatoPatch?,
    onFailure: () -> Unit,
): FlockHeldPreviewState? {
    if (patch?.playback != ObbligatoPlayback.HELD) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderPatch = patch.copy(name = "HELD PREVIEW", releaseSeconds = ObbligatoHeld.DEFAULT_RELEASE_SECONDS)
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
                ObbligatoHeld.render(renderPatch, cancelled = {
                    job?.isActive == false || Thread.currentThread().isInterrupted
                })
            }
            coroutineContext.ensureActive()
            require(held.loopStartFrame > 0 && held.loopStartFrame < held.loopEndExclusive &&
                held.loopEndExclusive == held.snip.frameCount) { "OBBLIGATO preview has no complete sustain loop" }
            val sample = "obbligato-preview.wav"
            val instrument = InstrumentStore.Instrument(renderPatch.name, release.value,
                listOf(InstrumentStore.Zone(patch.midi, patch.midi, patch.midi, sample,
                    held.snip.frameCount.toLong(), held.loopStartFrame.toLong())))
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
            Log.e("SynthScreen", "held OBBLIGATO preview failed", e)
            failure.value()
        } finally {
            state.rendering = false
        }
    }
    return state
}
