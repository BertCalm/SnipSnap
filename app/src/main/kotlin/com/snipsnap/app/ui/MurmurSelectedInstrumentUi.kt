package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.snipsnap.audio.Snip
import com.snipsnap.shell.MurmurSelectedInstrumentMaker
import com.snipsnap.shell.MurmurSelectedInstrumentPreview
import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** The real streaming/source-playback boundary; tests may observe it without rendering a full grid. */
internal interface MurmurSelectedInstrumentActions {
    fun preview(spec: MurmurSelectedInstrumentMaker.Spec, cancelled: () -> Boolean): Snip
    fun previewChord(spec: MurmurSelectedInstrumentMaker.Spec, notes: List<Int>, cancelled: () -> Boolean): Snip
    fun export(name: String, spec: MurmurSelectedInstrumentMaker.Spec, destination: File,
               cancelled: () -> Boolean, progress: (Int, Int) -> Unit)
}

internal val LocalMurmurSelectedInstrumentActions = staticCompositionLocalOf<MurmurSelectedInstrumentActions> {
    object : MurmurSelectedInstrumentActions {
        override fun preview(spec: MurmurSelectedInstrumentMaker.Spec, cancelled: () -> Boolean) =
            MurmurSelectedInstrumentPreview.render(spec, cancelled)
        override fun previewChord(spec: MurmurSelectedInstrumentMaker.Spec, notes: List<Int>, cancelled: () -> Boolean) =
            MurmurSelectedInstrumentPreview.renderChord(spec, notes, cancelled)
        override fun export(name: String, spec: MurmurSelectedInstrumentMaker.Spec, destination: File,
                            cancelled: () -> Boolean, progress: (Int, Int) -> Unit) {
            MurmurSelectedInstrumentMaker.exportFresh(name, spec, destination, cancelled, progress)
        }
    }
}

@Composable
internal fun rememberMurmurSelectedDelivery(): MurmurSelectedDeliveryState {
    val actions = LocalMurmurSelectedInstrumentActions.current
    return remember(actions) { MurmurSelectedDeliveryState(actions) }
}

/** A source edit invalidates a worker synchronously, before the next composition/effect. */
internal class MurmurSelectedDeliveryState(val actions: MurmurSelectedInstrumentActions) {
    private val generation = AtomicLong(0)
    private val worker = AtomicLong(-1)
    var releaseSeconds by mutableStateOf(MurmurSelectedInstrumentSource.DEFAULT_RELEASE_SECONDS)
        private set

    val releaseFraction: Float get() = (releaseSeconds - MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS) /
        (MurmurSelectedInstrumentSource.MAX_RELEASE_SECONDS - MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS)
    val inFlight: Boolean get() = worker.get() >= 0

    fun setRelease(fraction: Float) {
        if (!fraction.isFinite()) return
        invalidate()
        releaseSeconds = MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS + fraction.coerceIn(0f, 1f) *
            (MurmurSelectedInstrumentSource.MAX_RELEASE_SECONDS - MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS)
    }

    fun begin(): Long = generation.incrementAndGet().also(worker::set)
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(token: Long): Boolean = generation.get() == token && worker.get() == token
    fun finish(token: Long) { worker.compareAndSet(token, -1) }
    fun finishInvalidated() {
        val token = worker.get()
        if (token >= 0 && generation.get() != token) worker.compareAndSet(token, -1)
    }
}

internal fun revisedMurmur(patch: MurmurPatch): Boolean = patch.config.body != MurmurBody.LEGACY

internal fun murmurInstrumentUnavailable(patch: MurmurPatch): String =
    if (patch.velocity <= 0f) "RAISE VELOCITY ABOVE ZERO TO MAKE AN INSTRUMENT."
    else "RESTORE A KEPT PLUCK PRESET TO MAKE THIS INSTRUMENT. ROOT AND VELOCITY MAY CHANGE; ITS SOUND SHAPE MUST MATCH."

internal const val MURMUR_CHORD_OUT_OF_RANGE =
    "THE WHOLE MINOR CHORD MUST FIT THIS SOURCE'S RANGE. LOWER ITS ROOT OR CHOOSE A WIDER-RANGE SOURCE."
