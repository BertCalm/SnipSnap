package com.snipsnap.app

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.Test

class MurmurRoundRobinScreenSessionTest {
    private class Queue : MurmurRoundRobinScreenSession.Worker {
        val tasks = ArrayDeque<() -> Unit>()
        var shutdown = false
        override fun execute(task: () -> Unit) { check(!shutdown); tasks.add(task) }
        override fun shutdown() { shutdown = true }
        fun one() = tasks.removeFirst().invoke()
        fun drain() { while (tasks.isNotEmpty()) one() }
    }
    private class Port : MurmurRoundRobinScreenSession.Port {
        var loaded = false
        var count = 0
        var debt = false
        var accepted = true
        var opened = 0
        var polls = 0
        var closes = 0
        var panic = 0
        var onOpen: ((() -> Boolean) -> Unit)? = null
        var onReady: (() -> Unit)? = null
        var onPoll: (() -> Unit)? = null
        val chords = mutableListOf<Pair<List<Int>, Float>>()
        val releases = mutableListOf<Int>()
        override val ready: Boolean get() { onReady?.invoke(); return loaded }
        override val activeCount get() = count
        override val hasPendingStops get() = debt
        override fun open(cancelled: () -> Boolean) {
            opened++
            onOpen?.invoke(cancelled)
            if (cancelled()) throw CancellationException()
            loaded = true
        }
        override fun chord(notes: List<Int>, velocity: Float): Boolean {
            chords += notes.toList() to velocity
            if (accepted) count += notes.size
            return accepted
        }
        override fun release(note: Int) { releases += note; debt = true }
        override fun allOff() { panic++; if (count > 0) debt = true }
        override fun poll() { polls++; onPoll?.invoke() }
        override fun close() { closes++; loaded = false; count = 0; debt = false }
    }
    private class Fixture {
        val worker = Queue()
        val port = Port()
        val session = MurmurRoundRobinScreenSession(36..84, port, worker)
        fun loaded() { assertTrue(session.load()); worker.drain(); assertTrue(session.state.canPlay) }
    }

    @Test fun loadingAndBusyStatePrecedeWorkerAndControlsStayOffPort() {
        val f = Fixture()
        assertTrue(f.session.load())
        assertTrue(f.session.state.loading)
        assertFalse(f.session.load())
        assertFalse(f.session.press(1, listOf(60), .5f))
        f.session.poll()
        assertEquals(0, f.port.opened)
        assertEquals(1, f.worker.tasks.size)
        f.worker.drain()
        assertTrue(f.session.state.canPlay)
        assertFalse(f.session.state.loading)
    }

    @Test fun wholeChordUsesFinalPitchesAndDeduplicatesBeforeOneAdmission() {
        val f = Fixture(); f.loaded()
        assertTrue(f.session.press(4, listOf(67, 60, 64, 60), .72f))
        assertTrue(f.port.chords.isEmpty())
        f.worker.drain()
        assertEquals(listOf(listOf(67, 60, 64) to .72f), f.port.chords)
        assertEquals(3, f.session.state.activeCount)
    }

    @Test fun invalidToneRefusesWholeChordAndNeverQueuesPartialMusic() {
        val f = Fixture(); f.loaded()
        for (notes in listOf(emptyList(), listOf(60, 85), (36..44).toList())) {
            assertFalse(f.session.press(1, notes, .5f))
        }
        for (v in listOf(Float.NaN, 0f, -1f, 1.1f)) assertFalse(f.session.press(1, listOf(60), v))
        f.worker.drain(); assertTrue(f.port.chords.isEmpty())
    }

