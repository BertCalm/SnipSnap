package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.MidiGroove
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.VoiceAllocator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GROOVE: the needle-roll over a kit's captured break — five derived-or-
 * forked programs (A–E) scrolling under a fixed needle, swing, humanize,
 * the fork-to-E step editor, and MIDI export. See `design/HANDOFF.md`
 * "GROOVE screen" and `TapeOS Oilslick.dc.html`'s `isGroove` state for the
 * source of truth this file renders.
 *
 * A–D are pure functions of the captured base clip ([GrooveVariations]) —
 * recomputed live, never stored. E is the one stateful program: forked
 * once via [GrooveEdit.fork], then mutated in place by the step editor and
 * persisted through [GrooveEdit.save]. Playback is a single
 * `withFrameNanos` clock (dt clamped — TAPE's own lesson, see
 * `TapeScreen.kt`) advancing a step position that both programs read: the
 * needle-roll's own scroll and, while [GrooveEdit] is open, the editor
 * overlay's playhead ring.
 */

/** Ceiling on one clock tick's elapsed time — a stale `withFrameNanos` gap (backgrounding, a debugger pause) must never fast-forward the needle. */
private const val GROOVE_STEP_MAX_NANOS = 100_000_000L

/** E's debounced disk write — one save per burst of taps, not one per tap (the PAD SHEET lesson). */
private const val GROOVE_SAVE_DEBOUNCE_MS = 1000L

/**
 * HUMANIZE's jitter amount, passed straight to [GrooveVariations.humanize].
 * The handoff names the control but not a number; 0.5 mirrors the
 * artboard's own captured-groove mock (`(rnd-0.5)*0.5` of a 16th) —
 * noticeably loose without wandering off the pocket.
 */
private const val GROOVE_HUMANIZE_AMOUNT = 0.5f

/** SWING's range and step, per the handoff; the artboard's own default state is 62%. */
private const val GROOVE_SWING_DEFAULT = 62
private const val GROOVE_SWING_MIN = 50
private const val GROOVE_SWING_MAX = 75
private const val GROOVE_SWING_STEP = 2

/** A note reads "lit" for this many steps after the needle passes it. */
private const val GROOVE_LIT_WINDOW = 0.7f

private val PROG_NAMES = listOf(
    "PROG A · AS CAPTURED",
    "PROG B · SWUNG",
    "PROG C · HALF-TIME",
    "PROG D · SPARSE",
    "PROG E · EDITED",
)
private val PROG_SUBS = listOf(
    "THE BREAK, AS PLAYED",
    "ON THE GRID, PUSHED LATE",
    "ROOM TO BREATHE",
    "THE SKELETON",
    "FORKED — YOUR STEPS",
)
private val PROG_LETTERS = listOf("PROG A", "PROG B", "PROG C", "PROG D")

/** Lane display order, straight from [GrooveEdit.Lane]'s own declaration order. */
private val LANE_ORDER: List<GrooveEdit.Lane> = GrooveEdit.Lane.entries
private val LANE_LABEL: Map<GrooveEdit.Lane, String> = mapOf(
    GrooveEdit.Lane.KICK to "KICK",
    GrooveEdit.Lane.SNARE to "SNARE",
    GrooveEdit.Lane.HAT_CLOSED to "HAT C",
    GrooveEdit.Lane.HAT_OPEN to "HAT O",
    GrooveEdit.Lane.PERC to "PERC",
)
private val LANE_DRUM_CLASS: Map<GrooveEdit.Lane, DrumClass> = mapOf(
    GrooveEdit.Lane.KICK to DrumClass.KICK,
    GrooveEdit.Lane.SNARE to DrumClass.SNARE,
    GrooveEdit.Lane.HAT_CLOSED to DrumClass.HAT_CLOSED,
    GrooveEdit.Lane.HAT_OPEN to DrumClass.HAT_OPEN,
    GrooveEdit.Lane.PERC to DrumClass.PERC,
)

/**
 * The inverse of [GrooveEdit.LANE_SLOT]'s note mapping — what the
 * needle-roll's RENDERER needs to bucket a clip's raw notes into the five
 * drawn lane columns (the design). Playback does NOT gate on this map: a
 * note whose pad sits outside the five lanes (a CLAP, a TOM) still plays —
 * see the playback clock's own comment — it just isn't drawn as a block.
 */
