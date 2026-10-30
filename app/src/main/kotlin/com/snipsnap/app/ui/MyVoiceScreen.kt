package com.snipsnap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.MediaDecode
import com.snipsnap.app.MixVoice
import com.snipsnap.app.MyVoiceRecorder
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.ConfluencePersonalLibrary
import com.snipsnap.shell.ConfluencePersonalWorkshop
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.synth.ConfluencePersonalVoice
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** One finite personal source, then an ordinary pad. Raw audio is assessed before preparation. */
@Composable
fun MyVoiceScreen(
    entry: KitShelf.Entry?,
    appScope: CoroutineScope,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
) {
    val context = LocalContext.current
    val scheme = LocalScheme.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { File(context.filesDir, ConfluencePersonalWorkshop.LIBRARY_DIR) }
    val recorder = remember(context) { MyVoiceRecorder.microphone(context) }
    var request by remember { mutableIntStateOf(0) }
    var work by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var take by remember { mutableStateOf<Snip?>(null) }
    var rawTake by remember { mutableStateOf<Snip?>(null) }
    var assessment by remember { mutableStateOf<ConfluencePersonalVoice.Assessment?>(null) }
    var prepared by remember { mutableStateOf<ConfluencePersonalWorkshop.Prepared?>(null) }
    var kept by remember { mutableStateOf<ConfluencePersonalLibrary.StoredVoice?>(null) }
    var voices by remember { mutableStateOf<List<ConfluencePersonalLibrary.Summary>>(emptyList()) }
    var origin by remember { mutableStateOf(ConfluencePersonalLibrary.Origin.RECORDING) }
    var startFrame by remember { mutableIntStateOf(0) }
    var endFrame by remember { mutableIntStateOf(0) }
    var trimOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("My voice") }
    var mode by remember { mutableStateOf(ConfluencePersonalWorkshop.Mode.ENSEMBLE) }
    var player by remember { mutableStateOf<MixVoice?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var destination by remember(entry?.dir) { mutableStateOf<Int?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var permissionRequest by remember { mutableIntStateOf(-1) }
    var importRequest by remember { mutableIntStateOf(-1) }

    fun stopRequests() {
        request++
        work?.cancel(); work = null
        recorder.cancel()
        player?.release(); player = null
        recording = false; working = null
    }

    fun failed(error: Exception) {
        Log.e("MyVoice", "Personal voice action failed", error)
        refusal = error.message?.takeIf { it.isNotBlank() } ?: "The voice could not be opened. Try another take."
        status = null
    }

    suspend fun selectTake(raw: Snip, source: ConfluencePersonalLibrary.Origin, ticket: Int) {
        val canonical = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.canonicalTake(raw) }
        val first = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.assess(raw) }
        val start = first.suggestedStartFrame ?: 0
        val end = first.suggestedEndFrame ?: canonical.frameCount
        val selected = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.assess(raw, start, end) }
        val ready = if (selected.usable) offMain(Dispatchers.Default) {
            ConfluencePersonalWorkshop.prepare(raw, start, end)
        } else null
        if (mounted && request == ticket) {
            take = canonical; rawTake = raw; origin = source; assessment = selected; prepared = ready; kept = null
            startFrame = start; endFrame = end; trimOpen = false; name = "My voice"
            mode = ConfluencePersonalWorkshop.Mode.ENSEMBLE
            refusal = if (selected.usable) null else selected.message
            status = if (selected.usable) "A STEADY PASSAGE IS READY. COMPARE YOUR VOICE BELOW." else null
        }
    }

    fun startRecording() {
        if (!mounted || saving) return
        stopRequests()
        val ticket = request
        refusal = null; status = null; working = "STARTING MICROPHONE…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val raw = recorder.record {
                    if (mounted && request == ticket) { recording = true; working = "RECORDING · HOLD ONE STEADY AH OR OO" }
                }
                if (mounted && request == ticket) {
                    recording = false; working = "FINDING A STEADY PASSAGE…"
                    selectTake(raw, ConfluencePersonalLibrary.Origin.RECORDING, ticket)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (mounted && request == ticket) failed(e)
            } finally {
                if (mounted && request == ticket) { recording = false; working = null }
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (mounted && permissionRequest == request && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (result[Manifest.permission.RECORD_AUDIO] == true) startRecording()
            else refusal = "Microphone permission was not granted. Allow it in Settings, or import a recording."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expected = importRequest
        importRequest = -1
        if (uri != null && mounted && expected == request) {
            stopRequests()
            val ticket = request
            refusal = null; status = null; working = "OPENING THE RECORDING…"
            work = scope.launch(Dispatchers.Main.immediate) {
                try {
                    val decoded = offMain { MediaDecode.decode(context, uri, ConfluencePersonalVoice.MAX_SOURCE_SECONDS) }
                    selectTake(decoded, ConfluencePersonalLibrary.Origin.IMPORT, ticket)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (mounted && request == ticket) failed(e)
                } finally { if (mounted && request == ticket) working = null }
            }
        }
    }

    DisposableEffect(owner) {
        mounted = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // A document picker may background the Activity; retain only its explicit result ticket.
                val picking = importRequest == request
                stopRequests()
                if (picking) importRequest = request
                status = null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { mounted = false; stopRequests(); owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { stopRequests(); onBack() }
    LaunchedEffect(library) {
        try { voices = offMain { ConfluencePersonalWorkshop.listVoices(library) } }
        catch (e: Exception) { if (e is CancellationException) throw e; failed(e) }
    }
    LaunchedEffect(entry?.dir) {
        try {
            destination = entry?.let { current -> offMain {
                KitWrites.mutex.withLock {
                    val model = KitBuilderModel.open(current.dir)
                    (1..128).firstOrNull { model.pad(it) == null }
                }
            } }
        } catch (e: Exception) { if (e is CancellationException) throw e; failed(e) }
    }

    fun applyTrim() {
        val source = rawTake ?: return
        stopRequests(); val ticket = request
        val start = startFrame; val end = endFrame
        refusal = null; working = "CHECKING THIS PASSAGE…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val checked = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.assess(source, start, end) }
                val ready = if (checked.usable) offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.prepare(source, start, end) } else null
                if (mounted && request == ticket) {
                    assessment = checked; prepared = ready; kept = null
                    refusal = if (checked.usable) null else checked.message
                    status = if (checked.usable) "TRIM READY. COMPARE YOUR VOICE BELOW." else null
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (mounted && request == ticket) failed(e) }
            finally { if (mounted && request == ticket) working = null }
        }
    }

    fun hear(next: ConfluencePersonalWorkshop.Mode) {
        val selected = prepared
        val original = take ?: return
        val stored = kept
        val from = startFrame
        val until = endFrame
        if (next != ConfluencePersonalWorkshop.Mode.ORIGINAL && selected == null) return
        stopRequests(); val ticket = request
        refusal = null; working = "RENDERING ${next.name}…"; mode = next
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val audible = offMain(Dispatchers.Default) {
                    val sound = when {
                        stored != null -> ConfluencePersonalWorkshop.preparePad(stored, next).snip
                        selected != null -> ConfluencePersonalWorkshop.preview(selected, next)
                        else -> original.copy(samples = original.samples.copyOfRange(from * original.channels, until * original.channels))
                    }
                    val gain = ConfluencePersonalWorkshop.previewLevel
                    val stereo = FloatArray(sound.frameCount * 2)
                    for (frame in 0 until sound.frameCount) for (channel in 0..1) {
                        stereo[frame * 2 + channel] = sound.samples[frame * sound.channels + minOf(channel, sound.channels - 1)] * gain
                    }
                    sound.sampleRate to stereo
                }
                if (mounted && request == ticket && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    player = MixVoice(audible.second, audible.first).also { it.start() }
                    status = "${next.name} PREVIEW READY"
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (mounted && request == ticket) failed(e) }
            finally { if (mounted && request == ticket) working = null }
        }
    }

    fun saveVoice() {
        val selected = prepared ?: return
        if (saving || name.isBlank()) return
        val source = take; val sourceOrigin = origin; val title = name.trim()
        stopRequests(); saving = true; refusal = null
        appScope.launch(Dispatchers.Main.immediate) {
            try {
                val stored = offMain { ConfluencePersonalWorkshop.saveVoice(library, selected, title, sourceOrigin) }
                val all = offMain { ConfluencePersonalWorkshop.listVoices(library) }
                onToast("VOICE SAVED ON THIS PHONE")
                if (scope.isActive && mounted && take === source) { kept = stored; voices = all; status = "VOICE SAVED · READY FOR A NEW PAD" }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onToast(e.message ?: "The personal voice could not be saved.")
                if (scope.isActive && mounted) failed(e)
            } finally { if (scope.isActive && mounted) saving = false }
        }
    }

    fun savePad() {
        val voice = kept ?: return
        val target = entry ?: return
        val proposedSlot = destination ?: return
        if (saving) return
        val selectedMode = mode
        stopRequests(); saving = true; refusal = null
        appScope.launch(Dispatchers.Main.immediate) {
            try {
                val slot = offMain { KitWrites.mutex.withLock {
                    val fresh = KitBuilderModel.open(target.dir)
                    check(fresh.pad(proposedSlot) == null) { "That pad is now occupied. Choose the next empty pad." }
                    proposedSlot
                } }
                val rendered = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.preparePad(voice, selectedMode) }
                val updated = offMain { KitWrites.mutex.withLock {
                    val fresh = KitBuilderModel.open(target.dir)
                    ConfluencePersonalWorkshop.makePad(fresh, voice, rendered, slot)
                    fresh.save(); fresh.kit
                } }
                onKitUpdated(updated)
                onToast("MY VOICE SAVED ON ${PadNoteMap.labelForPad(slot)}")
                if (scope.isActive && mounted) {
                    status = "${selectedMode.name} SAVED ON ${PadNoteMap.labelForPad(slot)} · VOICE KEPT"
                    destination = (1..128).firstOrNull { updated.pad(it) == null }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onToast(e.message ?: "The new pad could not be saved.")
                if (scope.isActive && mounted) {
                    failed(e)
                    destination = offMain { KitWrites.mutex.withLock {
                        runCatching {
                            val fresh = KitBuilderModel.open(target.dir)
                            (1..128).firstOrNull { fresh.pad(it) == null }
                        }.getOrNull()
                    } }
                }
            } finally { if (scope.isActive && mounted) saving = false }
        }
    }

    suspend fun publishStored(stored: ConfluencePersonalLibrary.StoredVoice, ticket: Int) {
        val selected = offMain { ConfluencePersonalWorkshop.openVoice(stored) }
        val raw = offMain { stored.rawSnip }
        val canonical = offMain(Dispatchers.Default) { ConfluencePersonalWorkshop.canonicalTake(raw) }
        if (mounted && request == ticket) {
            take = canonical; rawTake = raw; prepared = selected; assessment = selected.assessment
            startFrame = selected.startFrame; endFrame = selected.endFrame; kept = stored; name = stored.name
            origin = stored.origin; trimOpen = false; mode = ConfluencePersonalWorkshop.Mode.ENSEMBLE
            status = "SAVED VOICE READY"; refusal = null
        }
    }

    fun openVoice(voice: ConfluencePersonalLibrary.Summary) {
        if (saving) return
        stopRequests(); val ticket = request
        refusal = null; working = "OPENING YOUR VOICE…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val stored = offMain { ConfluencePersonalWorkshop.loadVoice(library, voice.id) }
                publishStored(stored, ticket)
            } catch (e: Exception) { if (e is CancellationException) throw e; if (mounted && request == ticket) failed(e) }
            finally { if (mounted && request == ticket) working = null }
        }
    }

    fun openKitVoice(pad: KitPad) {
        val current = entry ?: return
        if (saving) return
        stopRequests(); val ticket = request
        refusal = null; working = "OPENING THE KIT'S VOICE SOURCE…"
        work = scope.launch(Dispatchers.Main.immediate) {
            try {
                val stored = offMain { ConfluencePersonalWorkshop.openPad(current.dir, pad) }
                publishStored(stored, ticket)
            } catch (e: Exception) { if (e is CancellationException) throw e; if (mounted && request == ticket) failed(e) }
            finally { if (mounted && request == ticket) working = null }
        }
    }

    val idle = working == null && !saving
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("◄ MAKE", scheme, enabled = true, onClick = { stopRequests(); onBack() })
            TapeText("MY VOICE", TapeType.lcdHeader, scheme.ink.tape, Modifier.weight(1f))
        }
        TapeText("HOLD ONE COMFORTABLE AH OR OO FOR ABOUT 4–6 SECONDS. QUIET ROOM, NO MUSIC. PREPARED ON THIS PHONE. IMPORTS: ${ConfluencePersonalVoice.MAX_SOURCE_SECONDS} SECONDS OR LESS.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = Int.MAX_VALUE)
        if (recording) ActionButton("STOP EARLY", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = { recorder.finish() })
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("RECORD 6 SECONDS", scheme, enabled = idle, modifier = Modifier.weight(1f), onClick = {
                stopRequests(); permissionRequest = request
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                else permissions.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            })
            ActionButton("IMPORT AUDIO", scheme, enabled = idle, modifier = Modifier.weight(1f), onClick = {
                stopRequests(); importRequest = request; importer.launch(arrayOf("audio/*"))
            })
        }
        val line = refusal ?: working ?: if (saving) "SAVING…" else status
        if (line != null) TapeText(line, TapeType.pixelSmall, if (refusal == null) scheme.ink2.tape else scheme.warn.tape, maxLines = Int.MAX_VALUE)
        if (assessment?.usable == false && refusal == null) TapeText(assessment!!.message, TapeType.pixelSmall, scheme.warn.tape, maxLines = Int.MAX_VALUE)
        val current = take
        if (current != null) {
            TapeText("${"%.2f".format(Locale.ROOT, (endFrame - startFrame).toFloat() / current.sampleRate)}S SELECTED", TapeType.pixelSmall, scheme.ink2.tape)
            ActionButton(if (trimOpen) "CLOSE TRIM" else "ADJUST TRIM", scheme, enabled = idle, onClick = { trimOpen = !trimOpen })
            if (trimOpen) {
                fun edited(start: Int, end: Int) {
                    stopRequests(); startFrame = start; endFrame = end; prepared = null; kept = null
                    refusal = null; status = "TRIM CHANGED · CHECK THIS PASSAGE"
                }
                StepperSlider("START", startFrame.toFloat() / current.frameCount.coerceAtLeast(1), "%.2fS".format(Locale.ROOT, startFrame.toFloat() / current.sampleRate), scheme.accent.tape, scheme, idle,
                    onFractionChange = { edited((it * current.frameCount).roundToInt().coerceIn(0, (endFrame - 1).coerceAtLeast(0)), endFrame) }, onFractionCommit = {})
                StepperSlider("END", endFrame.toFloat() / current.frameCount.coerceAtLeast(1), "%.2fS".format(Locale.ROOT, endFrame.toFloat() / current.sampleRate), scheme.accent.tape, scheme, idle,
                    onFractionChange = { edited(startFrame, (it * current.frameCount).roundToInt().coerceIn((startFrame + 1).coerceAtMost(current.frameCount), current.frameCount)) }, onFractionCommit = {})
                ActionButton("USE THIS TRIM", scheme, enabled = idle, modifier = Modifier.fillMaxWidth(), onClick = ::applyTrim)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (preview in ConfluencePersonalWorkshop.Mode.entries) ActionButton(preview.name, scheme,
                    enabled = idle && (preview == ConfluencePersonalWorkshop.Mode.ORIGINAL || prepared != null), lit = mode == preview,
                    modifier = Modifier.weight(1f), onClick = { hear(preview) })
            }
            if (player != null) ActionButton("STOP PREVIEW", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = { stopRequests(); status = "PREVIEW STOPPED" })
            if (prepared != null) {
                TapeText("VOICE NAME", TapeType.pixelSmall, scheme.ink2.tape)
                Box(Modifier.fillMaxWidth().sunkenField(scheme).padding(10.dp)) {
                    BasicTextField(name, { if (!saving) { name = it.take(80); kept = null } }, enabled = idle, singleLine = true,
                        textStyle = TapeType.marker.copy(color = scheme.ink.tape), cursorBrush = SolidColor(scheme.ink.tape),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = "Voice name" })
                }
                if (kept == null) ActionButton("SAVE VOICE", scheme, enabled = idle && name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = ::saveVoice)
                else {
                    TapeText("VOICE KEPT ON THIS PHONE", TapeType.pixelSmall, scheme.ink2.tape)
                    if (entry == null) TapeText("OPEN A KIT TO SAVE A NEW PAD.", TapeType.pixelSmall, scheme.ink2.tape)
                    else ActionButton(destination?.let { "SAVE ${mode.name} → ${PadNoteMap.labelForPad(it)}" } ?: "NO EMPTY PAD", scheme,
                        enabled = idle && destination != null, modifier = Modifier.fillMaxWidth(), onClick = ::savePad)
                }
            }
        }
        if (voices.isNotEmpty()) {
            TapeText("YOUR SAVED VOICES", TapeType.pixelSmall, scheme.ink2.tape)
            for (voice in voices) ActionButton(voice.name, scheme, accessibilityLabel = "Open personal voice ${voice.name}", enabled = idle,
                modifier = Modifier.fillMaxWidth(), onClick = { openVoice(voice) })
        }
        val phoneIds = voices.map { it.id }.toSet()
        val kitVoices = entry?.kit?.pads.orEmpty().filter {
            it.source["engine"] == ConfluencePersonalWorkshop.ENGINE && !it.source["personalVoiceId"].isNullOrBlank()
        }.distinctBy { it.source["personalVoiceId"] }.filter { it.source["personalVoiceId"] !in phoneIds }
        if (kitVoices.isNotEmpty()) {
            TapeText("VOICES IN THIS KIT", TapeType.pixelSmall, scheme.ink2.tape)
            for (pad in kitVoices) {
                val title = pad.source["personalVoiceName"] ?: pad.displayName
                ActionButton(title, scheme, accessibilityLabel = "Open kit voice $title", enabled = idle,
                    modifier = Modifier.fillMaxWidth(), onClick = { openKitVoice(pad) })
            }
        }
    }
}
