package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
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
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
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
import com.snipsnap.kit.LiveRecord
import com.snipsnap.kit.MidiGroove
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
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

/** How long FORK TO E's own armed confirm (replacing an existing PROG E) stays armed before it disarms itself — same window as `TakesBinScreen`'s `EMPTY_BIN_ARM_MS`. */
private const val GROOVE_FORK_ARM_MS = 3_000L

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
    /** SONG ▸ — opens ARRANGE, the same GROOVE-scoped-overlay shape as PAD SHEET's own onGrainField. */
    onArrange: () -> Unit = {},
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
    // `velocity` defaults to the old hardcoded `1f` so both existing
    // internal callers — the playback loop's own note-trigger and the step
    // editor's toggle preview, neither of which has a real touch to read a
    // velocity from — keep sounding exactly as they did before. A future
    // touch caller (a rendered pad grid, per the live-record plan) supplies
    // a real value instead.
    fun hit(slot: Int, velocity: Float = 1f) {
        val pad = entry.kit.pad(slot) ?: return
        if (!engineUp || !player.isUp()) return
        val allocation = allocator.noteOn(slot, velocity, pad.muteGroup, oneShot = true)
        for (voice in allocation.choked + allocation.stolen) player.stop(voice.id)
        if (!player.hit(pad, velocity, allocation.started.id)) allocator.voiceEnded(allocation.started.id)
    }

    // RECORD's own pad grid — PLAY's glow shape (PadGrid.kt's own KDoc
    // flagged this for Task 4): a FULL per-slot map, keyed on the kit's
    // identity like PlayScreen's (`remember(entry.kit)`, not `kitDir`), so
    // a kit edited elsewhere (PAD SHEET, a texture) hands back a fresh
    // `Kit` and the map follows it. A partial map would route a real,
    // assigned pad's `null` glow lookup to `PlayPad`'s EMPTY-pad branch
    // (PadGrid.kt's own KDoc) — every slot needs an entry, not just the
    // ones a lane happens to draw.
    val glow = remember(entry.kit) { kit.pads.associate { it.slot to Animatable(0f) } }
    fun flash(slot: Int) {
        val anim = glow[slot] ?: return
        scope.launch {
            anim.snapTo(1f)
            anim.animateTo(0f, tween(Motion.PAD_GLOW_MS))
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }
    if (kit.pads.isEmpty()) {
        // Nothing to record with, groove or no groove — same shelf
        // treatment as an empty kit list everywhere else in this app.
        EmptyGroove(scheme)
        return
    }

    var progIndex by remember(kitDir) { mutableIntStateOf(0) }
    var seed by remember(kitDir) { mutableIntStateOf(1) }
    var swingPercent by remember(kitDir) { mutableIntStateOf(GROOVE_SWING_DEFAULT) }
    var playing by remember(kitDir) { mutableStateOf(false) }
    var posSteps by remember(kitDir) { mutableFloatStateOf(0f) }
    var busy by remember(kitDir) { mutableStateOf(false) }

    // RECORD: playing pads in against the clock. `preTake` is the base
    // *before* this take (null on a from-scratch kit) — Task 5's undo
    // snapshot, captured the moment RECORD arms, never touched again until
    // the next arm. `recordBars` is the from-scratch loop length only —
    // 2 bars, "the normal case" per `StepEditorOverlay`'s own KDoc further
    // down this file; an overdub always records against the existing
    // base's own bar count instead, never this one. `take` is the
    // in-flight capture — not compose state, since nothing in the UI
    // needs to recompose when a hit is appended to it, only when
    // `recording`/`countingIn` themselves flip.
    var recording by remember(kitDir) { mutableStateOf(false) }
    var countingIn by remember(kitDir) { mutableStateOf(false) }
    var preTake by remember(kitDir) { mutableStateOf<Mpc3Clip?>(null) }
    var recordBars by remember(kitDir) { mutableIntStateOf(2) }
    var take by remember(kitDir) { mutableStateOf<LiveRecord.Take?>(null) }

    // Task 5: the post-take row (FORK TO E / UNDO TAKE). NOT `preTake`'s
    // own nullness — `preTake == null` is a legitimate snapshot (the
    // from-scratch case), not "nothing to show". This flag is the row's
    // whole lifetime: true the instant a take lands, false the instant
    // it's consumed (UNDO TAKE) or the user does anything else that moves
    // the program on (PROG prev/next, HUMANIZE, FORK TO E/EDIT STEPS, MIDI
    // export, SONG ▸, arming another RECORD) — never a persistent control.
    var justLanded by remember(kitDir) { mutableStateOf(false) }

    // FORK TO E's own armed confirm (bug fix, live-record plan Task 6):
    // [GrooveEdit.fork] silently hands back a pre-existing E when one is
    // already stored — correct for EDIT STEPS (re-entering the editor is
    // SUPPOSED to keep editing the same E) but wrong for this row's FORK
    // TO E, whose whole point is "make my just-landed take grid-perfect."
    // Landing on a stale E there would leave the fresh take unquantized
    // with no feedback at all — the same announces-success-does-nothing
    // failure class this session already fixed twice (HOLD, WIND). So an
    // existing E arms this flag instead of forking blind: the button's
    // own label swaps to "REPLACE E?", and only a SECOND tap actually
    // overwrites. See [forkTakeToE].
    var forkArmed by remember(kitDir) { mutableStateOf(false) }

    // Quietly stands down if the second tap never comes — a stale
    // "REPLACE E?" still armed a minute later would be a trap, not a
    // safety net (same reasoning as TakesBinScreen's own EMPTY_BIN_ARM_MS
    // effect).
    LaunchedEffect(forkArmed) {
        if (forkArmed) {
            delay(GROOVE_FORK_ARM_MS)
            forkArmed = false
        }
    }

    // The playback clock's own `(nanos, pos)` reading, hoisted out of the
    // clock `LaunchedEffect` below so a hit fired between two frames can
    // read where the needle actually was at the LAST tick and interpolate
    // forward from there — not a plain `mutableStateOf`, which would
    // schedule a recomposition on every one of ~60 writes/sec that nothing
    // in the UI tree actually reads (`posSteps` is the state the roll
    // draws from; this is purely the recorder's own reference point).
    class ClockAnchor { var nanos = 0L; var pos = 0f }
    val clockAnchor = remember(kitDir) { ClockAnchor() }

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

    // Null exactly when `base` is: a from-scratch kit has nothing to
    // derive A–D from yet, same as before RECORD existed at all — E alone
    // (reachable only via [LiveRecord.undo]'s own E-without-a-base branch)
    // was already unreachable from this screen before this task and stays
    // that way; RECORD doesn't change what GROOVE can display, only how a
    // base gets here.
    val currentClip = remember(progIndex, base, swingPercent, seed, eClip) {
        base?.let { computeProgram(progIndex, it, swingPercent, seed, eClip) }
    }
    // Playback and MIDI export cover every note; the roll only draws the
    // five lane columns (the design) — this is the honesty line that says
    // so whenever the on-screen program actually has notes the roll can't
    // place.
    val offLaneCount = currentClip?.notes?.count { it.note !in NOTE_TO_LANE } ?: 0

    fun failure(action: String, e: Exception) {
        onToast("$action FAILED: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * Every place that used to write `justLanded = false` bare now goes
     * through here, so `forkArmed` can never outlive the row it belongs
     * to: switching programs, HUMANIZE, EDIT STEPS, MIDI, SONG ▸, or
     * arming another RECORD must all cancel a pending "REPLACE E?"
     * confirm exactly as they already cancel the just-landed row itself —
     * otherwise the NEXT take's row could render already armed, skipping
     * the first tap its own confirm exists for.
     */
    fun clearJustLanded() {
        justLanded = false
        forkArmed = false
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
    fun silenceGroove() {
        playing = false
        // PLAY's and KIT's lesson: stopping the transport is not
        // stopping the sound. A backgrounded phone should not keep
        // a choke group ringing, nor leave the allocator counting
        // voices whose endings no frame loop is draining.
        allocator.allOff()
        player.allOff()
    }
    // A focus loss asks for the same silence (plus the same save-flush
    // safety net) as ON_STOP, so GROOVE's shared AudioFocus registration
    // rides this same effect - see PlayScreen's own copy of this pattern
    // for the acquire/release-on-ON_START reasoning.
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(kitDir) {
        object : AudioVoice {
            override fun silence() {
                silenceGroove()
                flushEditorSave(appScope)
            }
        }
    }
    DisposableEffect(lifecycleOwner, kitDir) {
        AudioFocus.acquire(audioVoice)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    silenceGroove()
                    flushEditorSave(appScope)
                    AudioFocus.release(audioVoice)
                }
                Lifecycle.Event.ON_START -> AudioFocus.acquire(audioVoice)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AudioFocus.release(audioVoice)
        }
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
    // Keyed on the kit too, not just the transport: an edit that reaches
    // this screen while the roll is running reloads the engine's bank, and
    // the loop's own `kit` (its tempo) and `hit` (its pads) have to follow
    // or the roll triggers yesterday's metadata against today's samples.
    // Restarting costs nothing — `lastPos` is read from `posSteps`, which
    // is state, so the needle resumes where it was.
    //
    // RECORD's own requirement (live-record plan, Task 4): position now
    // advances UNCONDITIONALLY whenever `playing` is true, not only when
    // there's a clip with notes to trigger — a from-scratch take has no
    // `base` yet, so `clip` is null throughout the count-in and the take
    // itself, but the clock still has to run (against `recordBars`) for
    // `recordHit` to have anything to interpolate against. Triggering
    // stays conditional on a real clip; advancing does not. `clockAnchor`
    // mirrors `lastNanos`/`lastPos` on every tick so `recordHit`, firing
    // between frames from a pointer callback, reads the SAME reference
    // point this loop just used — one formula, shared, per the plan's own
    // Design Question 5.
    LaunchedEffect(playing, kitDir, entry.kit) {
        if (!playing) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        var lastPos = posSteps
        clockAnchor.nanos = lastNanos
        clockAnchor.pos = lastPos
        while (isActive) {
            withFrameNanos { now ->
                val dtNanos = (now - lastNanos).coerceIn(0, GROOVE_STEP_MAX_NANOS)
                lastNanos = now
                val currentBase = base
                val clip = currentBase?.let { computeProgram(progIndex, it, swingPercent, seed, eClip) }
                val totalSteps = ((clip?.bars ?: recordBars) * GrooveEdit.STEPS_PER_BAR).toFloat()
                val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
                val stepsPerSecond = bpm / 60.0 * 4.0
                val inc = (dtNanos / 1_000_000_000.0 * stepsPerSecond).toFloat()
                var np = lastPos + inc
                if (clip != null && clip.notes.isNotEmpty()) {
                    for (n in clip.notes) {
                        val p = n.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()
                        val crossed = (p > lastPos && p <= np) ||
                            (np >= totalSteps && p + totalSteps > lastPos && p + totalSteps <= np)
                        if (crossed) hit(n.note - 35)
                    }
                }
                if (np >= totalSteps) np -= totalSteps
                lastPos = np
                posSteps = np
                clockAnchor.nanos = now
                clockAnchor.pos = np
            }
        }
    }

    /**
     * EDIT STEPS's own fork: [GrooveEdit.fork]'s early-return (hand back
     * whatever E is already stored) is exactly right here — re-entering
     * the editor is SUPPOSED to keep editing the same E, not discard it.
     * NOT used by the post-take row's FORK TO E — see [forkTakeToE], which
     * needs the opposite default for the opposite reason.
     */
    fun forkToE() {
        if (busy) return
        val source = currentClip ?: return
        clearJustLanded()
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

    /**
     * The post-take row's FORK TO E (bug fix, live-record plan Task 6).
     * [forkToE]'s early-return-the-existing-E default is wrong here: this
     * offer's whole point is "make my just-landed take grid-perfect," so
     * silently handing back a stale E would leave the fresh take
     * unquantized with zero feedback — a control that claims an effect it
     * doesn't deliver. Neither silently replacing a hand-edited E nor
     * silently doing nothing is acceptable, so an existing E arms
     * [forkArmed] instead of forking blind — the button's own label swaps
     * to "REPLACE E?" — and only a second tap actually overwrites.
     *
     * `eClip != null` (not a fresh `GrooveEdit.hasUserProgram(kitDir)` disk
     * read) decides whether to arm: this is a plain click handler, called
     * synchronously, so there's no `withContext(Dispatchers.IO)` to hang a
     * disk read off before deciding. `eClip` is kept current by every path
     * that writes E ([forkToE] sets it; [undoTake]'s own KDoc states undo
     * never touches E), so the only way it can disagree with disk is if
     * something else deleted E in the same instant — which would only make
     * this arm a confirm that turns out unnecessary (`GrooveEdit.fork`
     * still re-reads the sidecar itself and writes what's actually there),
     * never skip a confirm that was needed.
     */
    fun forkTakeToE() {
        if (busy) return
        val source = currentClip ?: return
        val existingE = eClip != null
        if (existingE && !forkArmed) {
            forkArmed = true
            return
        }
        clearJustLanded()
        busy = true
        val sourceLetter = if (progIndex < 4) PROG_LETTERS[progIndex] else editorSourceLabel
        scope.launch {
            try {
                val forked = withContext(Dispatchers.IO) { GrooveEdit.fork(kitDir, source, replace = existingE) }
                eClip = forked
                editorSourceLabel = sourceLetter
                editorBar = 0
                editorDirty = false
                progIndex = 4
                isEditing = true
                onToast(if (existingE) Copy.FORKED_TO_E_REPLACED else Copy.FORKED_TO_E)
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
        val exportBase = base ?: return
        if (midiBusy) return
        clearJustLanded()
        midiBusy = true
        scope.launch {
            try {
                // The kit's grooves as .mid files: the base plus its three
                // mechanical derivations, plus E when it exists — the same
                // set the A–E selector cycles through, reusing MidiGroove
                // directly rather than going through ExportFormat.MIDI
                // (which writes one clip per call, not this screen's
                // "everything, at once" button).
                val clips = GrooveVariations.standard(exportBase, swingPercent) + listOfNotNull(eClip)
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

    /**
     * One touch, two effects that can never diverge: [hit] makes the sound
     * (and lights the pad, PLAY's own glow shape), and — only while
     * [recording] is actually true, not merely [countingIn] — the same
     * touch is appended to [take]. A hit during count-in still sounds (the
     * user gets to feel the pad respond) but is never captured; there is
     * no take running yet to capture it into.
     *
     * [uptimeMillis] is the touch's own hardware timestamp
     * (`PointerInputChange.uptimeMillis`, threaded through from
     * `PadGrid.kt`'s `onHit` — see that file's own KDoc for the timebase
     * proof), converted to nanoseconds and measured against
     * [clockAnchor]'s last tick — NOT a fresh clock read taken here, which
     * would bake in whatever time has passed since dispatch on top of the
     * hardware's own input latency (ledger Q2). The delta is clamped
     * symmetrically: unlike the clock loop's own frame-to-frame dt (which
     * is never negative), a touch can be timestamped slightly BEFORE
     * [clockAnchor]'s last tick if it lands early in a Choreographer pass,
     * and that small negative offset is real sub-frame precision, not
     * staleness — flooring it to zero would throw away exactly what this
     * task is for. [LiveRecord.wrapped] folds either sign correctly into
     * the loop.
     */
    fun recordHit(slot: Int, velocity: Float, uptimeMillis: Long) {
        hit(slot, velocity)
        flash(slot)
        val t = take
        if (!recording || t == null) return
        val hitNanos = uptimeMillis * 1_000_000L
        val dtNanos = (hitNanos - clockAnchor.nanos).coerceIn(-GROOVE_STEP_MAX_NANOS, GROOVE_STEP_MAX_NANOS)
        val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
        val stepsPerSecond = bpm / 60.0 * 4.0
        val posAtHit = clockAnchor.pos + (dtNanos / 1_000_000_000.0 * stepsPerSecond)
        // Steps -> the "elapsed seconds" LiveRecord.pulsesFor wants, via
        // the SAME stepsPerSecond — dividing back out is exact algebra,
        // not a second, independently-tuned conversion: pulsesFor(s, bpm)
        // = s * (bpm/60) * 960, and s = posAtHit / stepsPerSecond makes
        // that resolve to posAtHit * STEP_PULSES, the identical quantity
        // the needle-roll and playback clock already use for `p`.
        val elapsedSeconds = posAtHit / stepsPerSecond
        t.add(slot + 35, elapsedSeconds, bpm, velocity)
    }

    /**
     * Arms RECORD. `preTake` is snapshotted here, once, regardless of
     * which branch follows — Task 5's undo needs to know what was there
     * BEFORE this take even in the from-scratch case, where that's `null`.
     * Forces PROG A: recording always plays in against the base's own
     * humanized read, never a derived program with a different bar count
     * (HALF-TIME doubles `bars` — [LiveRecord.toClip] requires the take
     * and the existing base agree on bar count, so this isn't optional).
     *
     * From-scratch (`base == null`): one bar of count-in — four
     * [PadEngine.clickHit]s, accented on the first — because there is no
     * loop yet to cue the user off of (Design Question 3). Each click is
     * scheduled against a DEADLINE computed once from a single
     * `System.nanoTime()` anchor (`startNanos + (beat+1) * beatNanos`),
     * not four sequential `delay(beatMs)` calls — `delay` is a floor, not
     * a deadline, so naively re-adding `beatMs` four times in a row
     * compounds scheduler jitter into real, audible drift between what
     * the clicks counted and when the clock actually starts. `posSteps`
     * is reset to 0 right before `playing = true` so the clock's own
     * downbeat lands where the fourth click implied it would, modulo the
     * one frame of restart slop `playing = true` itself costs (the same
     * "restarting costs nothing" cost normal PLAY/STOP toggling already
     * pays — see the clock effect's own KDoc — bounded and tiny next to
     * the multi-beat drift this scheduling fixes).
     *
     * Against an existing base: no count-in — the loop is already audibly
     * playing (or `playing = true` starts it here), and playing IS the
     * cue, matching every other drum machine's "record over what's
     * already going." If it WASN'T already playing (stopped, needle left
     * mid-bar), `posSteps` resets to 0 too: Design Question 3's "the loop
     * is already audibly playing" premise doesn't hold in that sub-case,
     * and starting the take from wherever the needle happened to be
     * abandoned would give no downbeat reference at all — the base's own
     * bar 1 is the only sane default when there's no click to lean on.
     */
    fun startRecording() {
        if (busy || recording || countingIn) return
        clearJustLanded()
        val armedBase = base
        preTake = armedBase
        take = LiveRecord.Take(armedBase?.bars ?: recordBars)
        progIndex = 0
        if (armedBase == null) {
            countingIn = true
            scope.launch {
                val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
                val beatNanos = (60_000_000_000.0 / bpm).toLong().coerceAtLeast(1L)
                val startNanos = System.nanoTime()
                for (beat in 0 until 4) {
                    player.clickHit(accent = beat == 0)
                    val deadlineNanos = startNanos + (beat + 1) * beatNanos
                    val waitMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                    if (waitMs > 0) delay(waitMs)
                }
                countingIn = false
                recording = true
                posSteps = 0f
                playing = true
            }
        } else {
            if (!playing) posSteps = 0f
            recording = true
            playing = true
        }
    }

    /**
     * Stops RECORD, lands whatever was captured, and updates `base`
     * directly (Global Constraints: never bump `App.kt`'s `reloadRequest`
     * on GROOVE's own write — that would re-run this screen's own load
     * effect mid-take and blank it). A silent take (armed, then stopped
     * with nothing played) lands nothing — [LiveRecord.land] itself
     * refuses an empty note list, so this checks first rather than
     * surfacing that refusal as a failure toast for what's actually a
     * no-op.
     *
     * `eClip`/local E state is untouched: [LiveRecord.land] re-reads E
     * fresh from disk and rides it along unmodified, so there's nothing
     * here for this function to reconcile.
     *
     * A landed take shows `justLanded` (Task 5): the completion this take
     * currently lacked otherwise — a toast naming what was actually played,
     * plus the FORK TO E / UNDO TAKE row below.
     */
    fun stopRecording() {
        if (!recording) return
        recording = false
        val t = take
        take = null
        if (t == null || t.notes().isEmpty()) return
        val landBase = base
        val name = landBase?.name ?: "${kit.name} Take"
        scope.launch {
            try {
                val clip = LiveRecord.toClip(t, name, existing = landBase)
                withContext(Dispatchers.IO) { LiveRecord.land(kitDir, clip) }
                base = clip
                justLanded = true
                onToast(Copy.takeLanded(clip.notes.size, clip.bars))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("RECORD", e)
            }
        }
    }

    /**
     * UNDO TAKE: single-level, matching Design Question 4 — [preTake] is
     * the one pre-arm snapshot [startRecording] captured, not a stack.
     * Delegates the actual restore to [LiveRecord.undo], which already
     * implements all three branches (existing base restored; from-scratch-
     * with-E restored to E-only; from-scratch-with-nothing restored to no
     * `groove.json`) — this function only decides which of the three
     * honest toasts to show, using the SAME two facts [LiveRecord.undo]
     * itself branches on: whether [snapshot] is null, and whether an E
     * exists. `eClip`'s local state needs no update here: none of undo's
     * three branches ever touches E, so whatever this composable already
     * holds for it still matches disk after the write.
     *
     * `justLanded` doubles as the re-entry guard: the row that calls this
     * disappears the instant it's tapped once (this function clears it
     * before the write even starts, matching every other busy-guarded
     * action here), so a second tap can't reach this function at all — a
     * missing row, not a caught no-op.
     */
    fun undoTake() {
        if (busy || !justLanded) return
        clearJustLanded()
        val snapshot = preTake
        val hadE = eClip != null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LiveRecord.undo(kitDir, snapshot) }
                base = snapshot
                progIndex = 0
                onToast(
                    when {
                        snapshot != null -> Copy.TAKE_UNDONE
                        hadE -> Copy.TAKE_UNDONE_TO_E
                        else -> Copy.TAKE_UNDONE_EMPTY
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("UNDO", e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val loadedBase = base
        if (loadedBase == null) {
            // From-scratch: no base yet, so none of A–E, swing, HUMANIZE,
            // EDIT STEPS or MIDI have anything to operate on — EmptyGroove's
            // own message stays the resting state, RECORD is the only
            // control, and a take landing here is what turns this into the
            // full screen below on the very next recomposition (`base`
            // going non-null is a plain state update, not a re-mount — see
            // this function's own KDoc on why the early return moved past
            // the state declarations instead of forking into a second
            // composable).
            Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            countingIn -> TapeText("COUNTING IN…", TapeType.lcd(19), scheme.amber.tape)
                            recording -> TapeText("● RECORDING — LAY DOWN BAR 1", TapeType.lcd(19), scheme.amber.tape)
                            else -> TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
                        }
                    }
                    if (recording || countingIn) {
                        // Live during the count-in too, not just once
                        // `recording` flips true: a hit here still sounds
                        // (the pad responds), it's only the CAPTURE
                        // (`recordHit`'s own `recording` gate) that waits
                        // for the click sequence to finish.
                        BankRow(kit, glow, ::recordHit, {}, Modifier.weight(2f).fillMaxWidth())
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Layout.MIN_HIT_TARGET.dp)
                            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                            .border(1.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                            .let { m ->
                                if (countingIn) {
                                    m
                                } else {
                                    m.tapeClick(label = null) { if (recording) stopRecording() else startRecording() }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            when {
                                countingIn -> "COUNTING IN…"
                                recording -> "■ STOP RECORDING"
                                else -> "● RECORD"
                            },
                            TapeType.pixel,
                            scheme.lcdInk.tape,
                        )
                    }
                }
            }
        } else {
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
                    // Locked to PROG A while RECORD is armed or counting
                    // in: switching to a derived program mid-take (HALF-
                    // TIME doubles `bars`) would desync the clock's own
                    // wrap point from `take.bars`, set once at arm time —
                    // see startRecording's own KDoc.
                    onPrev = { if (!recording && !countingIn) { clearJustLanded(); progIndex = (progIndex - 1 + progCount) % progCount } },
                    onNext = { if (!recording && !countingIn) { clearJustLanded(); progIndex = (progIndex + 1) % progCount } },
                    scheme = scheme,
                    modifier = Modifier.fillMaxWidth(),
                )

                NeedleRoll(
                    clip = currentClip,
                    posSteps = posSteps,
                    playing = playing,
                    scheme = scheme,
                    modifier = Modifier.weight(if (recording || countingIn) 1f else 1.6f).fillMaxWidth(),
                )

                if (recording || countingIn) {
                    // RECORD's own surface, in place of the controls
                    // below: PLAY/STOP is meaningless here (RECORD already
                    // implies PLAY, and stopping playback mid-take would
                    // freeze the clock `recordHit` interpolates against
                    // without stopping the take), and HUMANIZE/EDIT STEPS/
                    // MIDI all need a settled base, not one mid-overdub.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TapeText(
                            if (countingIn) "COUNTING IN…" else "● RECORDING — OVERDUBBING ONTO PROG A",
                            TapeType.pixel,
                            scheme.amber.tape,
                        )
                    }
                    BankRow(kit, glow, ::recordHit, {}, Modifier.weight(2f).fillMaxWidth())
                    GrooveActionButton(
                        if (countingIn) "COUNTING IN…" else "■ STOP RECORDING",
                        scheme,
                        Modifier.fillMaxWidth(),
                        enabled = !countingIn,
                        accent = true,
                    ) { stopRecording() }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .weight(1.2f)
                                .height(Layout.MIN_HIT_TARGET.dp)
                                .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                                .border(1.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                                .tapeClick(label = null) { playing = !playing },
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(if (playing) "■ STOP" else "► PLAY", TapeType.pixel, scheme.lcdInk.tape)
                        }
                        Row(
                            // Growing this row's own height is safe (NeedleRoll
                            // above absorbs it via weight(1f)); growing the two
                            // SwingSteppers' *width* to match is not — see
                            // SwingStepper's own KDoc for why their width is
                            // capped below Layout.MIN_HIT_TARGET.
                            Modifier.weight(1.6f).height(Layout.MIN_HIT_TARGET.dp).sunkenField(scheme, 6.dp).padding(horizontal = 3.dp),
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

                    if (justLanded) {
                        // The take just landed — a transient, one-shot pair
                        // of actions (Task 5): FORK TO E calls [forkTakeToE],
                        // NOT the plain [forkToE] EDIT STEPS below uses — an
                        // existing E arms a "REPLACE E?" confirm instead of
                        // silently handing back stale steps (Task 6 bug fix;
                        // see [forkTakeToE]'s own KDoc). UNDO TAKE reaches
                        // for `preTake`, snapshotted once at arm time. Both
                        // — and anything else that moves the program on —
                        // clear this row (and any pending "REPLACE E?" arm)
                        // via `clearJustLanded`; see `justLanded`'s own KDoc.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GrooveActionButton(
                                if (forkArmed) "REPLACE E?" else "FORK TO E ▸",
                                scheme,
                                Modifier.weight(1f),
                                enabled = !busy,
                                accent = true,
                            ) { forkTakeToE() }
                            GrooveActionButton("UNDO TAKE", scheme, Modifier.weight(1f), enabled = !busy) { undoTake() }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GrooveActionButton("HUMANIZE ⚄", scheme, Modifier.weight(1f), enabled = !busy) {
                            clearJustLanded()
                            seed++
                            progIndex = 0
                            onToast(Copy.HUMANIZED)
                        }
                        GrooveActionButton("EDIT STEPS", scheme, Modifier.weight(1f), enabled = !busy) { forkToE() }
                        GrooveActionButton("MIDI ▸", scheme, Modifier.weight(1f), enabled = !midiBusy, accent = true) { exportMidi() }
                    }
                    // SONG ▸ — the same four programs laid into a structure, not
                    // just cycled: intro/theme/variation/the turn/reprise/outro,
                    // one tap away from what this screen already has loaded.
                    GrooveActionButton("SONG ▸", scheme, Modifier.fillMaxWidth(), accent = true) {
                        clearJustLanded()
                        onArrange()
                    }
                    GrooveActionButton("● RECORD", scheme, Modifier.fillMaxWidth(), enabled = !busy && !midiBusy) { startRecording() }

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
            }
        }

        if (isEditing) {
            // Same function as DONE — flushes any pending E save, exactly
            // like the overlay's own DONE chip.
            BackHandler(onBack = ::closeEditor)
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
            Modifier.width(Layout.MIN_HIT_TARGET.dp).fillMaxHeight().height(Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme, 6.dp).tapeClick(label = null, onClick = onPrev),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("◄", TapeType.lcd(19), scheme.ink.tape)
        }
        Column(
            Modifier.weight(1f).height(Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TapeText(name, TapeType.lcd(19), scheme.amber.tape)
            TapeText(sub, TapeType.pixelSmall, scheme.ink3.tape)
        }
        Box(
            Modifier.width(Layout.MIN_HIT_TARGET.dp).height(Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme, 6.dp).tapeClick(label = null, onClick = onNext),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("►", TapeType.lcd(19), scheme.ink.tape)
        }
    }
}

/**
 * Height raised to [Layout.MIN_HIT_TARGET] (was 36dp) — free to grow,
 * since NeedleRoll above absorbs the extra row height. Width only raised
 * to 40dp (was 32dp), not the full 48: this stepper shares a
 * `SpaceBetween` row (the swing container in `GrooveScreen`, roughly
 * 1.6/2.8 of the frame width after margins - about 200dp) with a
 * two-line text readout ("SWING NN%" / "RIDES PROG B") that has no
 * `weight()` of its own. Two 48dp-wide steppers (+32dp total over the old
 * 32dp) leave that readout markedly less room on a narrower-than-390dp
 * phone; two 40dp steppers (+16dp total) is the width this control
 * reaches without that risk - under the 48dp floor, but a real
 * improvement over 32dp, and height (the axis that was actually free to
 * grow) clears the floor in full.
 */
@Composable
private fun SwingStepper(label: String, scheme: Scheme, onClick: () -> Unit) {
    Box(
        Modifier.width(40.dp).height(Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme, 4.dp).tapeClick(label = null, onClick = onClick),
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
            .height(Layout.MIN_HIT_TARGET.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, if (accent) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .let { if (enabled) it.tapeClick(label = null, onClick = onClick) else it },
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
                        .height(Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(label = null, onClick = onDone)
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
                            .height(Layout.MIN_HIT_TARGET.dp)
                            .let { if (selected) it.pressedBevel(scheme, 4.dp) else it.sunkenField(scheme, 4.dp) }
                            .tapeClick(label = null) { onBarSelect(bar) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText("BAR ${bar + 1}", TapeType.pixelSmall, if (selected) scheme.lcd.tape else scheme.ink2.tape)
                    }
                }
                Box(
                    Modifier.height(Layout.MIN_HIT_TARGET.dp).sunkenField(scheme, 4.dp).tapeClick(label = null, onClick = onClear).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("CLEAR BAR", TapeType.pixelSmall, scheme.ink2.tape)
                }
            }

            // The 16-step grid: hard geometry (16 columns across ~340dp of
            // remaining width after the lane rail, on a 390dp frame) that
            // cannot grow visually without breaking the layout the
            // artboard itself specifies (comment on this composable's own
            // KDoc). Fixed by expanding the *touch* target instead of the
            // drawn size (accessibility audit findings 1/9): each lane Row
            // used to space its 16 cells with `Arrangement.spacedBy(3.dp)`,
            // which left the 3dp gaps between cells as dead touch space —
            // nothing was clickable there. Below, the outer per-cell Box
            // (fillMaxHeight + weight(1f), no gap) is what carries the tap
            // handler and gets the *full* undivided slot; the visual
            // fill/border are drawn on an inset 1.5dp padding inside it, so
            // the painted cell is visually within ~0.2dp of its old size
            // (imperceptible) while its actual hit rect is ~3dp wider,
            // reclaiming exactly the gap that used to be unclickable.
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
                        Row(Modifier.weight(1f).fillMaxHeight()) {
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
                                        .tapeClick(label = null) { onToggle(lane, step) }
                                        .padding(1.5.dp)
                                        .background(fillColor, RoundedCornerShape(3.dp))
                                        .border(
                                            1.dp,
                                            if (isPlayhead) scheme.lcdInk.tape else scheme.grayEdge.tape,
                                            RoundedCornerShape(3.dp),
                                        ),
                                )
                            }
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
