package com.snipsnap.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AndroidAudioSink
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.LoopBounce
import com.snipsnap.app.LoopEmpty
import com.snipsnap.app.LoopGrid
import com.snipsnap.app.LoopWrites
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.LoopEngine
import com.snipsnap.loop.Residency
import com.snipsnap.loop.SampleSource
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DroneSource
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long a run of tempo taps has to stop before the change is baked and
 * applied.
 *
 * `OrbitScreen.BPM_SETTLE_MS` is the same idea for the same reason; this one
 * is its own because the two screens' costs differ — a grid re-fits every
 * snip on the change, which is why it is worth waiting for the taps to end.
 */
private const val TEMPO_SETTLE_MS = 350L

/**
 * How long BOUNCE will wait for `LoopEngine` to actually adopt a tempo it
 * was just handed, before giving up and rendering anyway.
 *
 * `LoopEngine.apply` only queues; `playOne` adopts once, at the top of its
 * own next pass — which can be up to one whole interval away if the audio
 * thread is mid-write on the interval already playing. One bar at
 * `Session.MIN_BPM` (40) is 6 seconds; this gives that a comfortable
 * margin without letting a stuck audio thread hang the button forever —
 * giving up and bouncing the (still correct) session data is the same
 * fallback a timed-out `Residency.warm` already leaves the caller with.
 */
private const val BOUNCE_SYNC_TIMEOUT_MS = 8_000L

/**
 * What the grid was loaded from, read once when the bench is entered.
 *
 * [loaded] is null for two different reasons that must not read the same on
 * screen — nothing sent yet, or a sidecar that will not parse — which
 * [emptyLine] already tells apart.
 */
private class LoopBoot(val dir: File, val loaded: Session?, val emptyLine: String)

private fun loadBoot(context: Context): LoopBoot {
    val dir = LoopWrites.dir(context)
    val rate = deviceSampleRate(context)

    // Bake at the device's rate, not the MPC's: nothing converts in the
    // callback because nothing needs to.
    val loaded = runCatching { SessionStore.load(dir) }.getOrNull()
        ?.copy(sampleRate = rate)
        // A drone's span depends on the interval in frames, and the rate
        // just changed under it: re-slice so the tuning promise holds
        // here too (see DroneFit.refit).
        ?.let(DroneFit::refit)
    // Nothing sent yet, or a sidecar that will not parse? The two look
    // identical here (both `null`) and must not read the same on screen:
    // SEND A SNIP FROM SNIPS is false advice for the second, whose next
    // send is refused precisely so the file is not overwritten.
    val emptyLine = if (loaded == null && File(dir, SessionStore.FILE_NAME).isFile) {
        Copy.LOOP_UNREADABLE
    } else {
        Copy.LOOP_EMPTY
    }
    return LoopBoot(dir, loaded, emptyLine)
}

/**
 * The loop player's threads and the objects that live exactly as long as the
 * bench does. [LoopBench] creates one with `remember` and starts and stops it
 * from a `DisposableEffect`, so the audio thread's life is the composable's
 * life — enter the bench and it starts, leave it and it is gone.
 *
 * Each thread has one job. The audio thread runs LoopEngine and blocks on
 * AudioTrack; a pool of two bakes blocks ahead of it; the UI thread reads the
 * engine's position and draws. Between the engine and the UI that is the whole
 * concurrency story: they share an AtomicInteger for position and an
 * AtomicReference for pending edits, and nothing else.
 *
 * One more thread sits beside them, deliberately not [bakers]: [writer], which
 * saves the session. BOUNCE has a thread too and it is not here at all — a
 * render outlives this bench, so it belongs to [LoopBounce] and the app.
 *
 * Constructing one starts no thread: the pools spawn theirs on first submit.
 * That keeps `remember` free of side effects an abandoned composition could
 * leak; [start] is what begins the audio.
 */
private class LoopRig(dir: File) {

    val bakers = Executors.newFixedThreadPool(2)

    /**
     * Drones render here, one at a time, never on [bakers]. A drone is
     * seconds of work: two on the shared pool would hold both its threads,
     * every other track's bake would queue behind them, and the engine would
     * bake those on the audio thread. One thread also caps what drones hold
     * in flight to one render's worth of memory (tens of MB for the longest).
     */
    val droneBaker = Executors.newSingleThreadExecutor()

    /**
     * One thread, for writing the session back — not [bakers].
     *
     * Two reasons, and the second is the one that bit. A sidecar write is
     * nothing beside a bake, but [bakers] is torn down with `shutdownNow()`,
     * which interrupts running tasks AND drops everything still queued: clear
     * a track, press Back immediately, and the write can be thrown away behind
     * both baker threads while the shelf then reads the old file and the
     * cleared track comes back. This one is shut down gracefully instead (see
     * [stop]), so a queued write finishes. Single-threaded also means two
     * edits in quick succession land in the order they were made.
     */
    val writer = Executors.newSingleThreadExecutor()