private val NOTE_TO_LANE: Map<Int, GrooveEdit.Lane> = GrooveEdit.Lane.entries.associateBy { GrooveEdit.noteFor(it) }

/**
 * A–E as a pure function of the loaded state — used both by composition
 * (to render) and by the playback clock (to trigger), so the two can never
 * disagree about what's currently on screen.
 */
private fun computeProgram(index: Int, base: Mpc3Clip, swingPercent: Int, seed: Int, eClip: Mpc3Clip?): Mpc3Clip? =
    when (index) {
        0 -> GrooveVariations.humanize(base, GROOVE_HUMANIZE_AMOUNT, seed)
        1 -> GrooveVariations.swing(base, swingPercent)
        2 -> GrooveVariations.halfTime(base)
        3 -> GrooveVariations.sparse(base)
        else -> eClip
    }

@Composable
fun GrooveScreen(
    entry: KitShelf.Entry?,
    appScope: CoroutineScope,
    onToast: (String) -> Unit,
    /** Bumped by App when TAPE rewrote the kit's groove (READ AS GROOVE, STEAL THE FEEL) while this screen may be up. */
    reloadRequest: Int = 0,
) {
    val scheme = LocalScheme.current

    if (entry == null) {
        EmptyGroove(scheme)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kitDir = entry.dir
    val kit = entry.kit

    var base by remember(kitDir) { mutableStateOf<Mpc3Clip?>(null) }
    var eClip by remember(kitDir) { mutableStateOf<Mpc3Clip?>(null) }
    var loading by remember(kitDir) { mutableStateOf(true) }
    LaunchedEffect(kitDir, reloadRequest) {
        loading = true
        val (b, e) = withContext(Dispatchers.IO) {
            // The captured base is first by convention — six call sites
            // across the codebase depend on it; do not reorder.
            GrooveStore.load(kitDir).firstOrNull() to GrooveEdit.load(kitDir)
        }
        base = b
        eClip = e
        loading = false
    }

    // The roll and the editor sound through the same `PadEngine` as PLAY,
    // KIT and KEYS — the last screen to come off M0's interim SoundPool
    // player, which is gone with it. A groove tick can cross several notes
    // in one frame, so the allocator is what keeps a busy bar from
    // outrunning the engine's voices, and what chokes a hat against its
    // own mute group here exactly as it would under a finger.
    val engineContext = LocalContext.current
    val player = remember(kitDir) { PadEngine(deviceSampleRate(engineContext)) }
    var engineUp by remember(kitDir) { mutableStateOf(false) }
    DisposableEffect(kitDir) {
        engineUp = player.start()
        onDispose { player.close() }
    }
    // Keyed on the kit's identity, not the dir: an edit elsewhere (the pad
    // sheet, a texture) hands back a fresh `kit`, and the bank has to
    // follow or the roll plays yesterday's audio.
    LaunchedEffect(entry.kit) { withContext(Dispatchers.IO) { player.load(entry) } }
    val allocator = remember(kitDir) { VoiceAllocator(maxVoices = PadEngine.MAX_VOICES) }
    // Endings drained at screen rate, always — not only while the roll is
    // running, since a tap in the editor makes a voice too.
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            for (id in player.drainEnded()) allocator.voiceEnded(id)
            if (player.needsRestart()) engineUp = player.start()
        }
    }

    /**
     * One pad, one voice. Forced one-shot: neither the roll nor a cell tap
     * has a release gesture to end a gate pad with, which is how the
     * SoundPool preview always played too. A slot with nothing on it is
     * silence rather than a stuck voice — the allocation is handed back.
     */
    fun hit(slot: Int) {
        val pad = entry.kit.pad(slot) ?: return
        if (!engineUp || !player.isUp()) return
        val allocation = allocator.noteOn(slot, 1f, pad.muteGroup, oneShot = true)
        for (voice in allocation.choked + allocation.stolen) player.stop(voice.id)
        if (!player.hit(pad, 1f, allocation.started.id)) allocator.voiceEnded(allocation.started.id)
    }

    if (loading) {
        Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }
    val loadedBase = base
    if (loadedBase == null) {
        // No groove stored — a kit made by CHOP's SEND TO GRID may carry
        // one (the happy path); one that doesn't gets the same shelf
        // treatment as an empty kit list.
        EmptyGroove(scheme)
        return
    }

    var progIndex by remember(kitDir) { mutableIntStateOf(0) }
    var seed by remember(kitDir) { mutableIntStateOf(1) }
    var swingPercent by remember(kitDir) { mutableIntStateOf(GROOVE_SWING_DEFAULT) }
    var playing by remember(kitDir) { mutableStateOf(false) }
    var posSteps by remember(kitDir) { mutableFloatStateOf(0f) }
    var busy by remember(kitDir) { mutableStateOf(false) }

    val progCount = if (eClip != null) 5 else 4
    if (progIndex >= progCount) progIndex = 0

    // Editing: EDIT STEPS forks (or re-enters) PROG E and opens the
    // full-screen step editor. `eClip` IS the editor's working buffer —
    // both the main screen's E program and the overlay read the same
    // state, so there's no separate copy to keep in sync.
    var isEditing by remember(kitDir) { mutableStateOf(false) }
    var editorBar by remember(kitDir) { mutableIntStateOf(0) }
    var editorSourceLabel by remember(kitDir) { mutableStateOf(PROG_LETTERS[0]) }
    var editorDirty by remember(kitDir) { mutableStateOf(false) }
    var editorSaveTick by remember(kitDir) { mutableIntStateOf(0) }

    val currentClip = remember(progIndex, loadedBase, swingPercent, seed, eClip) {
        computeProgram(progIndex, loadedBase, swingPercent, seed, eClip)
    }
    // Playback and MIDI export cover every note; the roll only draws the
    // five lane columns (the design) — this is the honesty line that says
    // so whenever the on-screen program actually has notes the roll can't
    // place.
    val offLaneCount = currentClip?.notes?.count { it.note !in NOTE_TO_LANE } ?: 0

    fun failure(action: String, e: Exception) {
        onToast("$action FAILED: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Writes whatever's dirty in [eClip] right now, on [target] — shared by DONE's immediate flush and the teardown/ON_STOP safety nets. */
    /** The actual write, awaited — DONE's own flush needs to know when this is done, not just that it started. */
    suspend fun saveEditorNow() {
        if (!editorDirty) return
        val c = eClip ?: return
        try {
            withContext(Dispatchers.IO) { GrooveEdit.save(kitDir, c) }
            editorDirty = false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure("SAVE", e)
        }
    }

    /** Fire-and-forget flush for the safety nets (teardown, ON_STOP) — nothing downstream needs to wait on these. */
    fun flushEditorSave(target: CoroutineScope) {
        if (!editorDirty) return
        target.launch { saveEditorNow() }
    }

    // The debounced write itself: every toggle/clear bumps the tick and
    // marks dirty; this is what turns a burst of taps into one disk write.
    LaunchedEffect(kitDir, editorSaveTick) {
        if (editorSaveTick == 0) return@LaunchedEffect
        delay(GROOVE_SAVE_DEBOUNCE_MS)
        flushEditorSave(scope)
    }

    // Teardown: leaving GROOVE (a MenuRow tab switch) mid-edit must not
    // drop a still-pending save — launched on `appScope`, which outlives
    // this composable's own (about-to-be-cancelled) scope, exactly the
    // PAD SHEET pattern.
    DisposableEffect(kitDir) {
        onDispose { flushEditorSave(appScope) }
    }

    // ON_STOP: backgrounding the app stops playback and flushes any
    // pending E save, same as TAPE stopping its own transport.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, kitDir) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                playing = false
                // PLAY's and KIT's lesson: stopping the transport is not
                // stopping the sound. A backgrounded phone should not keep
                // a choke group ringing, nor leave the allocator counting
                // voices whose endings no frame loop is draining.
                allocator.allOff()
                player.allOff()
                flushEditorSave(appScope)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The playback clock — a `withFrameNanos` loop converting elapsed wall
    // time to elapsed steps (TAPE's own dt-clamp lesson against a stale
    // frame gap fast-forwarding the needle). Notes are read live each
    // tick via `computeProgram`, not a value captured when the loop
    // started, so switching programs mid-play changes what's triggered on
    // the very next tick — matching the artboard's own `gToggle`.
    //
    // Every note triggers here, not just the five lane notes the roll
    // draws (see [NOTE_TO_LANE]'s own KDoc) — a groove with a CLAP or TOM
    // hit should still be heard, same as it's still written by MIDI ▸.
    // `noteFor(lane) = 35 + slot` is the writer's whole chromatic map, not
    // a fact specific to the five lanes, so `note - 35` recovers the pad
    // slot for any note; `hit` is silence on a slot with nothing loaded.
    LaunchedEffect(playing, kitDir) {
        if (!playing) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        var lastPos = posSteps
        while (isActive) {
            withFrameNanos { now ->
                val dtNanos = (now - lastNanos).coerceIn(0, GROOVE_STEP_MAX_NANOS)
                lastNanos = now
                val clip = computeProgram(progIndex, loadedBase, swingPercent, seed, eClip)
                if (clip != null && clip.notes.isNotEmpty()) {
                    val totalSteps = (clip.bars * GrooveEdit.STEPS_PER_BAR).toFloat()
                    val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
                    val stepsPerSecond = bpm / 60.0 * 4.0
                    val inc = (dtNanos / 1_000_000_000.0 * stepsPerSecond).toFloat()
                    var np = lastPos + inc
                    for (n in clip.notes) {
                        val p = n.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()
                        val crossed = (p > lastPos && p <= np) ||
                            (np >= totalSteps && p + totalSteps > lastPos && p + totalSteps <= np)
                        if (crossed) hit(n.note - 35)
                    }
                    if (np >= totalSteps) np -= totalSteps
                    lastPos = np
                    posSteps = np
                }
            }
        }
    }

    fun forkToE() {
        if (busy) return
        val source = currentClip ?: return
        busy = true
        val sourceLetter = if (progIndex < 4) PROG_LETTERS[progIndex] else editorSourceLabel
        scope.launch {
            try {
                val (hadE, forked) = withContext(Dispatchers.IO) {
                    GrooveEdit.hasUserProgram(kitDir) to GrooveEdit.fork(kitDir, source)
                }
                eClip = forked
                editorSourceLabel = sourceLetter
                editorBar = 0
                editorDirty = false
                progIndex = 4
                isEditing = true
                if (!hadE) onToast(Copy.FORKED_TO_E)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("FORK", e)
            } finally {
                busy = false
            }
        }
    }

    fun toggleCell(lane: GrooveEdit.Lane, step: Int) {
        val c = eClip ?: return
        val note = GrooveEdit.noteFor(lane)
        val pulses = step * GrooveEdit.STEP_PULSES
        val turningOn = c.notes.none { it.note == note && it.timePulses == pulses }
        eClip = GrooveEdit.toggleStep(c, lane, step)
        editorDirty = true
        editorSaveTick++
        if (turningOn) hit(GrooveEdit.LANE_SLOT.getValue(lane))
    }

    fun clearEditorBar() {
        val c = eClip ?: return
        eClip = GrooveEdit.clearBar(c, editorBar)
        editorDirty = true
        editorSaveTick++
        onToast(Copy.BAR_WIPED)
    }

    /**
     * DONE's own flush is awaited, not fire-and-forget: EDIT STEPS re-forks
     * by re-reading the sidecar from disk (`GrooveEdit.fork`'s early-return
     * path just hands back whatever's stored), so a fresh fork racing an
     * in-flight save could read a stale copy and rewind `eClip` to it.
     * `busy` (which already gates EDIT STEPS/HUMANIZE) closes that window —
     * no mutex needed if the button simply can't fire while this runs.
     */
    fun closeEditor() {
        isEditing = false
        if (!editorDirty) return
        busy = true
        scope.launch {
            try {
                saveEditorNow()
            } finally {
                busy = false
            }
        }
    }

    var midiBusy by remember(kitDir) { mutableStateOf(false) }
    fun exportMidi() {
        if (midiBusy) return
        midiBusy = true
        scope.launch {
            try {
                // The kit's grooves as .mid files: the base plus its three
                // mechanical derivations, plus E when it exists — the same
                // set the A–E selector cycles through, reusing MidiGroove
                // directly rather than going through ExportFormat.MIDI
                // (which writes one clip per call, not this screen's
                // "everything, at once" button).
                val clips = GrooveVariations.standard(loadedBase, swingPercent) + listOfNotNull(eClip)
                val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
                val written = withContext(Dispatchers.IO) {
                    val root = context.getExternalFilesDir("exports")
                        ?: throw IOException("external storage unavailable")
                    val midiDir = File(File(root, Names.sanitizeStem(kit.name)), "midi")
                    clips.map { c ->
                        MidiGroove.writeTo(File(midiDir, "${Names.sanitizeStem(c.name)}.mid"), c, bpm, overwrite = true)
                    }
                }
                onToast("${written.size} MIDI FILES WRITTEN — ANY DAW OPENS THE RHYTHM. THE MPC PLAYS IT TOO.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("MIDI EXPORT", e)
            } finally {
                midiBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                Modifier.fillMaxWidth().height(Layout.LCD_HEADER_H.dp).lcdPanel(scheme).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TapeText("GROOVE", TapeType.lcd(23), scheme.lcdInk.tape)
                    val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
                    TapeText(
                        "%.1f BPM · %d BARS · %d NOTES".format(bpm, currentClip?.bars ?: 0, currentClip?.notes?.size ?: 0),
                        TapeType.lcdSmall,
                        scheme.ink.tape,
                    )
                }
            }

            ProgramSelector(
                name = PROG_NAMES[progIndex],
                sub = PROG_SUBS[progIndex],
                onPrev = { progIndex = (progIndex - 1 + progCount) % progCount },
                onNext = { progIndex = (progIndex + 1) % progCount },
                scheme = scheme,
                modifier = Modifier.fillMaxWidth(),
            )

            NeedleRoll(
                clip = currentClip,
                posSteps = posSteps,
                playing = playing,
                scheme = scheme,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .weight(1.2f)
                        .height(44.dp)
                        .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                        .tapeClick { playing = !playing },
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(if (playing) "■ STOP" else "► PLAY", TapeType.pixel, scheme.lcdInk.tape)
                }
                Row(
                    Modifier.weight(1.6f).height(44.dp).sunkenField(scheme, 6.dp).padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SwingStepper("−", scheme) { swingPercent = (swingPercent - GROOVE_SWING_STEP).coerceAtLeast(GROOVE_SWING_MIN) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TapeText("SWING $swingPercent%", TapeType.pixel, scheme.amber.tape)
                        TapeText("RIDES PROG B", TapeType.pixelSmall, scheme.ink3.tape)
                    }
                    SwingStepper("+", scheme) { swingPercent = (swingPercent + GROOVE_SWING_STEP).coerceAtMost(GROOVE_SWING_MAX) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GrooveActionButton("HUMANIZE ⚄", scheme, Modifier.weight(1f), enabled = !busy) {
                    seed++
                    progIndex = 0
                    onToast(Copy.HUMANIZED)
                }
                GrooveActionButton("EDIT STEPS", scheme, Modifier.weight(1f), enabled = !busy) { forkToE() }
                GrooveActionButton("MIDI ▸", scheme, Modifier.weight(1f), enabled = !midiBusy, accent = true) { exportMidi() }
            }

            TapeText(
                "SAME BREAK, FOUR FEELS — ALL FOUR DUB TO THE MPC'S CLIP LIST.",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            if (offLaneCount > 0) {
                TapeText(Copy.offLane(offLaneCount), TapeType.pixelSmall, scheme.ink2.tape, Modifier.fillMaxWidth())
            }
        }

        if (isEditing) {
            val editing = eClip
            if (editing != null) {
                StepEditorOverlay(
                    clip = editing,
                    editorBar = editorBar.coerceIn(0, editing.bars - 1),
                    sourceLabel = editorSourceLabel,
                    playing = playing,
                    posSteps = posSteps,
                    scheme = scheme,
                    onBarSelect = { editorBar = it },
                    onClear = ::clearEditorBar,
                    onToggle = ::toggleCell,
                    onDone = ::closeEditor,
                )
            }
        }
    }
}

@Composable
private fun EmptyGroove(scheme: Scheme) {
    Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
        TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
    }
}

@Composable
private fun ProgramSelector(
    name: String,
    sub: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.width(44.dp).fillMaxHeight().height(46.dp).raisedBevel(scheme, 6.dp).tapeClick(onPrev),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("◄", TapeType.lcd(19), scheme.ink.tape)
        }
        Column(
            Modifier.weight(1f).height(46.dp).lcdPanel(scheme).padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TapeText(name, TapeType.lcd(19), scheme.amber.tape)
            TapeText(sub, TapeType.pixelSmall, scheme.ink3.tape)
        }
        Box(
            Modifier.width(44.dp).height(46.dp).raisedBevel(scheme, 6.dp).tapeClick(onNext),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("►", TapeType.lcd(19), scheme.ink.tape)
        }
    }
}

