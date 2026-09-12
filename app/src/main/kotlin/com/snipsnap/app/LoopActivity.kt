package com.snipsnap.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.io.File
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.LoopEngine
import com.snipsnap.loop.Residency
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.shell.Copy
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
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
 * The loop player screen.
 *
 * Each thread has one job. The audio thread runs LoopEngine and blocks on
 * AudioTrack; a pool of two bakes blocks ahead of it; the UI thread reads the
 * engine's position and draws. Between the engine and the UI that is the whole
 * concurrency story: they share an AtomicInteger for position and an
 * AtomicReference for pending edits, and nothing else.
 *
 * One more thread sits beside them, deliberately not [bakers]: [writer], which
 * saves the session. BOUNCE has a thread too and it is not here at all — a
 * render outlives this screen, so it belongs to [LoopBounce] and the app.
 */
class LoopActivity : ComponentActivity() {

    private val bakers = Executors.newFixedThreadPool(2)

    /**
     * One thread, for writing the session back — not [bakers].
     *
     * Two reasons, and the second is the one that bit. A sidecar write is
     * nothing beside a bake, but [bakers] is torn down with `shutdownNow()`,
     * which interrupts running tasks AND drops everything still queued: clear
     * a track, press Back immediately, and the write can be thrown away behind
     * both baker threads while the shelf then reads the old file and the
     * cleared track comes back. This one is shut down gracefully instead (see
     * [onDestroy]), so a queued write finishes. Single-threaded also means two
     * edits in quick succession land in the order they were made.
     */
    private val writer = Executors.newSingleThreadExecutor()

    /**
     * The one decode cache, shared by playback and BOUNCE.
     *
     * A second `KitSampleSource` for the bounce would decode every piece on
     * the grid all over again — the same WAVs the residency already holds — so
     * the render pays a second copy of the whole session in memory to produce
     * exactly the same audio. It is safe to share: the cache is a
     * `ConcurrentHashMap` and both readers only ever read.
     */
    private var source: KitSampleSource? = null

    /**
     * Kept rather than left inside [start], because the tempo control needs
     * it: a new BPM has to be baked before it is applied, and `warm` is on
     * this.
     */
    private var residency: Residency? = null
    private var engine: LoopEngine? = null
    private var sink: AndroidAudioSink? = null
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dir = LoopWrites.dir(this)
        val rate = deviceSampleRate(this)

        // Bake at the device's rate, not the MPC's: nothing converts in the
        // callback because nothing needs to.
        val loaded = runCatching { SessionStore.load(dir) }.getOrNull()
            ?.copy(sampleRate = rate)
        // Nothing sent yet, or a sidecar that will not parse? The two look
        // identical here (both `null`) and must not read the same on screen:
        // SEND A SNIP FROM SNIPS is false advice for the second, whose next
        // send is refused precisely so the file is not overwritten.
        val emptyLine = if (loaded == null && File(dir, SessionStore.FILE_NAME).isFile) {
            Copy.LOOP_UNREADABLE
        } else {
            Copy.LOOP_EMPTY
        }

        val samples = KitSampleSource(dir)
        source = samples