    /**
     * The one decode cache, shared by playback and BOUNCE.
     *
     * A second `KitSampleSource` for the bounce would decode every piece on
     * the grid all over again — the same WAVs the residency already holds — so
     * the render pays a second copy of the whole session in memory to produce
     * exactly the same audio. It is safe to share: the cache is a
     * `ConcurrentHashMap` and both readers only ever read.
     *
     * Wrapped in a [DroneSource] so drones render, and render once for
     * playback and BOUNCE alike: without it every drone on the grid is
     * silence.
     */
    val source: SampleSource = DroneSource(KitSampleSource(dir))

    /**
     * Kept rather than left inside [start], because the tempo control needs
     * it: a new BPM has to be baked before it is applied, and `warm` is on
     * this.
     */
    var residency: Residency? = null
        private set
    var engine: LoopEngine? = null
        private set
    var sink: AndroidAudioSink? = null
        private set
    private var audioThread: Thread? = null

    fun start(session: Session) {
        val audioSink = AndroidAudioSink(session.sampleRate)
        val res = Residency(session, source, bakers, droneBaker)
        val loopEngine = LoopEngine(res, audioSink)

        sink = audioSink
        residency = res
        engine = loopEngine

        audioThread = thread(name = "snipsnap-audio", isDaemon = true) {
            // Prime here, not on entering the bench. Baking interval 0 is six
            // WAV decodes, six resamples and possibly six slice-retriggers —
            // seconds of work that must never touch the main thread.
            res.buffersFor(0)
            res.prefetch(1)
            loopEngine.run()
        }
    }

    fun stop() {
        engine?.stop()
        audioThread?.join(1_000)
        sink?.close()
        // Bakes are abandoned; writes are not. shutdownNow() on the bakers
        // interrupts a decode nobody is waiting for any more, while the
        // writer is asked to finish what it has: a clear the player made a
        // moment before pressing Back has to reach the disk, or the track
        // comes back next launch and the shelf's count disagrees with the
        // screen they just left.
        bakers.shutdownNow()
        // A drone render stops at the interrupt (ResinDrone checks it).
        droneBaker.shutdownNow()
        writer.shutdown()
        writer.awaitTermination(2, TimeUnit.SECONDS)
        // Nothing to do for a bounce in flight: it is not this bench's
        // thread, does not hold this bench, and finishes into SNIPS on its
        // own. See LoopBounce.
    }
}

/**
 * LOOP as a bench under BEAT (`docs/SCREEN_PLAN_2026_10.md` §3.4 and §3.8):
 * the six-track phasing grid, its tempo row and BOUNCE, drawn inside the
 * frame in the scheme's own colours, with a back chip that reads [backLabel].
 *
 * This is everything the old `LoopActivity` did, with the Activity's
 * lifecycle swapped for the composable's. The engine, its audio thread and
 * the pools around it ([LoopRig]) are created when the bench enters the
 * composition and torn down when it leaves — `onCreate` and `onDestroy`
 * became the two ends of one `DisposableEffect`. Backgrounding the app
 * silences the sink and returning to the foreground reclaims focus, through
 * the same `ON_STOP` / `ON_START` pair every Compose screen uses; the effect
 * adds its observer after the sink exists, so the lifecycle's replay on
 * entry acquires focus the way `onStart` did after `onCreate`.
 *
 * Back is registered unconditionally and leaves the bench: there is no
 * result to hand anyone, because the session on disk is the answer (see
 * `App`'s `filledLoopTracks`). The empty grid's own SNIPS door calls
 * [onGoToSnips] instead, and the caller decides where that lands.
 *
 * [onToast] is called on the main thread, including for a save that finishes
 * after the bench has gone: a clear made just before Back still says whether
 * it reached the disk.
 */
