package com.snipsnap.app.ui

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * [block] off the main thread (on [context], IO unless told otherwise), and
 * the caller back on the main thread before it carries on.
 *
 * In the app the second half costs nothing: a screen's coroutines run on
 * Compose's main-thread dispatcher, so `withContext(Dispatchers.IO)` already
 * returns there, the looper check finds the main thread, and nothing more
 * happens. The on-device tests are different. `createComposeRule()` runs
 * every `LaunchedEffect` and `rememberCoroutineScope` coroutine on an
 * unconfined test dispatcher, so the code after a plain
 * `withContext(Dispatchers.IO)` carries on on the IO worker that finished
 * the block. Its state writes wake the recomposer on that worker, and the
 * screen recomposes off the main thread while the main thread may be
 * tearing it down: the `ArrayIndexOutOfBoundsException` in `SlotTable` on
 * activity destroy that `AquariumPanelTest`'s held-preview cases hit about
 * one round in thirty. Hopping back here keeps every state write after the
 * block on the main thread, in tests as in the app.
 *
 * The hop is a posted resume, not `withContext(Dispatchers.Main)`: when
 * that block finishes before this worker has suspended, `withContext`
 * returns straight back here, still on the worker. A 60-round run with
 * `withContext` as the hop caught exactly that in 7 rounds. Here the
 * coroutine always suspends first, and only the main thread resumes it.
 */
internal suspend fun <T> offMain(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend CoroutineScope.() -> T,
): T {
    val result = withContext(context, block)
    if (Looper.myLooper() == Looper.getMainLooper()) return result
    return suspendCoroutineUninterceptedOrReturn { continuation ->
        mainThread.post { continuation.intercepted().resume(result) }
        COROUTINE_SUSPENDED
    }
}

/**
 * [offMain] for blocking work that should stop when the coroutine is
 * cancelled: [block] runs under [runInterruptible] on [context], so a
 * cancel interrupts its thread, and the caller is back on the main thread
 * after it, for the same reason as [offMain].
 */
internal suspend fun <T> offMainInterruptible(
    context: CoroutineContext = Dispatchers.IO,
    block: () -> T,
): T = offMain(context) { runInterruptible(block = block) }

private val mainThread = Handler(Looper.getMainLooper())
