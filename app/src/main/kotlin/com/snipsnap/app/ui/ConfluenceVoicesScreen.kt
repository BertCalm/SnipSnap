package com.snipsnap.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.MixVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Busy
import com.snipsnap.shell.ConfluenceSelectedWorkshop
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.xpm.PadNoteMap
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private data class PassageChoice(
    val id: String,
    val title: String,
    val tradition: String,
    val performer: String,
    val sourceUrl: String,
    val licenseUrl: String,
    val credits: String,
)

private data class HeardPassage(
    val prepared: ConfluenceSelectedWorkshop.Prepared,
    val stereo: FloatArray,
    val sampleRate: Int,
    val seconds: Float,
)

/** The frame owns navigation; only an explicitly heard, unchanged render may become a pad. */
@Composable
fun ConfluenceVoicesScreen(
    entry: KitShelf.Entry?,
    appScope: CoroutineScope,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
) {
    val scheme = LocalScheme.current
    val lifecycle = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val jobs = LocalJobs.current
    var choices by remember { mutableStateOf<List<PassageChoice>>(emptyList()) }
    var settings by remember { mutableStateOf<ConfluenceSelectedWorkshop.Settings?>(null) }
    var prepared by remember { mutableStateOf<ConfluenceSelectedWorkshop.Prepared?>(null) }
    var readySeconds by remember { mutableStateOf<Float?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }
    var creditsOpen by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MixVoice?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var work by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf<String?>("OPENING THE VOICE BANK…") }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var destination by remember(entry?.dir) { mutableStateOf<Int?>(null) }

    fun stop() {
        request++
        work?.cancel(); work = null
        player?.release(); player = null
        working = null
    }

    fun failed(error: Exception) {
        Log.e("ConfluenceVoices", "Voice passage action failed", error)
        refusal = error.message?.takeIf { it.isNotBlank() } ?: "The voice passage could not be opened. Try another passage."
        status = null
    }

    fun change(next: ConfluenceSelectedWorkshop.Settings) {
        if (saving) return
        stop(); settings = next; prepared = null; readySeconds = null; refusal = null
        status = "SETTINGS CHANGED · HEAR BEFORE SAVING"
    }

    DisposableEffect(lifecycle) {
        mounted = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stop(); status = null }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { mounted = false; stop(); lifecycle.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        try {
            val available = offMain {
                ConfluenceSelectedWorkshop.bank.voices.map { voice ->
                    PassageChoice(voice.id, voice.title, voice.tradition, voice.performerCredit,
                        voice.sourceUrl, voice.licenseUrl, ConfluenceSelectedWorkshop.credits(voice.id))
                }
            }
            check(available.isNotEmpty()) { "No recorded passages are available in this build." }
            choices = available
            if (settings == null) settings = ConfluenceSelectedWorkshop.Settings(available.first().id)
            working = null
        } catch (e: Exception) { if (e is CancellationException) throw e; working = null; failed(e) }
    }

    LaunchedEffect(entry?.dir, entry?.kit?.pads) {
        try {
            destination = entry?.let { current -> offMain { KitWrites.mutex.withLock {
                val fresh = KitBuilderModel.open(current.dir)
                (1..128).firstOrNull { fresh.pad(it) == null }
            } } }
        } catch (e: Exception) { if (e is CancellationException) throw e; failed(e) }
    }

    fun hear() {
        val selected = settings ?: return
        if (saving) return
        stop(); val ticket = request
        prepared = null; readySeconds = null; refusal = null; status = null; working = "RENDERING ${selected.mode.name}…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = offMainInterruptible(Dispatchers.Default) {
                    val rendered = ConfluenceSelectedWorkshop.prepare(selected)
                    val sound = rendered.snip
                    val stereo = FloatArray(sound.frameCount * 2)
                    for (frame in 0 until sound.frameCount) for (channel in 0..1) {
                        stereo[frame * 2 + channel] = sound.samples[frame * sound.channels + minOf(channel, sound.channels - 1)] * rendered.previewLevel
                    }
                    HeardPassage(rendered, stereo, sound.sampleRate, sound.durationSeconds)
                }
                if (mounted && request == ticket && settings == selected &&
                    lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    prepared = result.prepared; readySeconds = result.seconds
                    player = MixVoice(result.stereo, result.sampleRate).also { it.start() }
                    status = "${selected.mode.name} PREVIEW READY"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (mounted && request == ticket) failed(e)
            } finally { if (mounted && request == ticket) working = null }
        }
    }

    fun savePad() {
        val exact = prepared ?: return
        val target = entry ?: return
        val slot = destination ?: return
        if (saving || exact.settings != settings) return
        stop(); saving = true; refusal = null
        appScope.launch(Dispatchers.Main.immediate) {
            val board = jobs?.begin(Busy("SAVING VOICE PASSAGE…"))
            try {
                val updated = offMain { KitWrites.mutex.withLock {
                    val fresh = KitBuilderModel.open(target.dir)
                    ConfluenceSelectedWorkshop.makePad(fresh, exact, slot)
                    fresh.save(); fresh.kit
                } }
                onKitUpdated(updated)
                onToast("VOICE PASSAGE SAVED ON ${PadNoteMap.labelForPad(slot)}")
                if (mounted && scope.isActive) {
                    status = "${exact.settings.mode.name} SAVED ON ${PadNoteMap.labelForPad(slot)}"
                    destination = (1..128).firstOrNull { updated.pad(it) == null }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onToast(e.message ?: "The voice passage could not be saved.")
                if (mounted && scope.isActive) {
                    failed(e)
                    destination = offMain { KitWrites.mutex.withLock {
                        runCatching {
                            val fresh = KitBuilderModel.open(target.dir)
                            (1..128).firstOrNull { fresh.pad(it) == null }
                        }.getOrNull()
                    } }
                }
            } finally { board?.end(); if (mounted && scope.isActive) saving = false }
        }
    }

    fun openPad(pad: KitPad) {
        val current = entry ?: return
        if (saving) return
        stop(); val ticket = request
        prepared = null; readySeconds = null; refusal = null; working = "OPENING THE SAVED PASSAGE…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val reopened = offMain { ConfluenceSelectedWorkshop.openPad(current.dir, pad) }
                    ?: throw IllegalArgumentException("This pad has no editable voice passage settings.")
                check(choices.any { it.id == reopened.voiceId }) { "This passage is not available in the current voice bank." }
                if (mounted && request == ticket) {
                    settings = reopened; chooserOpen = false; status = "SAVED SETTINGS READY · HEAR BEFORE SAVING"
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (mounted && request == ticket) failed(e) }
            finally { if (mounted && request == ticket) working = null }
        }
    }

    val selected = settings
    val index = choices.indexOfFirst { it.id == selected?.voiceId }
    val passage = choices.getOrNull(index)
    val adjustable = selected?.mode in listOf(ConfluenceSelectedWorkshop.Mode.SOLO, ConfluenceSelectedWorkshop.Mode.PAIR, ConfluenceSelectedWorkshop.Mode.GROUP)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TapeText("CONFLUENCE VOICES", TapeType.lcdHeader, scheme.ink.tape)
        TapeText("HEAR THE RECORDED PASSAGE FIRST. SOLO, PAIR AND GROUP MAKE A FINITE TEXTURE FROM ITS OWN PITCH MOTION.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
        if (passage != null && selected != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("◄", scheme, enabled = !saving, accessibilityLabel = "Previous voice passage", onClick = {
                    val previous = choices[(index - 1 + choices.size) % choices.size]
                    change(selected.copy(voiceId = previous.id))
                })
                ActionButton("PASSAGE ${index + 1} / ${choices.size}", scheme, enabled = !saving,
                    accessibilityLabel = "Choose voice passage", modifier = Modifier.weight(1f), onClick = { chooserOpen = !chooserOpen })
                ActionButton("►", scheme, enabled = !saving, accessibilityLabel = "Next voice passage", onClick = {
                    change(selected.copy(voiceId = choices[(index + 1) % choices.size].id))
                })
            }
            if (chooserOpen) for (choice in choices) {
                Column(Modifier.fillMaxWidth().raisedBevel(scheme)
                    .tapeClick(label = "Choose passage ${choice.title}", enabled = !saving, onClick = {
                        change(selected.copy(voiceId = choice.id)); chooserOpen = false
                    }).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TapeText(choice.title, TapeType.pixel, scheme.ink.tape, maxLines = Int.MAX_VALUE)
                    TapeText(choice.tradition, TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
                }
            }
            TapeText(passage.title, TapeType.markerBig, scheme.ink.tape, maxLines = Int.MAX_VALUE)
            TapeText(passage.tradition, TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            TapeText(passage.performer, TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            for (modes in listOf(
                listOf(ConfluenceSelectedWorkshop.Mode.SOURCE, ConfluenceSelectedWorkshop.Mode.REFERENCE),
                listOf(ConfluenceSelectedWorkshop.Mode.SOLO, ConfluenceSelectedWorkshop.Mode.PAIR, ConfluenceSelectedWorkshop.Mode.GROUP),
            )) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (mode in modes) ActionButton(mode.name, scheme, enabled = !saving, lit = selected.mode == mode,
                    accessibilityLabel = "Voice mode: ${mode.name}", modifier = Modifier.weight(1f), onClick = { change(selected.copy(mode = mode)) })
            }
            TapeText(when (selected.mode) {
                ConfluenceSelectedWorkshop.Mode.SOURCE -> "THE EXACT RECORDED CROP, AT NATIVE PITCH."
                ConfluenceSelectedWorkshop.Mode.REFERENCE -> "THE SAME PASSAGE WITH CONSTANT GAIN AND EDGE FADES."
                else -> "30-SECOND TEXTURE · NO NOTE OR MELODY CONTROL"
            }, TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
            StepperSlider("VARIATION", selected.variation, "${(selected.variation * 100).roundToInt()}%", scheme.accent.tape, scheme,
                enabled = !saving && adjustable, labelWidth = 104.dp,
                onFractionChange = { change(selected.copy(variation = (it * 40).roundToInt() / 40f)) }, onFractionCommit = {})
            StepperSlider("ROOM", selected.room, "${(selected.room * 100).roundToInt()}%", scheme.accent.tape, scheme,
                enabled = !saving && adjustable, labelWidth = 104.dp,
                onFractionChange = { change(selected.copy(room = (it * 40).roundToInt() / 40f)) }, onFractionCommit = {})
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("HEAR", scheme, enabled = !saving && working == null, modifier = Modifier.weight(1f), onClick = ::hear)
                ActionButton(destination?.let { "SAVE → ${PadNoteMap.labelForPad(it)}" } ?: "SAVE PAD", scheme,
                    enabled = !saving && prepared?.settings == selected && destination != null,
                    modifier = Modifier.weight(1f), onClick = ::savePad)
            }
            if (player != null || working != null) ActionButton("STOP", scheme, enabled = !saving,
                modifier = Modifier.fillMaxWidth(), onClick = { stop(); status = "PREVIEW STOPPED" })
            readySeconds?.let { TapeText("${"%.2f".format(Locale.ROOT, it)}S READY · EXACT HEARD SETTINGS", TapeType.pixelSmall, scheme.ink2.tape) }
            if (entry == null) TapeText("OPEN A KIT TO SAVE A NEW PAD.", TapeType.pixelSmall, scheme.ink2.tape)
            else if (destination == null) TapeText("NO EMPTY PAD. MAKE ROOM BEFORE SAVING.", TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE)
            ActionButton(if (creditsOpen) "CLOSE SOURCE CREDITS" else "SOURCE CREDITS & LICENCE", scheme, enabled = true,
                modifier = Modifier.fillMaxWidth(), onClick = { creditsOpen = !creditsOpen })
            if (creditsOpen) {
                TapeText(passage.credits, TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("SOURCE PAGE", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = {
                        try { uriHandler.openUri(passage.sourceUrl) } catch (e: Exception) { failed(e) }
                    })
                    ActionButton("LICENCE", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = {
                        try { uriHandler.openUri(passage.licenseUrl) } catch (e: Exception) { failed(e) }
                    })
                }
            }
        }
        val line = refusal ?: if (saving) "SAVING THE HEARD PASSAGE…" else working ?: status
        if (line != null) TapeText(line, TapeType.pixelSmall, if (refusal == null) scheme.ink2.tape else scheme.warn.tape, maxLines = Int.MAX_VALUE)
        val saved = entry?.kit?.pads.orEmpty().filter(ConfluenceSelectedWorkshop::isEditable)
        if (saved.isNotEmpty()) {
            TapeText("SAVED VOICE PADS", TapeType.pixelSmall, scheme.ink2.tape)
            for (pad in saved) ActionButton(pad.displayName, scheme, enabled = !saving && choices.isNotEmpty(),
                accessibilityLabel = "Open voice pad ${PadNoteMap.labelForPad(pad.slot)}", modifier = Modifier.fillMaxWidth(), onClick = { openPad(pad) })
        }
    }
}
