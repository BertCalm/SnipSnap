package com.snipsnap.app.ui

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * [block] off the main thread (on [context], IO unless told otherwise), and
 * the caller back on the main thread before it carries on.
 *
 * In the app this is plain `withContext`: a screen's coroutines run on
 * Compose's main-thread dispatcher, which already brings the caller back.
 * The on-device tests are different. `createComposeRule()` runs every
 * `LaunchedEffect` and `rememberCoroutineScope` coroutine under a test
 * interceptor that is not a dispatcher: it resumes a coroutine on whatever
 * thread finished the work it waited for, and sends Compose's apply
 * notifications from that thread as it does. After a plain
 * `withContext(Dispatchers.IO)` that is the IO worker, so the recomposer
 * wakes there and the screen recomposes off the main thread while the main
 * thread may be tearing it down: the `ArrayIndexOutOfBoundsException` in
 * `SlotTable` on activity destroy that `AquariumPanelTest`'s held-preview
 * cases hit, and the lost SOURCE edit in `CausticSynthScreenTest`.
 *
 * Under such an interceptor the block runs inside
 * `withContext(Dispatchers.Main.immediate)`. The worker then hands the
 * result to the main dispatcher, and only the main thread resumes the
 * caller. Hopping after the block is not enough: by then the interceptor has
 * already resumed the caller, and woken the recomposer, on the worker. Two
 * earlier versions that did that each let it through, in 7 of 60 and 3 of
 * 36 repeat rounds.
 */
internal suspend fun <T> offMain(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend CoroutineScope.() -> T,
): T {
    if (coroutineContext[ContinuationInterceptor] is CoroutineDispatcher) return withContext(context, block)
    return withContext(Dispatchers.Main.immediate) { withContext(context, block) }
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
