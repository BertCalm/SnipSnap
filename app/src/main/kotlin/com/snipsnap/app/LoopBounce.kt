package com.snipsnap.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Bouncer
import com.snipsnap.loop.SampleSource
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.shell.Copy
import com.snipsnap.shell.SnipStore
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LoopBounce"

/**
 * The grid's bounce, owned by the app rather than by the screen that starts it.
 *
 * A render is seconds of work and is deliberately allowed to outlive LOOP: the
 * player asked for it, and it lands in SNIPS whether or not they waited. That
 * is exactly why it cannot belong to the activity. An activity-owned executor
 * meant two things, both wrong:
 *
 * - the finished render posted its toast back through the dead activity, and
 *   held it alive for the length of the render to do so;
 * - and a second `LoopActivity` opened while the first render was still going
 *   had its own executor and its own "not bouncing" flag, so BOUNCE could be
 *   pressed again and the same grid rendered twice, into two snips.
 *
 * One object, one worker, one [inFlight] flag fixes both: the state is the
 * app's, so reopening the screen sees the render that is already running, and
 * nothing here holds an activity — only the application context.
 */
object LoopBounce {

    /**
     * One thread, daemon, never shut down.
     *
     * Daemon because nothing should be kept alive by an idle bounce thread;
     * never shut down because there is no owner whose death should end it —
     * that was the bug. It costs one parked thread for the life of the
     * process.
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "snipsnap-bounce").apply { isDaemon = true }
    }

    private val inFlight = MutableStateFlow(false)

    /** True while a render is running — what LOOP's button labels itself from. */
    val busy: StateFlow<Boolean> = inFlight.asStateFlow()

    private val landings = MutableStateFlow(0)

    /**
     * How many bounces have finished and landed, ever, this process.
     *
     * A count rather than an edge of [busy], and the difference is not
     * decoration. `StateFlow` conflates: a render short enough to finish
     * between two frames publishes `false → true → false`, and a collector
     * that only ever observes the latest value sees `false` both times. An
     * effect keyed on the boolean would get no new key and never re-run —
     * exactly the stale list this signal exists to prevent, in exactly the
     * case (a one-bar grid, a fast phone) where it is most likely.
     *
     * A monotonic count cannot be conflated away: the newest value differs
     * from the last one observed no matter how many were skipped. SNIPS keys
     * its list on this, because a snip landing while that list is open has to
     * appear — the bounce's own toast says IT IS IN SNIPS NOW, which has to be
     * true of the screen the player is looking at.
     *
     * Bumped only on success, and only after the import returns: nothing
     * landed when a render fails, and the file exists by the time anyone is
     * told it does.
     */
    val landed: StateFlow<Int> = landings.asStateFlow()

    /**
     * Start a bounce of [session], reading its audio through [source].
     *
     * Returns null when the render started, or the line to say now when it did
     * not: one is already running, or the grid is empty and there is nothing to
     * render. The landing (or the failure) announces itself later, on the main
     * thread, through the application context — so it arrives whether or not
     * the screen that asked for it is still there.
     *
     * [source] is the caller's decode cache. Holding it for the render's length
     * is the point: it is the same audio the grid is already playing, and a
     * second cache would decode every piece again to produce the same samples.
     */
    fun start(context: Context, session: Session, source: SampleSource): String? {
        if (session.tracks.all { SessionBuilder.isEmpty(it) }) return Copy.LOOP_BOUNCE_EMPTY
        if (!inFlight.compareAndSet(expect = false, update = true)) return Copy.LOOP_BOUNCE_ALREADY

        val app = context.applicationContext
        worker.execute {
            val result = runCatching { render(app, session, source) }
            // Announced to the app before it is announced to the player: the
            // file is on disk by the time this bumps, so a screen reloading on
            // it finds the new snip rather than racing the write.
            result.onSuccess { landings.value += 1 }
            val line = result.getOrElse { e ->
                // Law 3: the toast says what did not happen; the exception's
                // own detail goes to logcat.
                Log.e(TAG, "bounce: failed", e)
                Copy.LOOP_BOUNCE_FAILED
            }
            // Cleared before the toast, so a player watching BOUNCING… sees it
            // finish when the work finishes rather than when a message queue
            // gets round to it.
            inFlight.value = false
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, line, Toast.LENGTH_LONG).show()
            }
        }
        return null
    }

    /**
     * The render itself: the same path playback takes — bake, mix, into a sink
     * — with only the sink swapped, which is `Bouncer`'s own promise and why a
     * bounce cannot drift from what was heard. It lands through
     * `SnipStore.import`, the door ORBIT's bounce already uses, so the result
     * is an ordinary snip.
     */
    private fun render(app: Context, session: Session, source: SampleSource): String {
        val intervals = Bouncer.intervalsWithin(session, SnipStore.IMPORT_MAX_SEC)
        val rendered = Bouncer.render(session, source, intervals)
        val imported = SnipStore.import(rendered, app.filesDir, System.currentTimeMillis())
        // Should be impossible: the interval count was chosen against that very
        // ceiling. Logged rather than ignored because if it ever fires, the
        // line below states a length that is not what landed, and nothing else
        // would say so.
        if (imported.truncated) {
            Log.w(TAG, "bounce: import cut a render sized to fit — $intervals intervals, ${imported.seconds}s")
        }
        val bars = intervals * session.barsPerInterval
        val cycleBars = Arrangement.cycleIntervals(session) * session.barsPerInterval
        return if (bars < cycleBars) Copy.loopBouncedPart(bars, cycleBars) else Copy.loopBounced(bars)
    }
}
