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
import com.snipsnap.app.ShareOut
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
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.shell.Chart
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
 * the fork-to-E step editor, MIDI export, and (live-record plan) RECORD —
 * playing a new take in over the loop, overdub or from scratch. See
 * `design/HANDOFF.md` "GROOVE screen" and `TapeOS Oilslick.dc.html`'s
 * `isGroove` state for the source of truth this file renders.
 *
 * A–D are pure functions of the captured base clip ([GrooveVariations]) —
 * recomputed live, never stored. E is the one stateful program: forked
 * once via [GrooveEdit.fork], then mutated in place by the step editor and
 * persisted through [GrooveEdit.save]. RECORD writes a THIRD kind of
 * change — landing a whole new base clip via `LiveRecord.land` — but
 * still only ever writes `base`/E through those same two paths; there is
 * no fourth persistence route. Playback is a single `withFrameNanos`
 * clock (dt clamped — TAPE's own lesson, see `TapeScreen.kt`) advancing a
 * step position that both programs read: the needle-roll's own scroll
 * and, while [GrooveEdit] is open, the editor overlay's playhead ring;
 * RECORD reads the same clock to interpolate a touch's own timestamp
 * against it (see `recordHit`).
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
 * NAMED lane columns (the design). Playback does NOT gate on this map: a
 * note whose pad sits outside the five lanes (a CLAP, a TOM) still plays —
 * see the playback clock's own comment. It used to be true that such a
 * note also wasn't DRAWN at all (this was a defect, not a design choice —
 * the note is captured, played, and landed regardless); `NeedleRoll`'s
 * Fix 3 (live-record follow-ups) closed that: a note whose lookup here
 * misses falls into a sixth, renderer-only OTHER column instead of being
 * dropped from the drawing.
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
    /** ORBIT ▸ — opens the circular sequencer, the same overlay shape as ARRANGE. */
    onOrbit: () -> Unit = {},
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

    // The roll and the editor sound through the same `PadEngine` CLASS as
    // PLAY, KIT and KEYS — the last screens to come off M0's interim
    // SoundPool player, which is gone with it — NOT a shared instance or a
    // transport that crosses screens: this line constructs GROOVE's own
    // `PadEngine`, disposed with this composable below, exactly as
    // `PlayScreen.kt`'s own instance is disposed with it. There is no
    // real shared transport in this codebase (see the live-record plan's
    // own scope note on why); "same class" is as far as the claim goes. A
    // groove tick can cross several notes in one frame, so the allocator
    // is what keeps a busy bar from outrunning the engine's voices, and
    // what chokes a hat against its own mute group here exactly as it
    // would under a finger.
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
    // `bankReady` (finding D): false until `player.load` commits — before
    // then `clickSampleIndex == -1` and `PadEngine.clickHit` is a no-op, so
    // RECORD's own count-in would play four silent beats with no feedback
    // at all while still arming the take underneath them. Reset to false
    // whenever the effect restarts (a kit swap mid-load must not read as
    // still-ready from the PREVIOUS kit).
    var bankReady by remember(kitDir) { mutableStateOf(false) }
    LaunchedEffect(entry.kit) {
        bankReady = false
        withContext(Dispatchers.IO) { player.load(entry) }
        bankReady = true
    }
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

    // Fix 2 (live-record follow-ups): the count-in previously said only
    // "COUNTING IN…" with nothing to tell the user when the downbeat
    // arrives. Written by `startRecording`'s own click loop as each of the
    // four beats fires (4, 3, 2, 1), read by the three places this file
    // renders the count-in label. Reset to 0 both when the count-in ends
    // normally and when an armed take is dropped (`silenceGroove`) — a
    // stale "COUNTING IN… 1" left on screen after the take vanished would
    // be a lie, same reasoning as `justLanded`'s own reset discipline.
    var countInBeat by remember(kitDir) { mutableIntStateOf(0) }

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
    // Playback and MIDI export cover every note; the roll's five NAMED
    // lane columns only cover five of the kit's pads (the design) — a note
    // outside them still draws too, since Fix 3, just under the sixth
    // OTHER column rather than a named one. This is the honesty line that
    // says so whenever the on-screen program actually has notes like that.
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
        // Blocker B: an armed take must be DROPPED here, exactly as
        // leaving GROOVE entirely already drops one (this composable's own
        // `DisposableEffect(kitDir)` teardown, and Design Question 3's own
        // "abandoned mid-recording is silently dropped, not landed" call).
        // ON_STOP (backgrounding, a phone call) and an AudioFocus loss both
        // route through this function but do NOT leave composition, so
        // without this, `recording`/`countingIn` would survive: `playing`
        // above is now false so the clock effect returns immediately and
        // `clockAnchor` freezes at whatever it last held, and every hit
        // after the user returns clamps to that same frozen instant —
        // distinct pads piling into one chord at one pulse, landed over
        // the base on the next STOP RECORDING.
        //
        // No toast: this is the same class of silent abandonment as
        // leaving the screen mid-take, which has never toasted either —
        // toasting only THIS path would be inconsistent, not more honest.
        // A toast fired here would also show while the app is actively
        // backgrounding (unseen), and one deferred to the return trip is
        // exactly the noise a user coming back from a phone call doesn't
        // need for an action (backgrounding the app) nothing warned them
        // would cost anything to begin with — the take simply isn't there
        // when they look, same as it wouldn't be after a deliberate tab
        // switch.
        if (recording || countingIn) {
            recording = false
            countingIn = false
            take = null
            // Fix 2: a dropped take must not leave a stale "COUNTING IN… N"
            // label on screen for whatever renders next — see
            // `countInBeat`'s own KDoc.
            countInBeat = 0
        }
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
    // The lane notes are just five points on the writer's own chromatic
    // map, not a rule of their own, so `Mpc3Note.slotFor` recovers the pad
    // slot for any note - including the ones past the wrap, where plain
    // subtraction gives a slot no kit has; `hit` is silence on a slot with
    // nothing loaded.
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
        // Fix 1's own seam bug: the count-in's click loop
        // (`startRecording`) fires its four clicks on ITS OWN schedule,
        // then sets `posSteps = 0f` and `playing = true` — which restarts
        // THIS effect with `lastPos = 0f`. The beat-crossing pass below
        // fires on `b > lastPos && b <= np`, so `b = 0` — bar 1's downbeat,
        // the most important click of the whole take — never crosses on
        // this first tick; every LATER pass over the loop does catch it,
        // via the wrap clause. One accented click here, once, at restart,
        // anchors that first beat instead. Gated on `recording`, not
        // `countingIn`: an overdub with the loop already stopped
        // (`startRecording`'s else-branch) also resets `posSteps` to 0 and
        // restarts this same effect, and needs the identical anchor click.
        // A tempo or kit change mid-take (`entry.kit`, one of this
        // effect's own keys) re-enters this same branch too and fires one
        // EXTRA click that isn't a real downbeat — accepted: one spurious
        // click mid-take beats a silent bar 1 every time.
        if (recording) player.clickHit(accent = true)
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
                        // Mpc3Note.slotFor, not `note - 35`: the map wraps, so
                        // notes 0..35 are pads 93..128 and subtracting alone
                        // gives them a slot no kit has. A clip that plays on
                        // the MPC would be silent in this roll.
                        if (crossed) hit(Mpc3Note.slotFor(n.note))
                    }
                }
                // Fix 1 — the metronome through the WHOLE take, not just the
                // count-in: driven from THIS clock's own beat-boundary
                // crossings (same `crossed` shape the note loop above just
                // used, `b` standing in for `p`), never a second delay-based
                // loop timed off System.nanoTime() — that reintroduces the
                // two-timebase bug class the clockAnchor mechanism above
                // already cost this project a shipped defect closing once.
                // A beat is 4 steps (STEPS_PER_BAR / 4 beats per bar);
                // accent lands on every bar downbeat, same as the count-in's
                // own `beat == 0` accent. Gated on `recording`, not
                // `playing` — ordinary PLAY/STOP must stay silent here;
                // only a take actually in progress gets a click to play
                // against.
                if (recording) {
                    for (b in 0 until totalSteps.toInt() step 4) {
                        val crossed = (b > lastPos && b <= np) ||
                            (np >= totalSteps && b + totalSteps > lastPos && b + totalSteps <= np)
                        if (crossed) player.clickHit(accent = b % GrooveEdit.STEPS_PER_BAR == 0)
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
     * STEPS with nothing recorded: an empty one-bar base lands the way a
     * take does, an empty E is forked from it, and the step editor opens on
     * the blank bar — so GROOVE can start from a grid, not only from a
     * performance. One bar, as the MPC gives you; the editor takes it from
     * there.
     */
    fun startSteps() {
        if (busy || base != null) return
        clearJustLanded()
        busy = true
        scope.launch {
            try {
                val (b, e) = withContext(Dispatchers.IO) { GrooveEdit.startEmpty(kitDir, kit.name) }
                base = b
                eClip = e
                editorSourceLabel = PROG_LETTERS[0]
                editorBar = 0
                editorDirty = false
                progIndex = 4
                isEditing = true
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                failure("STEPS", ex)
            } finally {
                busy = false
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

    // CHART ▸ — the program on screen as a monospace drum chart, one text
    // file handed to the system chooser. It reads the same `currentClip`
    // the roll plays and MIDI ▸ writes, so what the chart shows is what
    // this screen is playing right now — including PROG E's edits and the
    // live swing percent. Off-grid hits are drawn in their nearest cell and
    // named in footnotes, never snapped: the J-card's step thumbnail
    // quantizes quietly for a picture, but a chart is a document, and this
    // one draws exactly what is stored.
    var chartBusy by remember(kitDir) { mutableStateOf(false) }
    fun exportChart() {
        val clip = currentClip
        if (clip == null) {
            onToast(Copy.CHART_NEEDS_GROOVE)
            return
        }
        if (chartBusy) return
        clearJustLanded()
        chartBusy = true
        scope.launch {
            try {
                val tempo = kit.tempoBpm
                val program = PROG_NAMES[progIndex].substringBefore(" ·")
                val text = Chart.render(
                    clip, kit,
                    bpm = tempo ?: KitPreview.DEFAULT_BPM,
                    bpmIsDefault = tempo == null,
                    program = program,
                )
                val relative = "exports/${Names.sanitizeStem(kit.name)}/chart/${Names.sanitizeStem(clip.name)}.txt"
                val file = withContext(Dispatchers.IO) {
                    val root = context.getExternalFilesDir("exports")
                        ?: throw IOException("external storage unavailable")
                    val out = File(File(File(root, Names.sanitizeStem(kit.name)), "chart"), "${Names.sanitizeStem(clip.name)}.txt")
                    out.parentFile?.mkdirs()
                    out.writeText(text)
                    out
                }
                val summary = Chart.summary(clip)
                onToast(
                    if (ShareOut.send(context, file, "text/plain", kit.name)) {
                        Copy.chartWritten(summary.notes, summary.offGrid)
                    } else {
                        Copy.chartKept(relative)
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("CHART", e)
            } finally {
                chartBusy = false
            }
        }
    }

    /**
     * One touch, two effects that can never diverge: [hit] makes the sound
     * (and lights the pad, PLAY's own glow shape), and — only while
     * [recording] is true AND [clockAnchor] is actually close to this
     * touch's own timestamp — the same touch is appended to [take]. Every
     * touch still sounds (the user gets to feel the pad respond), captured
     * or not.
     *
     * [recording] alone can no longer stand in for "the take proper has
     * started" (blocker A fix): it now opens the instant RECORD arms, for
     * the WHOLE from-scratch count-in, not just after it — see
     * [startRecording]'s own KDoc for why (the anchor for bar 1's downbeat
     * is valid from that same instant). So capture during a count-in is
     * additionally gated on being close enough to that anchor to plausibly
     * BE the downbeat, not an exploratory tap on an earlier count-in beat:
     * a hit more than [GROOVE_STEP_MAX_NANOS] before the anchor is outside
     * the clamp window below regardless, so filtering it out here (instead
     * of letting it fall through to a clamped, identical-every-time
     * position) avoids piling every early count-in tap onto the same
     * pulse near the loop end — the same pathology blocker B closes for a
     * frozen anchor, just from a different cause.
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
        // Narrows the early-open gate to "a few ms early against the
        // downbeat," per this fn's own KDoc — a tap on an earlier
        // count-in beat is more than the clamp window away and is simply
        // not captured, same as before blocker A's fix.
        if (countingIn && hitNanos < clockAnchor.nanos - GROOVE_STEP_MAX_NANOS) return
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
        t.add(Mpc3Note.noteFor(slot), elapsedSeconds, bpm, velocity)
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
        if (!bankReady) {
            // Finding D: `player.load` hasn't committed yet
            // (`clickSampleIndex == -1`), so the count-in would be four
            // silent clicks with no feedback while still arming the take
            // underneath them. Telling the user beats gating the button
            // outright — no extra `enabled=` plumbing needed across this
            // screen's two RECORD call sites for what resolves itself in
            // well under a second.
            onToast(Copy.KIT_STILL_LOADING)
            return
        }
        clearJustLanded()
        val armedBase = base
        preTake = armedBase
        // Captured by reference, not just read back via `take` later: a
        // dropped-then-re-armed take (silenceGroove nulls `take`, then a
        // second RECORD tap mints a NEW Take before the first count-in's
        // coroutine below wakes up) must be told apart from the CURRENT
        // arm by identity, not by mere nullness — see that coroutine's own
        // abort check.
        val armedTake = LiveRecord.Take(armedBase?.bars ?: recordBars)
        take = armedTake
        progIndex = 0
        if (armedBase == null) {
            // Blocker A: `clockAnchor` must be valid the instant RECORD
            // arms, not 1-2 frames later when the clock effect's own
            // `withFrameNanos` first resolves (which only happens after
            // `playing = true`, itself set only once the count-in
            // finishes). Bar 1's downbeat is exactly
            // `startNanos + 4 * beatNanos` on THIS same schedule — the one
            // the click loop below is timed against — so anchoring there
            // (not at "now") is a unit conversion, not a guess: `pos = 0f`
            // at that future nanosecond is what the count-in itself
            // promises. Computed and written synchronously, before
            // `scope.launch` even schedules the click coroutine, so there
            // is no window — however small — where a hit could read a
            // stale anchor.
            val bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
            val beatNanos = (60_000_000_000.0 / bpm).toLong().coerceAtLeast(1L)
            val startNanos = System.nanoTime()
            clockAnchor.nanos = startNanos + 4 * beatNanos
            clockAnchor.pos = 0f
            countingIn = true
            // Fix 2: set synchronously, in lockstep with `countingIn`
            // itself, rather than waiting for the launched loop below to
            // reach beat 0 — the anchor above is already valid for beat 0
            // this same instant (this function's own KDoc, "Blocker A"),
            // so the label should read "4" from the very first frame, not
            // flash a stale 0 for the one frame before the coroutine wakes.
            countInBeat = 4
            // The capture gate opens with the anchor, not after the
            // count-in completes: `recordHit` gates on `recording`, and
            // with a valid anchor in place a hit fired a few ms EARLY
            // (what a human does against a click) now nets a small
            // NEGATIVE delta instead of being silently discarded —
            // `LiveRecord.wrapped` already folds that to the loop end,
            // the musically identical instant. `countingIn` alone still
            // gates the UI (tap-to-stop stays disabled, "COUNTING IN…"
            // still shows), so nothing about what the user sees or can do
            // during the count-in changes.
            recording = true
            scope.launch {
                for (beat in 0 until 4) {
                    // Blocker B's other half: bail the moment this arm is
                    // no longer the live one — either `silenceGroove`
                    // dropped it (`take` nulled, e.g. an AudioFocus blip
                    // mid-count-in) or a second RECORD tap replaced it with
                    // a NEWER `Take` before this coroutine woke up (`take`
                    // non-null but a different instance). Checked by
                    // identity (`!==`), not nullness, so a re-arm can't
                    // read as "still mine." Inside the loop too, not just
                    // after it, so a drop stops the remaining clicks
                    // instead of clicking through `player.allOff()`.
                    // `scope` (rememberCoroutineScope) outlives ON_STOP;
                    // only leaving composition entirely cancels it.
                    if (take !== armedTake) return@launch
                    // Fix 2: counts DOWN (4, 3, 2, 1) as each click fires —
                    // `beat` itself counts up from 0, so this is the beats
                    // REMAINING including the one about to sound, matching
                    // what a human means by "counting in from 4". Written
                    // only past the identity guard just above, same as
                    // every other write this loop makes once armed — a
                    // superseded or dropped arm must never touch this
                    // state, per this function's own contract.
                    countInBeat = 4 - beat
                    player.clickHit(accent = beat == 0)
                    val deadlineNanos = startNanos + (beat + 1) * beatNanos
                    val waitMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                    if (waitMs > 0) delay(waitMs)
                }
                if (take !== armedTake) return@launch
                countingIn = false
                countInBeat = 0
                posSteps = 0f
                playing = true
            }
        } else {
            if (!playing) {
                // Review fix 2's own gap: resetting `posSteps` without
                // also resetting `clockAnchor` left the anchor holding the
                // ABANDONED needle position — a hit in this window landed
                // in the wrong bar, not merely late. `System.nanoTime()`
                // here (not a `withFrameNanos` read) matches how every
                // other synchronous anchor write in this file is done.
                posSteps = 0f
                clockAnchor.nanos = System.nanoTime()
                clockAnchor.pos = 0f
            }
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
     * no-op. Fix 4 (live-record follow-ups): that no-op used to be
     * completely silent — arm, count in, play nothing, tap STOP, and
     * nothing at all told the user their take didn't land. [Copy.TAKE_SILENT]
     * closes that; see this branch below.
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
        if (t == null || t.notes().isEmpty()) {
            // Fix 4: this was a bare `return` — no toast, no message at
            // all, the button just went back to idle as if nothing had
            // happened. Same silent-failure class this session's other
            // fixes already close for RECORD's other outcomes ([Copy.takeLanded],
            // [Copy.TAKE_UNDONE] and its siblings) — an armed-then-empty
            // take deserves the same honesty.
            onToast(Copy.TAKE_SILENT)
            return
        }
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
                    when {
                        countingIn -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // Fix 2: names the beat, not just the state — see `countInBeat`'s own KDoc.
                            TapeText("COUNTING IN… $countInBeat", TapeType.lcd(19), scheme.amber.tape)
                        }
                        // Blocker C: a from-scratch take was played
                        // completely blind — no needle, no bar/beat
                        // readout, and no base to hear once the count-in's
                        // click stops. `NeedleRoll` already null-safes a
                        // null clip (`totalSteps = (clip?.bars ?: 2) *
                        // STEPS_PER_BAR`, matching `recordBars`'s own
                        // default) — `currentClip` is always null here
                        // (`base` is null throughout this branch), so this
                        // renders unchanged and gets a moving playhead
                        // plus "▶ BAR n.b" for free, the only reference the
                        // user has for where a 2-bar loop wraps. Not shown
                        // during the count-in itself (above): `posSteps`
                        // isn't reset to bar 1 until the count-in's own
                        // coroutine finishes, and `playing` is still false,
                        // so a needle here would read a stale position.
                        recording -> NeedleRoll(
                            clip = currentClip,
                            posSteps = posSteps,
                            playing = playing,
                            scheme = scheme,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            // This is the branch where it matters most:
                            // from-scratch means `currentClip` is null, so
                            // without the live take this roll draws nothing
                            // but its own needle and bar labels — the user
                            // plays a whole take into a blank grid.
                            liveNotes = take?.notes().orEmpty(),
                        )
                        else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TapeText(Copy.EMPTY_GROOVE, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
                        }
                    }
                    if (recording && !countingIn) {
                        // The explicit "recording" cue the needle roll
                        // above doesn't itself say — same label the
                        // original single-Box status line used, kept
                        // alongside the needle rather than replaced by it.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TapeText("● RECORDING — LAY DOWN BAR 1", TapeType.pixel, scheme.amber.tape)
                        }
                    }
                    if (recording || countingIn) {
                        // Live during the count-in too: a hit here always
                        // sounds (the pad responds). Whether it's also
                        // CAPTURED is `recordHit`'s own call — since
                        // blocker A, that's not "wait for the click
                        // sequence to finish" but "close enough to the
                        // downbeat's own anchor" (see that fn's own KDoc);
                        // an exploratory tap on an earlier count-in beat
                        // still sounds here but isn't captured.
                        // WINDOW_GRID_ROWS (bank A as a 4x4), not BankRow. BankRow is
                        // PLAY's FULLSCREEN layout — two banks of eight abreast,
                        // whose own `bankFloor` is 8*MIN_HIT_TARGET + 7*PAD_GAP =
                        // 440dp for ONE bank. This app is portrait-locked at the
                        // manifest, so on a ~390dp phone that floor is never met:
                        // BankRow would always fall to its horizontalScroll branch
                        // and you cannot scroll a pad strip while both hands are
                        // playing it. 4x4 is also what KIT's own GRID_ROWS and
                        // PLAY's windowed view both render, so the grid you record
                        // on is the grid you already know.
                        PlayBank(kit, WINDOW_GRID_ROWS, glow, ::recordHit, {}, Modifier.weight(2f).fillMaxWidth())
                    }
                    if (countingIn || recording) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(Layout.MIN_HIT_TARGET.dp)
                                .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                                .border(1.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                                .let { m -> if (countingIn) m else m.tapeClick(label = null) { stopRecording() } },
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(if (countingIn) "COUNTING IN… $countInBeat" else "■ STOP RECORDING", TapeType.pixel, scheme.lcdInk.tape)
                        }
                    } else {
                        // Three ways in, side by side: play it, tap it, or ring it.
                        // ORBIT needs no groove at all, so it belongs here as much
                        // as on the full screen's action row.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GrooveActionButton("● RECORD", scheme, Modifier.weight(1f), enabled = !busy, accent = true) { startRecording() }
                            GrooveActionButton("STEPS", scheme, Modifier.weight(1f), enabled = !busy, accent = true) { startSteps() }
                            GrooveActionButton("ORBIT ▸", scheme, Modifier.weight(1f), accent = true) {
                                clearJustLanded()
                                onOrbit()
                            }
                        }
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
                            "%.1f BPM · %d BARS · %d NOTES".format(java.util.Locale.ROOT, bpm, currentClip?.bars ?: 0, currentClip?.notes?.size ?: 0),
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
                    // Re-read every frame: `posSteps` advances on each
                    // withFrameNanos tick, so this composition re-runs and
                    // picks up whatever the take has accumulated since.
                    liveNotes = if (recording) take?.notes().orEmpty() else emptyList(),
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
                            // Fix 2: the beat count, same as this screen's other three "COUNTING IN…" render sites.
                            if (countingIn) "COUNTING IN… $countInBeat" else "● RECORDING — OVERDUBBING ONTO PROG A",
                            TapeType.pixel,
                            scheme.amber.tape,
                        )
                    }
                    // WINDOW_GRID_ROWS, not BankRow — see the from-scratch
                    // branch above for why the fullscreen two-bank layout
                    // can't work on a portrait-locked phone.
                    PlayBank(kit, WINDOW_GRID_ROWS, glow, ::recordHit, {}, Modifier.weight(2f).fillMaxWidth())
                    GrooveActionButton(
                        if (countingIn) "COUNTING IN… $countInBeat" else "■ STOP RECORDING",
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GrooveActionButton("SONG ▸", scheme, Modifier.weight(1f), accent = true) {
                            clearJustLanded()
                            onArrange()
                        }
                        // ORBIT ▸ — the kit on rings of different lengths, one
                        // needle speed: polymeter and polyrhythm from the same
                        // pads this screen already has loaded.
                        GrooveActionButton("ORBIT ▸", scheme, Modifier.weight(1f), accent = true) {
                            clearJustLanded()
                            onOrbit()
                        }
                        // CHART ▸ — the program on screen as a text drum chart,
                        // beside MIDI ▸'s row: the same clip, read instead of played.
                        GrooveActionButton(
                            if (chartBusy) Copy.CHART_BUSY else "CHART ▸",
                            scheme,
                            Modifier.weight(1f),
                            enabled = !chartBusy,
                            accent = true,
                        ) { exportChart() }
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
        TapeText(Copy.READ_GROOVE_NEEDS_KIT, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
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
            // No fillMaxHeight here. A Row sizes to its tallest child, so a
            // child filling the incoming max height makes this whole Row
            // claim the Column's remaining space — which starved NeedleRoll's
            // weight(1.6f) to nothing and pushed the action rows and RECORD
            // clean off the screen. The ► button never had it, which is why
            // only ◄ stretched. Both are a plain 48dp square.
            Modifier.width(Layout.MIN_HIT_TARGET.dp).height(Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme, 6.dp).tapeClick(label = null, onClick = onPrev),
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
    /**
     * Notes captured by the take in progress, drawn on top of [clip]'s in
     * the accent colour.
     *
     * Without this the roll shows only what is already SAVED, so a take is
     * invisible while it is being played: you hit eighteen pads, the pads
     * flash, and the roll stays exactly as it was until you stop. On a
     * from-scratch take there is no [clip] at all, so you play into an
     * empty roll with nothing to tell you anything landed. Drawing the
     * live take answers the only question that matters mid-take — "is it
     * getting this?" — and the accent colour answers the second one on an
     * overdub: which of these did I just play.
     */
    liveNotes: List<Mpc3Note> = emptyList(),
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
            // Fix 3 (off-lane pads are invisible in the roll — a DEFECT,
            // not an enhancement): a hit on any pad outside the five drum
            // lanes is still captured, played, and landed (see
            // NOTE_TO_LANE's own KDoc) — it just used to draw nothing,
            // so mid-take the roll answered "is it getting this?" with a
            // visual NO while saying yes to disk. `+ 1` makes room for a
            // sixth, RENDERER-ONLY column those notes fall into below —
            // GrooveEdit.Lane itself stays five entries; this column
            // exists only here, in the drawing, never in the step editor
            // or LANE_SLOT.
            val columnW = ((laneRight - laneLeft) / (LANE_ORDER.size + 1)).coerceAtLeast(1f)
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
                // No `?: return@forEach` here (Fix 3): an off-lane note
                // falls through to the sixth column (`LANE_ORDER.size`)
                // instead of being dropped from the drawing entirely.
                val lane = NOTE_TO_LANE[n.note]
                val laneIndex = lane?.let { LANE_ORDER.indexOf(it) } ?: LANE_ORDER.size
                val p = n.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()
                val y = needleY + (p - posSteps) * stepW
                if (y < -noteH || y > size.height) return@forEach

                val hot = playing && p <= posSteps && (posSteps - p) < GROOVE_LIT_WINDOW
                // An off-lane note has no DrumClass to colour by — `scheme.ink.tape`
                // is already this line's own fallback, so an off-lane hit
                // simply keeps falling through to it, same as before.
                val color = lane?.let { LANE_DRUM_CLASS[it] }?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
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

            // The take in progress, over the saved clip and in the accent
            // colour so an overdub reads as "these are the ones I just
            // played". Same geometry as above deliberately: a note must
            // sit where it will sit once landed, or the roll would be
            // lying about what was captured.
            liveNotes.forEach { n ->
                // Same sixth-column fallback as the saved-clip pass above —
                // the two passes must stay geometrically identical, so a
                // live note sits exactly where it will sit once landed
                // (this composable's own KDoc on `liveNotes`).
                val lane = NOTE_TO_LANE[n.note]
                val laneIndex = lane?.let { LANE_ORDER.indexOf(it) } ?: LANE_ORDER.size
                val p = n.timePulses.toFloat() / GrooveEdit.STEP_PULSES.toFloat()
                val y = needleY + (p - posSteps) * stepW
                if (y < -noteH || y > size.height) return@forEach
                val x = laneLeft + laneIndex * columnW + (columnW - noteW) / 2f
                drawRoundRect(
                    color = scheme.amber.tape.copy(alpha = 0.45f + 0.55f * n.velocity),
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
            // Fix 3's sixth column, legended: same chip shape as the five
            // above, in the same neutral colour the saved-clip pass falls
            // back to for an off-lane note (`scheme.ink.tape`) — not a
            // seventh `GrooveEdit.Lane`, purely this row's own label for
            // the renderer-only column drawn above.
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .border(1.dp, scheme.ink.tape.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                    .background(scheme.lcd.tape, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                TapeText("OTHER", TapeType.pixelSmall, scheme.ink.tape)
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