@Composable
private fun SwingStepper(label: String, scheme: Scheme, onClick: () -> Unit) {
    Box(
        Modifier.width(32.dp).height(36.dp).raisedBevel(scheme, 4.dp).tapeClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.lcd(19), scheme.ink.tape)
    }
}

@Composable
private fun GrooveActionButton(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(36.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, if (accent) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .let { if (enabled) it.tapeClick(onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (accent) scheme.accent.tape else scheme.ink2.tape)
    }
}

/**
 * The main view: a fixed needle at [Layout.NEEDLE_Y], notes scrolling
 * under it at [Layout.STEP_W] px/step. The needle draws in [Scheme.warn]
 * — deliberately not [Scheme.amber] (OILSLICK spends that slot on cyan; a
 * needle drawn in it would vanish into the readouts it's meant to cross),
 * per `Scheme.warn`'s own KDoc. This is load-bearing design history, not a
 * styling choice to relitigate.
 */
@Composable
private fun NeedleRoll(
    clip: Mpc3Clip?,
    posSteps: Float,
    playing: Boolean,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.lcdPanel(scheme)) {
        val maxHeightPx = with(density) { maxHeight.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            val needleY = Layout.NEEDLE_Y.dp.toPx()
            val stepW = Layout.STEP_W.dp.toPx()
            val noteH = Layout.NOTE_H.dp.toPx()
            val laneLeft = 40.dp.toPx()
            val laneRight = size.width - 8.dp.toPx()
            val columnW = ((laneRight - laneLeft) / LANE_ORDER.size).coerceAtLeast(1f)
            val noteW = columnW * 0.8f

            drawRect(
                color = scheme.warn.tape.copy(alpha = 0.22f),
                topLeft = Offset(0f, needleY - 5.dp.toPx()),
                size = Size(size.width, 10.dp.toPx()),
            )
            drawLine(
                color = scheme.warn.tape,
                start = Offset(0f, needleY),
                end = Offset(size.width, needleY),
                strokeWidth = 2.dp.toPx(),
            )

            clip?.notes?.forEach { n ->
                val lane = NOTE_TO_LANE[n.note] ?: return@forEach
                val laneIndex = LANE_ORDER.indexOf(lane)
                val p = n.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()
                val y = needleY + (p - posSteps) * stepW
                if (y < -noteH || y > size.height) return@forEach

                val hot = playing && p <= posSteps && (posSteps - p) < GROOVE_LIT_WINDOW
                val color = LANE_DRUM_CLASS[lane]?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
                val alpha = if (hot) 1f else (0.35f + 0.55f * n.velocity)
                val x = laneLeft + laneIndex * columnW + (columnW - noteW) / 2f

                if (hot) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.4f),
                        topLeft = Offset(x - 3.dp.toPx(), y - 3.dp.toPx()),
                        size = Size(noteW + 6.dp.toPx(), noteH + 6.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(noteW, noteH),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }

        val totalSteps = (clip?.bars ?: 2) * GrooveEdit.STEPS_PER_BAR
        for (r in 0 until totalSteps step 4) {
            val y = Layout.NEEDLE_Y + (r - posSteps) * Layout.STEP_W
            if (y < -20f || y > maxHeightPx) continue
            val label = "${r / 16 + 1}.${(r % 16) / 4 + 1}"
            val color = if (r % 16 == 0) scheme.amber.tape else scheme.ink3.tape
            TapeText(label, TapeType.pixelSmall, color, Modifier.offset(x = 4.dp, y = with(density) { y.toDp() }))
        }

        val nowBar = (posSteps.toInt() / GrooveEdit.STEPS_PER_BAR) + 1
        val nowBeat = ((posSteps.toInt() % GrooveEdit.STEPS_PER_BAR) / 4) + 1
        TapeText(
            "▶ BAR $nowBar.$nowBeat",
            TapeType.pixelSmall,
            scheme.amber.tape,
            Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = (Layout.NEEDLE_Y + 5).dp),
        )

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            for (lane in LANE_ORDER) {
                val c = LANE_DRUM_CLASS[lane]?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                        .background(scheme.lcd.tape, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    TapeText(LANE_LABEL.getValue(lane), TapeType.pixelSmall, c)
                }
            }
        }
    }
}

