package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismMotionEditor
import com.snipsnap.synth.PrismMotionSources
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One small holder keeps job bookkeeping out of SynthScreen's ART verifier-limited method. */
internal class PrismMotionStudioState {
    var ownsPreview by mutableStateOf(false)
        private set
    var previewing by mutableStateOf(false)
        private set
    var sourceBusy by mutableStateOf(false)
        private set
    var selectionVersion by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
    private val baselines = mutableMapOf<PrismVoice, PrismPatch>()
    private var sourceGeneration = 0L
    private var previewGeneration = 0L
    internal var sourceJob: Job? = null
    internal var previewJob: Job? = null

    fun selected(patch: PrismPatch) {
        cancel()
        baselines[patch.voice] = patch
        selectionVersion++
    }

    fun baseline(patch: PrismPatch): PrismPatch = baselines.getOrPut(patch.voice) { patch }

    fun cancelPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        ownsPreview = false
        previewing = false
    }

    fun cancelSource() {
        sourceGeneration++
        sourceJob?.cancel()
        sourceJob = null
        sourceBusy = false
    }

    fun cancel() { cancelSource(); cancelPreview(); error = null }
    fun startSource(): Long {
        cancelSource()
        cancelPreview()
        sourceBusy = true
        ownsPreview = true
        error = null
        return sourceGeneration
    }
    fun acceptsSource(generation: Long): Boolean = generation == sourceGeneration
    fun finishSource(generation: Long) {
        if (acceptsSource(generation)) { sourceBusy = false; ownsPreview = false; sourceJob = null }
    }
    fun startPreview(): Long {
        cancelPreview()
        ownsPreview = true
        previewing = true
        error = null
        return previewGeneration
    }
    fun acceptsPreview(generation: Long): Boolean = generation == previewGeneration
    fun finishPreview(generation: Long) {
        if (acceptsPreview(generation)) { previewing = false; previewJob = null }
    }
}

@Composable
internal fun rememberPrismMotionStudio(): PrismMotionStudioState = remember { PrismMotionStudioState() }

/** OPEN recipes stay finite; repeating motion may enter PRISM's verified loop region. */
internal fun prismFiniteMotion(patch: PrismPatch?): Boolean = patch?.motion?.let { !it.repeat } == true

