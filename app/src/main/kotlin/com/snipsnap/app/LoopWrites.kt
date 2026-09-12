package com.snipsnap.app

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

    private val lock = ReentrantLock()

    /** Run [block] with the session folder to itself. */
    fun <T> writing(block: () -> T): T = lock.withLock(block)
}
