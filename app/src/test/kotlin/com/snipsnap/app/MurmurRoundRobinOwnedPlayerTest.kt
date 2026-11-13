package com.snipsnap.app

import java.util.concurrent.CancellationException
import kotlin.test.*

class MurmurRoundRobinOwnedPlayerTest {
    private class Retry : MurmurRoundRobinOwnedPlayer.Retry {
        var task: (() -> Unit)? = null; var refuse = false; var closed = false
        override fun post(task: () -> Unit): Boolean { if (refuse) return false; this.task = task; return true }
        override fun cancel() { task = null }
        override fun close() { closed = true; cancel() }
        fun fire() { val run = task; task = null; run?.invoke() }
    }
    private class Port : MurmurRoundRobinAdmission.Port {
        val chords = mutableListOf<List<MurmurRoundRobinAdmission.Command>>()
        val stops = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        var acceptStops = true
        override fun admit(chord: List<MurmurRoundRobinAdmission.Command>): Boolean { chords += chord.toList(); return true }
        override fun stopVoice(voiceId: Int, fadeMs: Float): Boolean { stops += voiceId; return acceptStops }
        override fun drainEnded(): IntArray = ends.toIntArray().also { ends.clear() }
    }
    private class Backend : MurmurRoundRobinOwnedPlayer.Backend {
        var next = 1L; var fail = ""; var restart = false; var afterStart: () -> Unit = {}
        val ports = linkedMapOf<Long, Port>(); val adds = linkedMapOf<Long, Int>(); val destroyed = mutableListOf<Long>()
        val starts = mutableListOf<Long>(); val commits = mutableListOf<Long>()
        override fun prepare(rate: Int): Long {
            if (fail == "prepare") return 0
            val id = next++; ports[id] = Port(); adds[id] = 0; return id
        }
        override fun add(handle: Long, pcm: FloatArray): Int {
            check(handle !in destroyed); assertTrue(pcm.all { it == .125f })
            if (fail == "add") return -1
            val ordinal = adds.getValue(handle); adds[handle] = ordinal + 1
            return if (fail == "duplicate") 0 else 100 + ordinal * 3
        }
        override fun commit(handle: Long): Boolean { commits += handle; return fail != "commit" }
        override fun start(handle: Long): Boolean {
            starts += handle
            if (restart) { ports.getValue(handle).ends += ports.getValue(handle).chords.flatten().map { it.voiceId }; restart = false }
            afterStart(); return fail != "start"
        }
        override fun needsRestart(handle: Long) = restart
        override fun port(handle: Long): MurmurRoundRobinAdmission.Port = ports.getValue(handle)
        override fun destroy(handle: Long) { assertTrue(handle !in destroyed); destroyed += handle }
    }
    private class Source(val id: String = "a", val afterCells: () -> Unit = {}) : MurmurRoundRobinOwnedPlayer.Source {
        val cells = (0..3).flatMap { take -> (0..6).flatMap { zone -> (0..2).map { touch ->
            val root = 36 + zone * 3
            MurmurRoundRobinOwnedPlayer.Cell(take * 21 + zone * 3 + touch, root,
                if (zone == 0) 36 else root - 1, if (zone == 6) 54 else root + 1, take, touch, 4)
        } } }
        val plan = MurmurRoundRobinOwnedPlayer.Plan("RR", id, 84L * 4 * 4, 80f, cells)
        var resident = -1L
        override fun transfer(resident: Long, cancelled: () -> Boolean, begin: (MurmurRoundRobinOwnedPlayer.Plan) -> Unit,
                              cell: (MurmurRoundRobinOwnedPlayer.Cell, FloatArray) -> Unit): MurmurRoundRobinOwnedPlayer.Plan {
            this.resident = resident
            cancelled(); begin(plan)
            for (c in cells) { cancelled(); cell(c, FloatArray(c.frames) { .125f }) }
            afterCells(); cancelled(); return plan
        }
    }
    @Test fun mapsEveryCellToActualNativeIndexAndDelegatesWholeChords() {
        val backend = Backend(); val retry = Retry(); val source = Source()
        MurmurRoundRobinOwnedPlayer(44100, backend, retry).use { player ->
            player.open(source); assertEquals(84, backend.adds[1L]); assertEquals(listOf(1L), backend.commits)
            val first = player.noteOnChord(listOf(36, 39), .2f)
            assertEquals(listOf(100, 109), first.map { it.command.sample })
            assertEquals(listOf(0, 0), first.map { it.take })
            assertEquals(166, player.noteOnChord(listOf(36), .65f).single().command.sample)
            assertTrue(player.noteOnChord(listOf(36, 99), 1f).isEmpty())
            assertEquals(2, backend.ports.getValue(1).chords.size)
        }
        assertEquals(listOf(1L), backend.destroyed); assertTrue(retry.closed)
    }
    @Test fun freshBankHandlesCannotDrainPreviousBanksEndedIds() {
        val b = Backend(); val r = Retry(); val p = MurmurRoundRobinOwnedPlayer(44100, b, r)
        p.open(Source()); assertEquals(1, p.noteOnChord(listOf(36), .2f).single().command.voiceId)
        b.ports.getValue(1).ends += 1
        val next = Source("b"); p.open(next)
        assertEquals(1344L, next.resident); assertEquals(listOf(1L), b.destroyed)
        assertEquals(1, p.noteOnChord(listOf(36), .2f).single().command.voiceId)
        p.poll(); assertEquals(1, p.activeCount); assertEquals("b", p.identity); p.close()
    }
    @Test fun eachLoadingFailurePreservesOldBankAndDestroysOnlyStagedOwner() {
        for (failure in listOf("prepare", "add", "commit", "start", "duplicate")) {
            val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source("old"))
            b.fail = failure
            assertFails { p.open(Source("new")) }
            assertEquals("old", p.identity); assertTrue(1L !in b.destroyed)
            if (failure != "prepare") assertEquals(listOf(2L), b.destroyed)
            b.fail = ""; assertEquals(1, p.noteOnChord(listOf(36), .2f).size); p.close()
        }
    }
    @Test fun cancellationAfterStartAbortsNewHandleAndKeepsOldPlayer() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source("old"))
        var cancelled = false; b.afterStart = { cancelled = true }
        assertFailsWith<CancellationException> { p.open(Source("new")) { cancelled } }
        assertEquals("old", p.identity); assertEquals(listOf(2L), b.destroyed); b.afterStart = {}; p.close()
    }
    @Test fun closeReenteredFromCancellationCannotResurrectStagedBank() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source("old"))
        assertFailsWith<CancellationException> {
            p.open(Source("new")) { if (b.adds[2L] == 1) p.close(); false }
        }
        assertNull(p.identity); assertEquals(setOf(1L, 2L), b.destroyed.toSet()); p.close()
        assertFailsWith<IllegalStateException> { p.open(Source()) }
    }
    @Test fun reentrantOpenRefusedAndTransferFailureRetainsOldPlayer() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source("old"))
        assertFailsWith<IllegalStateException> { p.open(Source("new") { p.open(Source("nested")) }) }
        assertEquals("old", p.identity); assertEquals(listOf(2L), b.destroyed); p.close()
    }
    @Test fun stopDebtRetriesUntilAcceptedAndQuotaWaitsForEndedIds() {
        val b = Backend(); val r = Retry(); val p = MurmurRoundRobinOwnedPlayer(44100, b, r); p.open(Source())
        p.noteOnChord(listOf(36, 39), .2f); val port = b.ports.getValue(1); port.acceptStops = false
        p.allOff(); assertTrue(p.hasPendingStops); assertNotNull(r.task); assertEquals(2, p.activeCount)
        r.fire(); assertTrue(p.hasPendingStops); port.acceptStops = true; r.fire()
        assertFalse(p.hasPendingStops); assertNull(r.task); assertEquals(2, p.activeCount)
        port.ends += listOf(1, 2); p.poll(); assertEquals(0, p.activeCount); p.close()
    }
    @Test fun staleRetryCannotTouchNewHandleOrClosedPlayer() {
        val b = Backend(); val r = Retry(); val p = MurmurRoundRobinOwnedPlayer(44100, b, r); p.open(Source())
        p.noteOnChord(listOf(36), .2f); b.ports.getValue(1).acceptStops = false; p.allOff()
        val stale = assertNotNull(r.task); p.open(Source("new")); stale()
        assertTrue(b.ports.getValue(2).stops.isEmpty()); p.close(); stale(); assertEquals(listOf(1L, 2L), b.destroyed)
    }
    @Test fun rejectedRetryPostKeepsDebtForNextPoll() {
        val b = Backend(); val r = Retry(); val p = MurmurRoundRobinOwnedPlayer(44100, b, r); p.open(Source())
        p.noteOnChord(listOf(36), .2f); b.ports.getValue(1).acceptStops = false; r.refuse = true; p.allOff()
        assertTrue(p.hasPendingStops); assertNull(r.task); r.refuse = false; p.poll(); assertNotNull(r.task); p.close()
    }
    @Test fun routeRestartKeepsMonotonicIdsAndReapsOldStreamEnds() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source())
        assertEquals(1, p.noteOnChord(listOf(36), .2f).single().command.voiceId); b.restart = true
        assertEquals(2, p.noteOnChord(listOf(36), .2f).single().command.voiceId)
        assertEquals(1, p.activeCount); assertEquals(listOf(1L, 1L), b.starts); p.close()
    }
    @Test fun failedRouteRestartRetiresOwnerAndCanBeReloadedFresh() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source())
        b.restart = true; b.fail = "start"; assertFailsWith<IllegalStateException> { p.poll() }
        assertNull(p.identity); assertEquals(listOf(1L), b.destroyed)
        b.fail = ""; p.open(Source("fresh")); assertEquals(1, p.noteOnChord(listOf(36), .2f).single().command.voiceId); p.close()
    }
    @Test fun closeIsIdempotentAndDoesNotRecreateAPlayer() {
        val b = Backend(); val p = MurmurRoundRobinOwnedPlayer(44100, b, Retry()); p.open(Source()); p.close(); p.close()
        assertEquals(listOf(1L), b.destroyed); assertTrue(p.noteOnChord(listOf(36), .2f).isEmpty()); p.poll()
    }
}
