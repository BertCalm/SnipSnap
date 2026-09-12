package com.snipsnap.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Bouncer
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.LoopEngine
import com.snipsnap.loop.Residency
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.SnipStore
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Logcat tag for this screen; file-private, like `App.kt`'s own. */
private const val TAG = "LoopActivity"

/**
 * The loop player screen.
 *
 * Each thread has one job. The audio thread runs LoopEngine and blocks on
 * AudioTrack; a pool of two bakes blocks ahead of it; the UI thread reads the
 * engine's position and draws. Between the engine and the UI that is the whole
 * concurrency story: they share an AtomicInteger for position and an
 * AtomicReference for pending edits, and nothing else.
 *
 * Two more threads sit beside them, each deliberately not [bakers], and each
 * with its own note below: [writer] saves the session, [bouncer] renders it.
 * They are separate because they fail differently — one must survive the
 * screen closing, the other must not hold it up.
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
     * One more thread, for BOUNCE.
     *
     * Not [bakers], whose two threads exist to stay ahead of the audio thread:
     * a render is seconds of solid work and would sit on half of that pool for
     * all of them, which is a dropout while the grid is still playing. Not
     * [writer] either — a sidecar save queued behind a three-minute render
     * would miss `onDestroy`'s window entirely.
     *
     * Shut down with `shutdown()`, not `shutdownNow()`: a bounce already
     * underway when the player leaves the screen still lands in SNIPS, which
     * is what they asked for and where they will look for it.
     */
    private val bouncer = Executors.newSingleThreadExecutor()

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
    private var engine: LoopEngine? = null
    private var sink: AndroidAudioSink? = null
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dir = File(filesDir, "sessions/current")
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
            // One bounce at a time, and the button says so while it runs.
            var bouncing by remember { mutableStateOf(false) }

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
                    bouncing = bouncing,
                    onBounce = {
                        if (!bouncing) {
                            bouncing = true
                            // The session as it is on screen, captured now: a
                            // mute tapped mid-render must not change what is
                            // being rendered half way through. What is heard
                            // is what is bounced, as of the tap.
                            bounce(s, samples) { bouncing = false }
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
     */
    private fun persist(session: Session, dir: File) {
        writer.execute {
            // Behind the same lock SNIPS' own → LOOP takes: that one is a
            // read-modify-write of this exact file from another screen, and it
            // must not interleave with this save. See LoopWrites.
            val saved = LoopWrites.writing { runCatching { SessionStore.save(session, dir) }.isSuccess }
            runOnUiThread {
                Toast.makeText(
                    applicationContext,
                    if (saved) Copy.LOOP_TRACK_CLEARED else Copy.LOOP_CLEAR_NOT_SAVED,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Render what the grid is doing and land it in SNIPS.
     *
     * The same path playback takes — bake, mix, into a sink — with the sink
     * swapped, which is `Bouncer`'s own first line and the reason a bounce
     * cannot drift from what was heard. It lands through `SnipStore.import`,
     * exactly as ORBIT's own bounce does, so the result is an ordinary snip:
     * choppable, paddable, and sendable straight back to a track.
     *
     * How much of the grid: [Bouncer.intervalsWithin], the same function the
     * button's own label is printed from. A full cycle is the least common
     * multiple of the chain lengths and can run for half an hour, which no
     * snip can hold, so a long one is bounced in part and the toast says so
     * against the whole cycle's length.
     */
    private fun bounce(session: Session, samples: KitSampleSource, done: () -> Unit) {
        if (session.tracks.all { SessionBuilder.isEmpty(it) }) {
            Toast.makeText(applicationContext, Copy.LOOP_BOUNCE_EMPTY, Toast.LENGTH_SHORT).show()
            done()
            return
        }
        bouncer.execute {
            val line = runCatching {
                val intervals = Bouncer.intervalsWithin(session, SnipStore.IMPORT_MAX_SEC)
                val rendered = Bouncer.render(session, samples, intervals)
                val imported = SnipStore.import(rendered, filesDir, System.currentTimeMillis())
                // Should be impossible: the interval count was chosen against
                // that very ceiling. Logged rather than ignored because if it
                // ever fires, the toast below is stating a length that is not
                // what landed — and nothing else would say so.
                if (imported.truncated) {
                    Log.w(TAG, "bounce: import cut a render sized to fit — $intervals intervals, ${imported.seconds}s")
                }
                val bars = intervals * session.barsPerInterval
                val cycleBars = Arrangement.cycleIntervals(session) * session.barsPerInterval
                if (bars < cycleBars) Copy.loopBouncedPart(bars, cycleBars) else Copy.loopBounced(bars)
            }.getOrElse { e ->
                // Law 3: the toast says what did not happen; the exception's
                // own detail goes to logcat.
                Log.e(TAG, "bounce: failed", e)
                Copy.LOOP_BOUNCE_FAILED
            }
            runOnUiThread {
                done()
                Toast.makeText(applicationContext, line, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun start(session: Session, dir: File) {
        val audioSink = AndroidAudioSink(session.sampleRate)
        val residency = Residency(session, source ?: KitSampleSource(dir), bakers)
        val loopEngine = LoopEngine(residency, audioSink)

        sink = audioSink
        engine = loopEngine

        audioThread = thread(name = "snipsnap-audio", isDaemon = true) {
            // Prime here, not in onCreate. Baking interval 0 is six WAV decodes,
            // six resamples and possibly six slice-retriggers — seconds of work
            // that must never touch the main thread.
            residency.buffersFor(0)
            residency.prefetch(1)
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
        // Not awaited, and not interrupted: a render can take a minute, which
        // is far too long to hold up a screen closing, and abandoning it would
        // throw away work the player explicitly asked for. It finishes on its
        // own thread and the snip is in SNIPS when they get there.
        bouncer.shutdown()
        super.onDestroy()
    }
}
