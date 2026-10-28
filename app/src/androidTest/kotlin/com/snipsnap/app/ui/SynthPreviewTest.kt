package com.snipsnap.app.ui

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SYNTH preview wrapper under Compose's interceptor, with controlled off-main work. */
@RunWith(AndroidJUnit4::class)
class SynthPreviewTest : ComposeScreenTest() {
    private var request by mutableStateOf(1)
    private var visible by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private val loadingEvents = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    private val published = CopyOnWriteArrayList<Int>()
    private val failures = CopyOnWriteArrayList<Exception>()
    private val finished = ConcurrentLinkedQueue<Int>()
    private val callbackThreads = CopyOnWriteArrayList<Boolean>()
    private val playing = AtomicInteger()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun releaseWorkers() {
        gates.forEach { it.countDown() }
    }

    private fun gate(): CountDownLatch = CountDownLatch(1).also { gates += it }

    private fun show(shimmerDelayMillis: Long = 0, renderer: (Int) -> Int) {
        setPhoneContent(settleMillis = 16) {
            if (visible) {
                val id = request
                LaunchedEffect(id) {
                    try {
                        renderSynthPreview(
                            debounceMillis = 0,
                            shimmerDelayMillis = shimmerDelayMillis,
                            onRendering = { busy ->
                                callbackThreads += Looper.myLooper() == Looper.getMainLooper()
                                loading = busy
                                loadingEvents += id to busy
                            },
                            render = { renderer(id) },
                            publish = { value ->
                                callbackThreads += Looper.myLooper() == Looper.getMainLooper()
                                published += value
                                // In SynthScreen this callback replaces the scope and swaps players.
                                playing.set(value)
                            },
                            onFailure = { failure ->
                                callbackThreads += Looper.myLooper() == Looper.getMainLooper()
                                failures += failure
                            },
                        )
                    } finally {
                        finished += id
                    }
                }
            }
        }
    }

    /** Model a renderer which notices interruption late, after a newer request is running. */
    private fun awaitIgnoringInterrupt(release: CountDownLatch, interrupted: CountDownLatch) {
        while (true) {
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "test did not release the renderer" }
                return
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
        }
    }

    private fun edit(value: Int) {
        compose.runOnIdle { request = value }
        pump(16)
    }

    @Test
    fun rapidEditsKeepTheNewLoadingStateAndOnlyTheLatestCompletionCanSwapAudio() {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstInterrupted = CountDownLatch(1)
        val secondInterrupted = CountDownLatch(1)
        val firstRelease = gate()
        val secondRelease = gate()
        show { id ->
            when (id) {
                1 -> { firstStarted.countDown(); awaitIgnoringInterrupt(firstRelease, firstInterrupted) }
                2 -> { secondStarted.countDown(); awaitIgnoringInterrupt(secondRelease, secondInterrupted) }
            }
            id
        }
        waitFor("the first renderer") { firstStarted.count == 0L && loadingEvents.contains(1 to true) }
        edit(2)
        waitFor("the replacement render and old worker interruption") {
            secondStarted.count == 0L && firstInterrupted.count == 0L && loadingEvents.contains(2 to true)
        }
        firstRelease.countDown()
        waitFor("the first request's cancelled cleanup") { finished.contains(1) }
        compose.runOnIdle {
            assertTrue("old cleanup must not hide the replacement's loading indicator", loading)
            assertFalse(loadingEvents.contains(1 to false))
            assertTrue(published.isEmpty())
        }

        // A later completion then arrives after request 3 already owns the scope/player.
        edit(3)
        waitFor("the latest preview") { published.contains(3) && finished.contains(3) && secondInterrupted.count == 0L }
        secondRelease.countDown()
        waitFor("the second request's late completion") { finished.contains(2) }
        compose.runOnIdle {
            assertEquals(listOf(3), published.toList())
            assertEquals(3, playing.get())
            assertFalse(loading)
            assertTrue(failures.isEmpty())
            assertTrue(callbackThreads.all { it })
        }
    }

    @Test
    fun anObsoleteOrdinaryFailureCannotToastOverTheNewPreview() {
        val oldStarted = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = gate()
        show { id ->
            if (id == 1) {
                oldStarted.countDown()
                awaitIgnoringInterrupt(release, interrupted)
                throw IllegalStateException("obsolete renderer failure")
            }
            id
        }
        waitFor("the old render") { oldStarted.count == 0L }
        edit(2)
        waitFor("the new preview and interruption") { published.contains(2) && interrupted.count == 0L }
        release.countDown()
        waitFor("the old ordinary failure to finish") { finished.contains(1) }
        compose.runOnIdle {
            assertEquals(listOf(2), published.toList())
            assertTrue("an obsolete error must not reach the toast callback", failures.isEmpty())
            assertEquals(2, playing.get())
            assertFalse(loading)
        }
    }

    @Test
    fun aFastCachedStyleResultNeverRevealsLoadingAndPublishesOnMain() {
        val renderWasOffMain = AtomicInteger()
        show(shimmerDelayMillis = 10_000) { id ->
            if (Looper.myLooper() != Looper.getMainLooper()) renderWasOffMain.incrementAndGet()
            id
        }
        waitFor("the immediate preview") { finished.contains(1) }
        compose.runOnIdle {
            assertEquals(listOf(1), published.toList())
            assertEquals(listOf(1 to false), loadingEvents.toList())
            assertFalse(loading)
            assertEquals(1, renderWasOffMain.get())
            assertTrue(callbackThreads.all { it })
        }
    }

    @Test
    fun leavingThePreviewInterruptsWorkWithoutPublishingOrReapplyingUiState() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = gate()
        show { id ->
            started.countDown()
            awaitIgnoringInterrupt(release, interrupted)
            id
        }
        waitFor("the visible loading state") { started.count == 0L && loadingEvents.contains(1 to true) }
        compose.runOnIdle { visible = false }
        pump(16)
        waitFor("the disposed worker interruption") { interrupted.count == 0L }
        release.countDown()
        waitFor("the disposed request cleanup") { finished.contains(1) }
        compose.runOnIdle {
            assertTrue(published.isEmpty())
            assertTrue(failures.isEmpty())
            assertEquals(listOf(1 to true), loadingEvents.toList())
        }
    }

    @Test
    fun aFailureCallbackExceptionIsNotReportedTwice() = runBlocking {
        val reported = AtomicInteger()
        val expected = IllegalStateException("failure callback failed")
        var caught: Exception? = null
        try {
            renderSynthPreview(
                debounceMillis = 0,
                shimmerDelayMillis = 10_000,
                onRendering = {},
                render = { 1 },
                publish = { throw IllegalArgumentException("publication failed") },
                onFailure = { reported.incrementAndGet(); throw expected },
            )
        } catch (failure: Exception) {
            caught = failure
        }
        assertTrue(caught is IllegalStateException)
        assertEquals(expected.message, caught?.message)
        assertEquals(1, reported.get())
    }

    @Test
    fun anInterruptedWorkerIsCleanWhenTheSameThreadRunsTheNextPreview() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val started = CountDownLatch(1)
        try {
            val cancelled = launch(Dispatchers.Main.immediate) {
                offMainInterruptible(dispatcher) {
                    started.countDown()
                    CountDownLatch(1).await()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cancelled.cancelAndJoin()
            val interrupted = withContext(Dispatchers.Main.immediate) {
                val flag = offMainInterruptible(dispatcher) { Thread.currentThread().isInterrupted }
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                flag
            }
            assertFalse("runInterruptible must clear its worker's interrupt before reuse", interrupted)
        } finally {
            dispatcher.close()
        }
    }
}
