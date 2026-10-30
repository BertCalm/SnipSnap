package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import com.snipsnap.shell.Busy
import com.snipsnap.shell.JobBoard

/**
 * The jobs the app itself is carrying, for work that outlives the screen
 * that started it: a treatment the pad editor writes on `appScope` keeps
 * running after the player has switched tabs, and a bar reported through
 * `ReportBusy` would be withdrawn the moment that screen left the
 * composition. A job begun here stays on the dock until it ends.
 *
 * Begin a job with [begin], and end it in a `finally`, so a job that throws
 * or is cancelled leaves no entry behind:
 *
 * ```
 * val job = jobs.begin(busy)
 * try { ... } finally { job.end() }
 * ```
 *
 * The dock draws [current], the latest job begun that has not ended
 * ([JobBoard]'s rule). App hands it on after its own job and before a
 * screen's, so a job a screen is carrying never hides one the app owns.
 *
 * Safe to call from any thread, which a job on `appScope` needs: the board's
 * operations are synchronised, and [current] is Compose state written through
 * a snapshot of its own, so the write is never made from inside a
 * composition and the dock recomposes on the next frame.
 */
class AppJobs {

    private val lock = Any()
    private val board = JobBoard()

    private var shown by mutableStateOf<Busy?>(null)

    /** The job the dock should draw, or null when none is running. Read in composition, it recomposes with the board. */
    val current: Busy? get() = shown

    /** Starts [busy] as the latest job. The handle is how its owner moves it on and ends it. */
    fun begin(busy: Busy): JobHandle = JobHandle(this, change { board.begin(busy) })

    internal fun update(id: Long, busy: Busy) {
        change { board.update(id, busy) }
    }

    internal fun end(id: Long) {
        change { board.end(id) }
    }

    /**
     * Runs [edit] on the board and publishes what it left showing, both under
     * the lock: two threads' changes then reach the state in the order they
     * reached the board, and the last write is always the board as it now
     * stands.
     */
    private fun <T> change(edit: () -> T): T = synchronized(lock) {
        val result = edit()
        Snapshot.withMutableSnapshot { shown = board.current }
        result
    }
}

/**
 * One job on the [AppJobs] board, held by whoever began it. [update] moves it
 * on (the count, the label) without changing its place; [end] takes it off.
 */
class JobHandle internal constructor(private val jobs: AppJobs, private val id: Long) {

    /** Says [busy] for this job from now on. Nothing happens once the job has ended. */
    fun update(busy: Busy) = jobs.update(id, busy)

    /** Takes this job off the board. Safe to call twice, and from any thread: the second call does nothing. */
    fun end() = jobs.end(id)
}

/**
 * Where work that outlives its screen finds the board. The app provides it at
 * its root; null, the default, is a screen shown on its own, in a test or a
 * preview, which has no dock to tell.
 */
val LocalJobs = staticCompositionLocalOf<AppJobs?> { null }