/** Reset the motion section only. Timbre, note, touch and unrelated recipe state remain edited. */
private fun resetPrismMotion(current: PrismPatch, baseline: PrismPatch): PrismPatch {
    val controls = if (prismFiniteMotion(baseline) && Prism.isLoop(current.macros["HOLD"] ?: 0f))
        current.macros + ("HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD)) else current.macros
    return current.copy(macros = controls, motion = baseline.motion)
}

/**
 * The detail panel never owns an audio player. All previews return to SynthScreen's one audition
 * voice; source analysis runs interruptibly off main, and only the latest selection can publish.
 * Edits are transforms of the host's current recipe, so a pending source cannot undo a slider.
 */
@Composable
internal fun PrismMotionPanel(
    patch: PrismPatch,
    studio: PrismMotionStudioState,
    scheme: Scheme,
    color: Color,
    currentPatch: () -> PrismPatch?,
    onEdit: (PrismVoice, (PrismPatch) -> PrismPatch) -> Unit,
    onPreview: (Snip) -> Unit,
    onStopPreview: (Boolean) -> Unit,
    onBeforeEdit: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val readPatch by rememberUpdatedState(currentPatch)
    val editPatch by rememberUpdatedState(onEdit)
    val hear by rememberUpdatedState(onPreview)
    val stop by rememberUpdatedState(onStopPreview)
    val beforeEdit by rememberUpdatedState(onBeforeEdit)
    var expanded by remember(patch.voice) { mutableStateOf(false) }
    var pickingSource by remember(patch.voice) { mutableStateOf(false) }
    val baseline = studio.baseline(patch)
    val motion = patch.motion
    val source = PrismMotionSources.byId(motion?.sourceId)

    LaunchedEffect(patch.voice, studio.selectionVersion) {
        studio.cancelSource()
        studio.cancelPreview()
    }
    LaunchedEffect(patch) {
        if (studio.ownsPreview && !studio.sourceBusy) { studio.cancelPreview(); stop(false) }
    }
    DisposableEffect(Unit) { onDispose { studio.cancel(); stop(false) } }

    fun edit(transform: (PrismPatch) -> PrismPatch) {
        beforeEdit()
        studio.cancelPreview()
        stop(false)
        editPatch(patch.voice, transform)
    }

    fun selectSource(choice: PrismMotionSources.Source) {
        beforeEdit()
        val starting = readPatch()?.takeIf { it.voice == patch.voice } ?: return
        val generation = studio.startSource()
        stop(false)
        pickingSource = false
        studio.sourceJob = scope.launch {
            try {
                val job = coroutineContext[Job]
                val prepared = offMainInterruptible(Dispatchers.Default) {
                    PrismMotionSources.applySource(starting, choice) { job?.isActive == false }
                }
                coroutineContext.ensureActive()
                if (!studio.acceptsSource(generation) || readPatch()?.voice != starting.voice) return@launch
                val selected = requireNotNull(prepared.motion)
                // Publish just the prepared spectra. Pitch, touch and motion controls may have
                // moved while analysis was running, and those latest edits take precedence.
                editPatch(starting.voice) { latest ->
                    val live = PrismMotionEditor.ensureMotion(latest)
                    live.copy(motion = requireNotNull(live.motion).copy(
                        amplitudesA = selected.amplitudesA, amplitudesB = selected.amplitudesB, sourceId = selected.sourceId,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (studio.acceptsSource(generation)) studio.error = "This tone could not be loaded. Try another source."
            } finally {
                studio.finishSource(generation)
            }
        }
    }

    fun preview(kind: String) {
        val captured = readPatch()?.takeIf { it.voice == patch.voice } ?: return
        val original = if (kind == "SOURCE") PrismMotionSources.originalSource(captured) else null
        if (kind == "SOURCE" && original == null) return
        val generation = studio.startPreview()
        stop(false)
        studio.previewJob = scope.launch {
            try {
                val rendered = offMainInterruptible(Dispatchers.Default) {
                    when (kind) {
                        "SOURCE" -> requireNotNull(original).render()
                        "STATIC" -> PrismMotionEditor.staticDestination(captured).render()
                        else -> captured.render()
                    }
                }
                coroutineContext.ensureActive()
                if (studio.acceptsPreview(generation) && readPatch() == captured) hear(rendered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (studio.acceptsPreview(generation)) studio.error = "This preview could not be played. Try again."
            } finally {
                studio.finishPreview(generation)
            }
        }
    }

    GroupBox(
        legend = "MOTION",
        summary = if (motion == null) "OFF · ADD MOVEMENT" else if (motion.repeat) "REPEAT · ${prismSeconds(motion.seconds)} S" else "OPEN · ${prismSeconds(motion.seconds)} S",
        open = expanded, onToggle = { expanded = !expanded }, scheme = scheme,
    ) {
        TapeText("SOURCE · ${source?.title ?: "SAVED TONE"}", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        LabButton("SOURCE ▸", scheme, enabled = true, accessibilityLabel = "CHOOSE MOTION SOURCE",
            modifier = Modifier.fillMaxWidth()) { pickingSource = true }
        if (motion == null) {
            LabButton("ADD MOTION", scheme, enabled = true, accessibilityLabel = "ADD PRISM MOTION",
                modifier = Modifier.fillMaxWidth()) { edit(PrismMotionEditor::ensureMotion) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, repeat) in listOf("OPEN" to false, "REPEAT" to true)) {
                    ActionButton(label, scheme, enabled = true, lit = motion.repeat == repeat,
                        modifier = Modifier.weight(1f).semantics { selected = motion.repeat == repeat },
                        accessibilityLabel = "PRISM MOTION $label") {
                        edit { PrismMotionEditor.withRepeat(it, repeat) }
                    }
                }
            }
            MacroSlider("AMOUNT", motion.evolve, color, scheme,
                onValueChange = { amount -> edit { PrismMotionEditor.withAmount(it, amount) } })
            MacroSlider("TIME", motion.seconds, color, scheme,
                onValueChange = { seconds -> edit { PrismMotionEditor.withTime(it, seconds) } },
                readout = "${prismSeconds(motion.seconds)} S", valueRange = PrismMotionEditor.MIN_SECONDS..PrismMotionEditor.MAX_SECONDS)
            TapeText(if (motion.repeat) "BODY ↔ TONE · ${prismSeconds(motion.seconds)} S PER CYCLE"
                else "BODY → TONE · ${prismSeconds(motion.seconds)} S TO OPEN", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            if (motion.repeat && Prism.isLoop(patch.macros["HOLD"] ?: 0f)) {
                val fitted = Prism.motionCycleSeconds(patch.voice, patch.macros, motion, patch.template)
                TapeText("HELD CYCLE ≈ ${prismSeconds(fitted)} S", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (kind in listOf("SOURCE", "STATIC", "MOVING")) {
                    LabButton(kind, scheme, enabled = !studio.sourceBusy && (kind != "SOURCE" || source != null),
                        modifier = Modifier.weight(1f), accessibilityLabel = "PREVIEW PRISM $kind") { preview(kind) }
                }
            }
            if (source == null) TapeText("CHOOSE A SOURCE TO HEAR ITS ORIGINAL.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton("RESET", scheme, enabled = true, modifier = Modifier.weight(1f), accessibilityLabel = "RESET PRISM MOTION") {
                studio.cancelSource()
                edit { resetPrismMotion(it, baseline) }
            }
            if (studio.sourceBusy || studio.previewing || studio.ownsPreview) {
                LabButton("STOP", scheme, enabled = true, modifier = Modifier.weight(1f), accessibilityLabel = "STOP PRISM PREVIEW") {
                    studio.cancel()
                    stop(true)
                }
            }
        }
        if (studio.sourceBusy) TapeText("LOADING TONE…", TapeType.pixelSmall, scheme.ink2.tape)
        else if (studio.previewing) TapeText("PREPARING PREVIEW…", TapeType.pixelSmall, scheme.ink2.tape)
        studio.error?.let { TapeText(it, TapeType.pixelSmall, scheme.warn.tape, maxLines = 2) }
    }
    if (pickingSource) PrismSourceDialog(source?.id, scheme, ::selectSource) { pickingSource = false }
}

private fun prismSeconds(seconds: Float): String = String.format(Locale.ROOT, "%.2f", seconds)

@Composable
private fun PrismSourceDialog(selectedId: String?, scheme: Scheme, onSelect: (PrismMotionSources.Source) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(14.dp).raisedBevel(scheme).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText("CHOOSE A TONE", TapeType.lcdSmall, scheme.ink.tape)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (source in PrismMotionSources.all()) {
                    LabButton("${source.patch.engine} · ${source.title}", scheme, enabled = true,
                        modifier = Modifier.fillMaxWidth().semantics { selected = source.id == selectedId },
                        accessibilityLabel = "MOTION SOURCE ${source.title}") { onSelect(source) }
                }
            }
            LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}
