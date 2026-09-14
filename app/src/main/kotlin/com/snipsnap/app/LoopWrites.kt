package com.snipsnap.app

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One writer at a time for the loop session in `sessions/current`.
 *
 * Two places write it and neither can see the other's work in flight: SNIPS'
 * own → LOOP (`App.sendSnipToLoop`, one coroutine per tap) and LOOP's
 * clear-a-track (`LoopActivity.persist`, on its own writer thread). Every
 * write is read-modify-write — load the sidecar, decide, save it back — so
 * without a lock two quick taps both read the same session, both pick the same
 * empty track, and the second save silently drops the first snip while its
 * toast says it landed. `KitWrites` exists for exactly this shape of bug on
 * the kit side; this is the same guarantee for the grid.
 *
 * A plain lock rather than `KitWrites`' `kotlinx.coroutines.sync.Mutex`
 * because one of the two callers is not a coroutine: `LoopActivity` writes
 * from an executor task. Both callers are already off the main thread, so
 * blocking is what they can afford and suspending is not what one of them can
 * do.
 */
object LoopWrites {

    /**
     * Where the loop session lives, under the app's own files directory.
     *
     * Here because this object already exists to be the one thing that knows
     * about that folder. It was written out as a literal in two files —
     * `App.kt` and `LoopActivity.kt` — which is the same shape that had SNIPS
     * reading `<files>/Kits/snips` for five days: a path typed twice, with
     * nothing to notice when the copies stop agreeing. `ConventionTest`'s own
     * law over retyped paths is what keeps it single now.
     */
    const val DIR = "sessions/current"

    /** The session folder itself, for whichever screen is asking. */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    private val lock = ReentrantLock()

    /** Run [block] with the session folder to itself. */
    fun <T> writing(block: () -> T): T = lock.withLock(block)
}