@Composable
fun LoopBench(
    /** The back chip's words: `Navigation.backChip`, so they name where [onBack] lands (BEAT, when GROOVE opened it). */
    backLabel: String,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onGoToSnips: () -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnToast by rememberUpdatedState(onToast)

    BackHandler(onBack = onBack)

    val boot = remember { loadBoot(context) }
    val rig = remember { LoopRig(boot.dir) }

    DisposableEffect(rig) {
        boot.loaded?.let(rig::start)
        // LOOP is the app's one genuinely continuous transport, and
        // AndroidAudioSink is the one voice with a real pause/resume (see
        // AudioFocus's own KDoc). Silencing on backgrounding is the same
        // lesson PLAY/KIT/GROOVE/KEYS/SPLIT/SURFACE already apply through
        // their own ON_STOP handling — and here it also closes a gap those
        // Compose screens don't have: a permanent focus loss
        // (AUDIOFOCUS_LOSS) abandons AudioFocus's own grant without touching
        // its registry (see the class KDoc), so the sink stays paused,
        // registered, but un-focused until something re-acquires. Without
        // this pair, that would mean forever — the sink's write() blocks the
        // audio thread indefinitely with no play/pause control anywhere in
        // LoopGrid to unstick it. Re-acquiring on every ON_START is what
        // reclaims focus after exactly that kind of loss, same as the
        // Compose screens' own ON_START branch.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> rig.sink?.silence()
                Lifecycle.Event.ON_START -> rig.sink?.let { AudioFocus.acquire(it, resume = true) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            rig.stop()
        }
    }

    /**
     * Write the session back, then say whether it landed.
     *
     * On [LoopRig.writer] rather than the main thread: it is a small JSON
     * file, but a file write on the UI thread is a file write on the UI
     * thread. It used to ride the bakers — that pool is already off-thread
     * and a sidecar write is microseconds beside a bake — which was wrong for
     * a reason that has nothing to do with speed: see [LoopRig.writer]'s own
     * note.
     *
     * The toast reports the write, not the intent — a clear that could not be
     * saved is a clear that comes back on the next launch, and the player has
     * to be told that rather than shown a grid that disagrees with the disk.
     * [failed] is the caller's own edit, worded for its own noun: a tempo
     * that did not save is not a track coming back, and a toast that
     * describes the wrong action sends the player looking for the wrong
     * thing.
     */
    fun persist(
        session: Session,
        landed: String = Copy.LOOP_TRACK_CLEARED,
        failed: String = Copy.LOOP_CLEAR_NOT_SAVED,
    ) {
        val dir = boot.dir
        rig.writer.execute {
            // Behind the same lock SNIPS' own → LOOP takes: that one is a
            // read-modify-write of this exact file from another screen, and it
            // must not interleave with this save. See LoopWrites.
            val saved = LoopWrites.writing { runCatching { SessionStore.save(session, dir) }.isSuccess }
            Handler(Looper.getMainLooper()).post { currentOnToast(if (saved) landed else failed) }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip(backLabel, scheme, Modifier.widthIn(min = 72.dp), onClick = onBack)
        }

        // remember, or every recomposition resets the session to what was
        // loaded from disk and throws away the mutes the user just tapped.
        var session by remember { mutableStateOf(boot.loaded) }
        // What the sidecar says, as opposed to what this screen is
        // currently doing — they differ by exactly the mutes below, which
        // are deliberately never written. An edit is persisted from THIS
        // one, so clearing a track does not also quietly save the mutes
        // tapped beside it.
        var onDisk by remember { mutableStateOf(boot.loaded) }
        var interval by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        // A run of taps on − or + is one tempo change, not eight: each tap
        // moves the number on screen at once and restarts this, so the
        // bake-and-apply happens once, when the player stops. ORBIT's own
        // BPM control settles the same way and for the same reason.
        var tempoSettle by remember { mutableStateOf<Job?>(null) }
        // Read from the app, not held here: a render outlives this bench,
        // so opening LOOP again while one is still going has to show
        // BOUNCING… too. `LoopBounce.start` flips this before it returns,
        // so the button changes on the tap rather than a frame later.
        val bouncing by LoopBounce.busy.collectAsState()

        val s = session
        if (s == null) {
            // Never a black screen: say which door fills the grid, or say
            // the file would not read.
            LoopEmpty(
                boot.emptyLine,
                modifier = Modifier.weight(1f),
                // Only the genuinely-empty line gets the door; see
                // LoopEmpty's own KDoc on why LOOP_UNREADABLE does not.
                onGoToSnips = if (boot.emptyLine == Copy.LOOP_EMPTY) onGoToSnips else null,
            )
        } else {
            LoopGrid(
                session = s,
                interval = interval,
                modifier = Modifier.weight(1f),
                onToggleTrack = { t ->
                    val next = s.copy(
                        tracks = s.tracks.mapIndexed { i, track ->
                            if (i == t) track.copy(engaged = !track.engaged) else track
                        },
                    )
                    session = next
                    rig.engine?.apply(next)
                    // Deliberately not saved. A mute is a performance
                    // control — it belongs to this run of the screen, not
                    // to the session on disk — and writing the sidecar on
                    // every tap of a header would be a file write per
                    // gesture. Clearing a track below is an edit, and that
                    // one is written.
                },
                onClearTrack = { t ->
                    val next = SessionBuilder.clear(s, t)
                    session = next
                    rig.engine?.apply(next)
                    // Written from the disk-backed copy, not from `next`:
                    // `next` carries this run's mutes, and the whole point
                    // of not saving a mute is not saving it here either.
                    val written = SessionBuilder.clear(onDisk ?: next, t)
                    onDisk = written
                    persist(written)
                },
                onBpm = { step ->
                    val targetBpm = (s.bpm + step).coerceIn(Session.MIN_BPM, Session.MAX_BPM)
                    // The readout moves now; the audio moves when the
                    // taps stop.
                    session = s.copy(bpm = targetBpm)
                    tempoSettle?.cancel()
                    tempoSettle = scope.launch {
                        delay(TEMPO_SETTLE_MS)
                        // Read live, not the snapshot the tap closed
                        // over: a mute or a clear tapped during the
                        // settle window has already updated `session`
                        // (and, for a clear, the engine and the disk),
                        // and applying the stale snapshot here would
                        // silently undo it. Only the tempo is ours to
                        // carry forward — everything else comes from
                        // whatever the grid is showing right now.
                        // Refit, so a drone re-slices to the span this
                        // tempo needs to stay within its tuning promise.
                        val toWarm = DroneFit.refit((session ?: s).copy(bpm = targetBpm))
                        // Baked BEFORE it is applied, on the baker pool:
                        // a new BPM resizes the interval, and without this
                        // the engine's next buffersFor would bake six
                        // blocks on the audio thread. See Residency.warm.
                        val at = rig.engine?.position() ?: 0
                        withContext(Dispatchers.IO) { rig.residency?.warm(toWarm, at + 1, at + 2) }
                        // Read live AGAIN, not `toWarm`: warm itself can
                        // now take up to Residency.WARM_TIMEOUT_MS, and a
                        // mute or clear landing during THAT wait would be
                        // the same silent-undo bug one re-read up already
                        // fixed for the shorter settle delay — applying
                        // `toWarm` here would just move the race, not
                        // close it.
                        val toApply = DroneFit.refit((session ?: s).copy(bpm = targetBpm))
                        rig.engine?.apply(toApply)
                        // The screen holds the refit too, or the next
                        // mute (built from `session`) would hand the
                        // engine the drone's old span back.
                        session = toApply
                        // A tempo is an edit, so it is written — from the
                        // disk-backed copy, so this does not also save the
                        // mutes tapped beside it.
                        val written = DroneFit.refit((onDisk ?: toApply).copy(bpm = targetBpm))
                        onDisk = written
                        persist(written, Copy.LOOP_TEMPO_SET, Copy.LOOP_TEMPO_NOT_SAVED)
                    }
                },
                bouncing = bouncing,
                onBounce = {
                    scope.launch {
                        // If a tempo tap is still settling, its bpm is on
                        // screen already but the engine has not caught up
                        // yet (see TEMPO_SETTLE_MS) — bouncing `s` as-is
                        // here would render a tempo the speaker is not
                        // actually playing, breaking the promise below.
                        // Joining forces that change to land first; a
                        // completed or absent settle returns at once.
                        //
                        // Joining is necessary but not sufficient: it
                        // only guarantees `engine.apply` was CALLED, and
                        // LoopEngine.apply only stores a pending
                        // reference — LoopEngine.playOne adopts it once,
                        // at the top of its own next pass, which can
                        // still be mid-write on the OLD tempo's interval
                        // when this resumes (sink.write blocks for
                        // roughly one interval). The poll below waits for
                        // that adoption itself, the same bounded shape
                        // Residency.warm uses for the same reason: a
                        // stuck audio thread costs a wait here, never a
                        // hang.
                        tempoSettle?.join()
                        // The session as it is on screen, read live rather
                        // than the `s` this lambda closed over: a mute
                        // tapped mid-render must not change what is being
                        // rendered half way through, but the join above
                        // can itself take up to TEMPO_SETTLE_MS, and `s`
                        // would not see an edit made during that wait.
                        // What is heard is what is bounced, as of now.
                        val target = session ?: s
                        val res = rig.residency
                        if (res != null) {
                            val deadline = System.currentTimeMillis() + BOUNCE_SYNC_TIMEOUT_MS
                            while (res.session().bpm != target.bpm && System.currentTimeMillis() < deadline) {
                                delay(20)
                            }
                        }
                        val refused = LoopBounce.start(context, target, rig.source)
                        if (refused != null) currentOnToast(refused)
                    }
                },
            )

            LaunchedEffect(Unit) {
                while (true) {
                    interval = rig.engine?.position() ?: 0
                    delay(50)
                }
            }
        }
    }
}

/** Duplicated from `BinScreen.kt`'s own private `HeaderChip` rather than exported — every screen-local button in this codebase stays private to its own file, per that file's own stated convention. */
@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            // `enabled` goes through `tapeClick`, not around it: a dimmed
            // chip stays in the semantics tree instead of silently
            // vanishing from it (accessibility audit finding 12).
            .tapeClick(label = label, enabled = enabled, spoken = spokenBack(label), onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}