    @Test fun chordOwnsCapturedNotesAndSharedPitchesWaitForLastRelease() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60, 64, 67), .5f)
        f.session.press(2, listOf(64, 69), .9f)
        f.worker.drain()
        f.session.release(1); f.worker.drain()
        assertEquals(listOf(60, 67), f.port.releases)
        f.session.release(2); f.worker.drain()
        assertEquals(listOf(60, 67, 64, 69), f.port.releases)
        f.session.release(2); f.worker.drain()
        assertEquals(4, f.port.releases.size)
    }

    @Test fun duplicateGestureAndRefusedChordOwnNoExtraRelease() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f); f.session.press(1, listOf(62), .5f)
        f.worker.drain(); assertEquals(1, f.port.chords.size)
        f.port.accepted = false
        f.session.press(2, listOf(64), .5f); f.worker.drain()
        f.session.release(2); f.worker.drain(); assertTrue(f.port.releases.isEmpty())
        f.session.release(1); f.worker.drain(); assertEquals(listOf(60), f.port.releases)
    }

    @Test fun pollingContinuesAfterFingerLiftUntilActualTailAndDebtDrain() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f); f.worker.drain()
        f.session.release(1); f.worker.drain()
        assertTrue(f.session.state.needsPoll)
        repeat(20) { f.session.poll() }
        assertEquals(1, f.worker.tasks.size)
        f.worker.drain(); assertTrue(f.session.state.needsPoll)
        f.port.onPoll = { f.port.count = 0; f.port.debt = false }
        f.session.poll(); f.worker.drain()
        assertFalse(f.session.state.needsPoll); assertEquals(2, f.port.polls)
    }

    @Test fun silenceInvalidatesQueuedAttackBeforeWorkerCanSoundIt() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f)
        f.session.silence(); f.worker.drain()
        assertTrue(f.port.chords.isEmpty())
        assertEquals(2, f.port.panic) // initial load and explicit silence
    }

    @Test fun backgroundCancelsQueuedLoadingAndPreventsQueuedAttacks() {
        val f = Fixture()
        f.session.load(); f.session.foreground(false); f.worker.drain()
        assertEquals(0, f.port.opened)
        assertFalse(f.session.state.loading)
        assertFalse(f.session.press(1, listOf(60), .5f))
        f.session.foreground(true); f.loaded()
        f.session.press(1, listOf(60), .5f); f.session.foreground(false); f.worker.drain()
        assertTrue(f.port.chords.isEmpty())
    }

    @Test fun failedReloadLeavesOldOwnerPlayableAndClearsCapturedGestures() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f); f.worker.drain()
        f.port.onOpen = { throw IllegalArgumentException("bad bank") }
        assertTrue(f.session.load()); assertFalse(f.session.state.canPlay); f.worker.drain()
        assertTrue(f.session.state.canPlay); assertNotNull(f.session.state.error)
        f.session.release(1); f.worker.drain(); assertTrue(f.port.releases.isEmpty())
        f.session.press(1, listOf(62), .5f); f.worker.drain()
        assertEquals(listOf(62), f.port.chords.last().first)
    }

    @Test fun cancelBeforeReloadRunsPreservesLoadedOwnerWithoutOpeningAgain() {
        val f = Fixture(); f.loaded()
        f.session.load(); f.session.cancelLoading(); f.worker.drain()
        assertEquals(1, f.port.opened); assertTrue(f.session.state.canPlay)
        assertNull(f.session.state.error)
    }

    @Test fun closeInvalidatesQueuedNotesAndDisposesExactlyOnceOnWorker() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f)
        f.session.close(); f.session.close()
        assertEquals(0, f.port.closes); assertTrue(f.session.state.closed)
        assertFalse(f.session.load()); assertFalse(f.session.press(2, listOf(64), .5f))
        f.worker.drain()
        assertEquals(1, f.port.closes); assertTrue(f.port.chords.isEmpty()); assertTrue(f.worker.shutdown)
    }

    @Test fun closeDuringBlockingLoadCancelsImmediatelyAndLateSuccessCannotResurrect() {
        val f = Fixture(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        f.port.onOpen = { cancel -> entered.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS)); assertTrue(cancel()) }
        f.session.load()
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread { try { f.worker.one() } catch (error: Throwable) { failure.set(error) } }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            f.session.close()
            assertTrue(f.session.state.closed)
        } finally { finish.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive); assertNull(failure.get()); f.worker.drain()
        assertFalse(f.session.state.loaded); assertEquals(1, f.port.closes)
    }

    @Test fun blockedNativeSnapshotCannotBlockUiCloseOrCancellation() {
        val f = Fixture(); val entered = CountDownLatch(1); val finish = CountDownLatch(1); val closed = CountDownLatch(1)
        f.port.onReady = { entered.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS)) }
        f.session.load()
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread { try { f.worker.one() } catch (error: Throwable) { failure.set(error) } }.apply { start() }
        var ui: Thread? = null
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            ui = Thread { f.session.cancelLoading(); f.session.close(); closed.countDown() }.apply { start() }
            assertTrue(closed.await(1, TimeUnit.SECONDS), "UI waited behind native snapshot")
        } finally { finish.countDown(); worker.join(3000); ui?.join(3000) }
        assertFalse(worker.isAlive); assertNull(failure.get()); f.port.onReady = null; f.worker.drain()
        assertTrue(f.session.state.closed); assertFalse(f.session.state.loaded)
    }

    @Test fun routeFailureRetiresPlaybackStateAndRequiresExplicitReload() {
        val f = Fixture(); f.loaded()
        f.session.press(1, listOf(60), .5f); f.worker.drain()
        f.port.onPoll = { f.port.loaded = false; f.port.count = 0; throw IllegalStateException("route failed") }
        f.session.poll(); f.worker.drain()
        assertFalse(f.session.state.canPlay); assertNotNull(f.session.state.error)
        assertFalse(f.session.press(2, listOf(64), .5f))
        f.port.onPoll = null; f.session.load(); f.worker.drain(); assertTrue(f.session.state.canPlay)
    }
}