        setContent {
            // remember, or every recomposition resets the session to what was
            // loaded from disk and throws away the mutes the user just tapped.
            var session by remember { mutableStateOf(loaded) }
            // What the sidecar says, as opposed to what this screen is
            // currently doing — they differ by exactly the mutes below, which
            // are deliberately never written. An edit is persisted from THIS
            // one, so clearing a track does not also quietly save the mutes
            // tapped beside it.
            var onDisk by remember { mutableStateOf(loaded) }
            var interval by remember { mutableIntStateOf(0) }
            val scope = rememberCoroutineScope()
            // A run of taps on − or + is one tempo change, not eight: each tap
            // moves the number on screen at once and restarts this, so the
            // bake-and-apply happens once, when the player stops. ORBIT's own
            // BPM control settles the same way and for the same reason.
            var tempoSettle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            // Read from the app, not held here: a render outlives this screen,
            // so opening LOOP again while one is still going has to show
            // BOUNCING… too. `LoopBounce.start` flips this before it returns,
            // so the button changes on the tap rather than a frame later.
            val bouncing by LoopBounce.busy.collectAsState()

            val s = session
            if (s == null) {
                // Never a black screen: say which door fills the grid, or say
                // the file would not read. This activity is reachable by adb
                // regardless of what the shelf shows.
                LoopEmpty(emptyLine)
            } else {
                LoopGrid(
                    session = s,
                    interval = interval,
                    onToggleTrack = { t ->
                        val next = s.copy(
                            tracks = s.tracks.mapIndexed { i, track ->
                                if (i == t) track.copy(engaged = !track.engaged) else track
                            },
                        )
                        session = next
                        engine?.apply(next)
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
                        engine?.apply(next)
                        // Written from the disk-backed copy, not from `next`:
                        // `next` carries this run's mutes, and the whole point
                        // of not saving a mute is not saving it here either.
                        val written = SessionBuilder.clear(onDisk ?: next, t)
                        onDisk = written
                        persist(written, dir)
                    },
                    onBpm = { step ->
                        val targetBpm = (s.bpm + step).coerceIn(Session.MIN_BPM, Session.MAX_BPM)
                        // The readout moves now; the audio moves when the
                        // taps stop.
                        session = s.copy(bpm = targetBpm)
                        tempoSettle?.cancel()
                        tempoSettle = scope.launch {
                            kotlinx.coroutines.delay(TEMPO_SETTLE_MS)
                            // Read live, not the snapshot the tap closed
                            // over: a mute or a clear tapped during the
                            // settle window has already updated `session`
                            // (and, for a clear, the engine and the disk),
                            // and applying the stale snapshot here would
                            // silently undo it. Only the tempo is ours to
                            // carry forward — everything else comes from
                            // whatever the grid is showing right now.
                            val toApply = (session ?: s).copy(bpm = targetBpm)
                            // Baked BEFORE it is applied, on the baker pool:
                            // a new BPM resizes the interval, and without this
                            // the engine's next buffersFor would bake six
                            // blocks on the audio thread. See Residency.warm.
                            val at = engine?.position() ?: 0
                            withContext(Dispatchers.IO) { residency?.warm(toApply, at + 1, at + 2) }
                            engine?.apply(toApply)
                            // A tempo is an edit, so it is written — from the
                            // disk-backed copy, so this does not also save the
                            // mutes tapped beside it.
                            val written = (onDisk ?: toApply).copy(bpm = targetBpm)
                            onDisk = written
                            persist(written, dir, Copy.LOOP_TEMPO_SET, Copy.LOOP_TEMPO_NOT_SAVED)
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
                            tempoSettle?.join()
                            // The session as it is on screen, read live rather
                            // than the `s` this lambda closed over: a mute
                            // tapped mid-render must not change what is being
                            // rendered half way through, but the join above
                            // can itself take up to TEMPO_SETTLE_MS, and `s`
                            // would not see an edit made during that wait.
                            // What is heard is what is bounced, as of now.
                            val refused = LoopBounce.start(this@LoopActivity, session ?: s, samples)
                            if (refused != null) {
                                Toast.makeText(applicationContext, refused, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        interval = engine?.position() ?: 0
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
        }

        if (loaded != null) start(loaded, dir)
    }

    /**
     * Write the session back, then say whether it landed.
     *
     * On [writer] rather than the main thread: it is a small JSON file, but a
     * file write on the UI thread is a file write on the UI thread. It used to
     * ride [bakers] — that pool is already off-thread and a sidecar write is
     * microseconds beside a bake — which was wrong for a reason that has
     * nothing to do with speed: see [writer]'s own note.
     *
     * The toast reports the write, not the intent — a clear that could not be
     * saved is a clear that comes back on the next launch, and the player has
     * to be told that rather than shown a grid that disagrees with the disk.
     * [failed] is the caller's own edit, worded for its own noun: a tempo
     * that did not save is not a track coming back, and a toast that
     * describes the wrong action sends the player looking for the wrong
     * thing.
     */
    private fun persist(
        session: Session,
        dir: File,
        landed: String = Copy.LOOP_TRACK_CLEARED,
        failed: String = Copy.LOOP_CLEAR_NOT_SAVED,
    ) {
        writer.execute {
            // Behind the same lock SNIPS' own → LOOP takes: that one is a
            // read-modify-write of this exact file from another screen, and it
            // must not interleave with this save. See LoopWrites.
            val saved = LoopWrites.writing { runCatching { SessionStore.save(session, dir) }.isSuccess }
            runOnUiThread {
                Toast.makeText(
                    applicationContext,
                    if (saved) landed else failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun start(session: Session, dir: File) {
        val audioSink = AndroidAudioSink(session.sampleRate)
        val res = Residency(session, source ?: KitSampleSource(dir), bakers)
        val loopEngine = LoopEngine(res, audioSink)

        sink = audioSink
        residency = res
        engine = loopEngine

        audioThread = thread(name = "snipsnap-audio", isDaemon = true) {
            // Prime here, not in onCreate. Baking interval 0 is six WAV decodes,
            // six resamples and possibly six slice-retriggers — seconds of work
            // that must never touch the main thread.
            res.buffersFor(0)
            res.prefetch(1)
            loopEngine.run()
        }
    }

    // LOOP is the app's one genuinely continuous transport, and
    // AndroidAudioSink is the one voice with a real pause/resume (see
    // AudioFocus's own KDoc). Silencing on backgrounding is the same lesson
    // PLAY/KIT/GROOVE/KEYS/SPLIT/SURFACE already apply through their own
    // ON_STOP handling — and here it also closes a gap those Compose
    // screens don't have: a permanent focus loss (AUDIOFOCUS_LOSS)
    // abandons AudioFocus's own grant without touching its registry (see
    // the class KDoc), so the sink stays paused, registered, but un-focused
    // until something re-acquires. Without this pair, that would mean
    // forever — the sink's write() blocks the audio thread indefinitely
    // with no play/pause control anywhere in LoopGrid to unstick it.
    // onStart re-acquiring on every return to the foreground is what
    // reclaims focus after exactly that kind of loss, same as the Compose
    // screens' own ON_START branch.
    override fun onStop() {
        super.onStop()
        sink?.silence()
    }

    override fun onStart() {
        super.onStart()
        sink?.let { AudioFocus.acquire(it) }
    }

    override fun onDestroy() {
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
        writer.shutdown()
        writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        // Nothing to do for a bounce in flight: it is not this activity's
        // thread, does not hold this activity, and finishes into SNIPS on its
        // own. See LoopBounce.
        super.onDestroy()
    }
}