/**
 * The fork-to-E step editor, full-screen over the groove screen. E keeps
 * its source's bar count — this renders N bar tabs (a fork from a
 * half-time program can legitimately be 4 bars; 2 is the normal case, not
 * a cap), not a fixed pair.
 *
 * Cells are drawn well under [Layout.MIN_HIT_TARGET] (5 lanes × 16 cells
 * on a 390dp frame leaves roughly 15–20dp per cell after the lane-label
 * rail and gaps) — the artboard itself draws these as `flex:1` cells
 * across the same width budget, sub-44dp by design, so this follows it
 * rather than the general hit-target rule.
 */
@Composable
private fun StepEditorOverlay(
    clip: Mpc3Clip,
    editorBar: Int,
    sourceLabel: String,
    playing: Boolean,
    posSteps: Float,
    scheme: Scheme,
    onBarSelect: (Int) -> Unit,
    onClear: () -> Unit,
    onToggle: (GrooveEdit.Lane, Int) -> Unit,
    onDone: () -> Unit,
) {
    val totalSteps = clip.bars * GrooveEdit.STEPS_PER_BAR
    val playheadBar = if (playing) posSteps.toInt() / GrooveEdit.STEPS_PER_BAR else -1
    val playheadCol = if (playing && playheadBar == editorBar) posSteps.toInt() % GrooveEdit.STEPS_PER_BAR else -1

    // Recomputed only when the clip or the visible bar changes, not every
    // animation frame while the playhead ring is live.
    val onSteps = remember(clip, editorBar) {
        LANE_ORDER.associateWith { lane ->
            val note = GrooveEdit.noteFor(lane)
            (0 until GrooveEdit.STEPS_PER_BAR).filter { col ->
                val step = editorBar * GrooveEdit.STEPS_PER_BAR + col
                clip.notes.any { it.note == note && it.timePulses == step * GrooveEdit.STEP_PULSES }
            }.toSet()
        }
    }

    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).padding(8.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText("STEP EDIT — PROG E", TapeType.lcd(20), scheme.lcdInk.tape, Modifier.weight(1f))
                Box(
                    Modifier
                        .height(30.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(onDone)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("DONE", TapeType.lcd(16), scheme.amber.tape)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (bar in 0 until clip.bars) {
                    val selected = bar == editorBar
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .let { if (selected) it.pressedBevel(scheme, 4.dp) else it.sunkenField(scheme, 4.dp) }
                            .tapeClick { onBarSelect(bar) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText("BAR ${bar + 1}", TapeType.pixelSmall, if (selected) scheme.lcd.tape else scheme.ink2.tape)
                    }
                }
                Box(
                    Modifier.height(26.dp).sunkenField(scheme, 4.dp).tapeClick(onClear).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("CLEAR BAR", TapeType.pixelSmall, scheme.ink2.tape)
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (lane in LANE_ORDER) {
                    val laneColor = LANE_DRUM_CLASS[lane]?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
                    val laneOnSteps = onSteps.getValue(lane)
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(
                            Modifier.width(46.dp).fillMaxHeight().sunkenField(scheme, 3.dp).padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            TapeText(LANE_LABEL.getValue(lane), TapeType.pixelSmall, laneColor)
                        }
                        for (col in 0 until GrooveEdit.STEPS_PER_BAR) {
                            val on = col in laneOnSteps
                            val isPlayhead = col == playheadCol
                            val fillColor = when {
                                on -> laneColor
                                col % 4 == 0 -> scheme.field.tape
                                else -> scheme.lcd.tape
                            }
                            val step = editorBar * GrooveEdit.STEPS_PER_BAR + col
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(fillColor, RoundedCornerShape(3.dp))
                                    .border(
                                        1.dp,
                                        if (isPlayhead) scheme.lcdInk.tape else scheme.grayEdge.tape,
                                        RoundedCornerShape(3.dp),
                                    )
                                    .tapeClick { onToggle(lane, step) },
                            )
                        }
                    }
                }
            }

            TapeText(
                "$totalSteps STEPS · FORKED FROM $sourceLabel · TAP TO TOGGLE — B–D STAY DERIVED FROM A",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
    }
}
