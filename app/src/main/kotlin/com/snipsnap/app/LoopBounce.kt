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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val inFlight = AtomicBoolean(false)

    /** True while a render is running, for any screen that wants to say so. */
    fun busy(): Boolean = inFlight.get()

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
        if (!inFlight.compareAndSet(false, true)) return Copy.LOOP_BOUNCE_ALREADY

        val app = context.applicationContext
        worker.execute {
            val line = runCatching { render(app, session, source) }
                .getOrElse { e ->
                    // Law 3: the toast says what did not happen; the
                    // exception's own detail goes to logcat.
                    Log.e(TAG, "bounce: failed", e)
                    Copy.LOOP_BOUNCE_FAILED
                }
            // Cleared before the toast, not after: the flag is what the button
            // reads, and a player watching BOUNCING… should see it finish when
            // the work finishes, not when a message queue gets round to it.
            inFlight.set(false)
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
