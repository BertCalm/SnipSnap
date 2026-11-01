package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.shell.PrismPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import java.io.File

/** A preview can retain an authored opening before entering a measured sustain region. */
internal data class HeldPreviewPlayback(val snip: Snip, val loop: IntRange? = null)

internal fun prismOpeningInstrumentSupported(patch: Patch): Boolean =
    patch is PrismPatch && PrismPadMaker.supports(patch)

internal fun prismHeldSpec(patch: Patch, releaseFraction: Float): HeldSpec? =
    (patch as? PrismPatch)?.takeIf(PrismPadMaker::supports)?.let {
        PrismHeldSpec(PrismPadMaker.spec(it, releaseFraction))
    }

/** Keep PRISM's complete recipe outside SynthScreen's verifier-limited composable. */
internal class PrismHeldSpec(private val spec: PrismPadMaker.Spec) : HeldSpec {
    override val zoneMidis get() = PrismPadMaker.zoneMidis(spec)
    override val hasAttack get() = false
    override val knobLabel get() = "RELEASE ${PrismPadMaker.secondsLabel(spec.releaseSeconds)}"
    override val instrumentNote get() =
        "OPENING ONCE, THEN REPEATING SUSTAIN. HOLD A KEY TO CONTINUE; LET GO TO FADE."

    override fun renderZone(midi: Int, cancelled: () -> Boolean): List<KeyNote> =
        listOf(PrismPadMaker.renderZone(spec, midi, cancelled))

    override fun preview(cancelled: () -> Boolean): Snip = previewPlayback(cancelled).snip

    override fun previewPlayback(cancelled: () -> Boolean): HeldPreviewPlayback {
        val midi = Prism.midiFor(spec.patch.voice,
            (Prism.defaults(spec.patch.voice) + spec.patch.macros).getValue("TUNE"))
        val note = PrismPadMaker.renderZone(spec, midi, cancelled)
        return HeldPreviewPlayback(note.snip, note.loopStartFrame.toInt() until note.snip.frameCount)
    }

    override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
        PrismPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
}

@Composable
internal fun PrismInstrumentHint(scheme: Scheme) {
    TapeText("INSTRUMENT: OPENING ONCE, THEN REPEATING SUSTAIN.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
    TapeText("PAD AND MAIN AUDITION KEEP THE SUSTAIN ONLY.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
}

/** A cancelled or superseded sheet cannot publish a stale opening or continue its preview. */
@Composable
internal fun GuardPrismHeldInstrument(
    capturedPatch: PrismPatch?,
    currentPatch: PrismPatch?,
    sourceBusy: Boolean,
    onCancel: () -> Unit,
) {
    val cancel by rememberUpdatedState(onCancel)
    val captured by rememberUpdatedState(capturedPatch)
    LaunchedEffect(capturedPatch, currentPatch, sourceBusy) {
        if (capturedPatch != null && (currentPatch != capturedPatch || sourceBusy)) cancel()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && captured != null) cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (captured != null) cancel()
        }
    }
}
