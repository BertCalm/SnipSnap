package com.snipsnap.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MurmurRoundRobinAdmissionTest {
    private class FakePort : MurmurRoundRobinAdmission.Port {
        var accept = true
        var stopAccept: (Int) -> Boolean = { true }
        var ends = intArrayOf()
        val attempted = mutableListOf<List<MurmurRoundRobinAdmission.Command>>()
        val accepted = mutableListOf<List<MurmurRoundRobinAdmission.Command>>()
        val stops = mutableListOf<Pair<Int, Float>>()
        override fun admit(chord: List<MurmurRoundRobinAdmission.Command>): Boolean {
            attempted += chord.toList()
            if (accept) accepted += chord.toList()
            return accept
        }
        override fun stopVoice(voiceId: Int, fadeMs: Float): Boolean {
            stops += voiceId to fadeMs
            return stopAccept(voiceId)
        }
        override fun drainEnded(): IntArray = ends.also { ends = intArrayOf() }
    }
    private fun zone(low: Int = 36, high: Int = 47, root: Int = 36, offset: Int = 0) =
        MurmurRoundRobinAdmission.Zone(low, high, root, List(4) { take -> List(3) { touch ->
            MurmurRoundRobinAdmission.Cell(offset + take * 3 + touch, take.toLong(), 100L + take, -1)
        } })
    private fun controller(port: FakePort, zones: List<MurmurRoundRobinAdmission.Zone> = listOf(zone())) =
        MurmurRoundRobinAdmission(zones, 125f, port)

    @Test fun chordResolvesEachNoteAndCommitsSharedZoneCursorInCallerOrder() {
        val p = FakePort(); val c = controller(p)
        val attacks = c.noteOnChord(listOf(36, 40, 43), .65f)
        assertEquals(listOf(0, 1, 2), attacks.map { it.take })
        assertEquals(listOf(1, 4, 7), attacks.map { it.command.sample })
        assertEquals(listOf(0L, 1L, 2L), attacks.map { it.command.start })
        assertEquals(listOf(100L, 101L, 102L), attacks.map { it.command.end })
        assertEquals(listOf(1, 2, 3), attacks.map { it.command.voiceId })
        assertTrue(attacks[1].command.pitch > attacks[0].command.pitch)
        assertTrue(attacks[2].command.pitch > attacks[1].command.pitch)
        assertEquals(.65f * .35f, attacks[0].command.gain)
        assertEquals(1, p.accepted.size)
        assertEquals(3, c.noteOnChord(listOf(38), 1f).single().take)
    }
    @Test fun differentZonesAdvanceIndependentlyAndNativeTouchesShareCursor() {
        val p = FakePort(); val c = controller(p, listOf(zone(), zone(48, 59, 48, 12)))
        val first = c.noteOnChord(listOf(36, 48), .35f)
        assertEquals(listOf(0, 0), first.map { it.take }); assertEquals(listOf(1, 13), first.map { it.command.sample })
        val next = c.noteOnChord(listOf(36), .1f).single()
        assertEquals(1, next.take); assertEquals(0, next.touch); assertEquals(3, next.command.sample)
        assertEquals(1, c.noteOnChord(listOf(49), 1f).single().take)
    }
    @Test fun queueRefusalAndExceptionDoNotAdvanceCursorsOrIds() {
        val p = FakePort(); val c = controller(p); p.accept = false
        assertTrue(c.noteOnChord(listOf(36, 40), .7f).isEmpty()); assertEquals(0, c.activeCount)
        p.accept = true
        val retry = c.noteOnChord(listOf(36, 40), .7f)
        assertEquals(listOf(0, 1), retry.map { it.take }); assertEquals(listOf(1, 2), retry.map { it.command.voiceId })
        assertEquals(p.attempted[0], p.attempted[1])
        val throwing = object : MurmurRoundRobinAdmission.Port {
            var fail = true
            override fun admit(chord: List<MurmurRoundRobinAdmission.Command>): Boolean { if (fail) error("before publication"); return true }
            override fun stopVoice(voiceId: Int, fadeMs: Float) = true
            override fun drainEnded() = intArrayOf()
        }
        val other = MurmurRoundRobinAdmission(listOf(zone()), 100f, throwing)
        assertFailsWith<IllegalStateException> { other.noteOnChord(listOf(36), .5f) }
        throwing.fail = false
        assertEquals(1, other.noteOnChord(listOf(36), .5f).single().command.voiceId)
    }
    @Test fun invalidWholeChordNeverCallsAdmissionAndDoesNotConsumeATake() {
        val p = FakePort(); val c = controller(p)
        for (notes in listOf(emptyList(), listOf(36, 36), listOf(36, 128), listOf(35, 36), (36..44).toList()))
            assertTrue(c.noteOnChord(notes, .5f).isEmpty())
        for (v in listOf(0f, -1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) assertTrue(c.noteOnChord(listOf(36), v).isEmpty())
        assertTrue(p.attempted.isEmpty()); assertEquals(0, c.noteOnChord(listOf(36), .5f).single().take)
    }
    @Test fun globalEightIncludesEveryTakeQueuedAttackAndReleasedTail() {
        val p = FakePort(); val c = controller(p)
        repeat(8) { assertEquals(it % 4, c.noteOnChord(listOf(36), .5f).single().take) }
        c.noteOff(36)
        assertEquals(8, c.activeCount); assertEquals(8, p.stops.size)
        assertTrue(c.noteOnChord(listOf(37), .5f).isEmpty())
        p.ends = intArrayOf(1, 4); c.poll(); assertEquals(6, c.activeCount)
        val new = c.noteOnChord(listOf(37, 38), .5f)
        assertEquals(listOf(0, 1), new.map { it.take }); assertEquals(listOf(9, 10), new.map { it.command.voiceId })
    }
    @Test fun refusedStopRetriesOnlyCapturedOldIdsAndNeverReleasesALaterAttack() {
        val p = FakePort(); val c = controller(p)
        c.noteOnChord(listOf(36), .5f); p.stopAccept = { false }; c.noteOff(36)
        assertTrue(c.hasPendingStops); assertEquals(1, c.activeCount)
        c.noteOnChord(listOf(36), .5f)
        p.stopAccept = { true }; p.stops.clear(); c.poll()
        assertEquals(listOf(1 to 125f), p.stops); assertFalse(c.hasPendingStops); assertEquals(2, c.activeCount)
        p.ends = intArrayOf(1); c.poll(); assertEquals(1, c.activeCount)
    }
    @Test fun partialStopsRetryOnlyRefusedIdsAndEndedDebtIsCancelled() {
        val p = FakePort(); val c = controller(p)
        c.noteOnChord(listOf(36, 40, 43), .5f); p.stopAccept = { it == 2 }; c.allOff()
        assertTrue(c.hasPendingStops); p.stops.clear(); p.ends = intArrayOf(1, 2); c.poll()
        assertEquals(listOf(3 to 125f), p.stops); assertEquals(1, c.activeCount)
        p.stopAccept = { true }; p.stops.clear(); c.poll(); assertFalse(c.hasPendingStops)
        assertEquals(listOf(3 to 125f), p.stops); assertEquals(1, c.activeCount)
    }
    @Test fun resetAndAllOffRestartSequenceWithoutCuttingOrReusingIds() {
        val p = FakePort(); val c = controller(p)
        c.noteOnChord(listOf(36), .5f); c.resetRoundRobin()
        assertEquals(1, c.activeCount); assertTrue(p.stops.isEmpty())
        val second = c.noteOnChord(listOf(36), .5f).single(); assertEquals(0, second.take); assertEquals(2, second.command.voiceId)
        c.allOff(); assertEquals(2, c.activeCount); p.ends = intArrayOf(1, 2); c.poll()
        val third = c.noteOnChord(listOf(36), .5f).single(); assertEquals(0, third.take); assertEquals(3, third.command.voiceId)
        p.ends = intArrayOf(1, 1, 999); c.poll(); assertEquals(1, c.activeCount)
    }
    @Test fun descriptorCollectionsAreCopiedAndMalformedLayoutsRefused() {
        val p = FakePort(); val cells = MutableList(3) { MurmurRoundRobinAdmission.Cell(it, 0, 100) }
        val takes = MutableList<List<MurmurRoundRobinAdmission.Cell>>(4) { cells }
        val z = zone().copy(takes = takes); val c = controller(p, listOf(z))
        cells.clear(); takes.clear(); assertEquals(1, c.noteOnChord(listOf(36), .5f).size)
        assertFailsWith<IllegalArgumentException> { controller(p, listOf(z)) }
        assertFailsWith<IllegalArgumentException> { controller(p, listOf(zone(), zone(40, 50, 40))) }
        assertFailsWith<IllegalArgumentException> { controller(p, listOf(zone().copy(root = 99))) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinAdmission(listOf(zone()), Float.NaN, p) }
    }
    @Test fun freshControllerUsesFreshPortAndOldHandleEndingsCannotCrossTheBoundary() {
        val oldPort = FakePort(); val old = controller(oldPort)
        old.noteOnChord(listOf(36), .5f); old.allOff()
        // A reload owner must destroy that native handle, not attach a new controller to it.
        val freshPort = FakePort(); val fresh = controller(freshPort)
        fresh.noteOnChord(listOf(36), .5f); oldPort.ends = intArrayOf(1)
        old.poll(); fresh.poll(); assertEquals(0, old.activeCount); assertEquals(1, fresh.activeCount)
    }
}
