package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceAllocatorTest {

    @Test
    fun `closed hat chokes the open hat - the kit rule that has to survive`() {
        val v = VoiceAllocator()
        val open = v.noteOn(padSlot = 4, muteGroup = 1)
        assertTrue(open.choked.isEmpty())

        val closed = v.noteOn(padSlot = 3, muteGroup = 1)
        assertEquals(listOf(open.started.id), closed.choked.map { it.id })
        assertEquals(1, v.activeCount)
        assertEquals(3, v.activeVoices.single().padSlot)
    }

    @Test
    fun `a grouped pad chokes its own previous hit, an ungrouped pad overlaps`() {
        val v = VoiceAllocator()
        val h1 = v.noteOn(padSlot = 3, muteGroup = 1)
        val h2 = v.noteOn(padSlot = 3, muteGroup = 1)
        assertEquals(listOf(h1.started.id), h2.choked.map { it.id })

        val t1 = v.noteOn(padSlot = 9, muteGroup = 0)
        val t2 = v.noteOn(padSlot = 9, muteGroup = 0)
        assertTrue(t2.choked.isEmpty())
        assertTrue(v.activeVoices.map { it.id }.containsAll(listOf(t1.started.id, t2.started.id)))
    }

    @Test
    fun `different mute groups do not interact`() {
        val v = VoiceAllocator()
        val a = v.noteOn(padSlot = 3, muteGroup = 1)
        val b = v.noteOn(padSlot = 5, muteGroup = 2)
        assertTrue(b.choked.isEmpty())
        assertEquals(2, v.activeCount)
        assertTrue(a.started.id != b.started.id)
    }

    @Test
    fun `note-off stops gate voices only`() {
        val v = VoiceAllocator()
        v.noteOn(padSlot = 1, oneShot = true)
        val gate = v.noteOn(padSlot = 2, oneShot = false)
        v.noteOn(padSlot = 2, oneShot = false)

        assertEquals(emptyList(), v.noteOff(1).map { it.id }, "one-shots ignore note-off")
        val stopped = v.noteOff(2)
        assertEquals(2, stopped.size)
        assertTrue(gate.started.id in stopped.map { it.id })
        assertEquals(1, v.activeCount)
    }

    @Test
    fun `at the cap the oldest voice is stolen`() {
        val v = VoiceAllocator(maxVoices = 3)
        val first = v.noteOn(padSlot = 1)
        v.noteOn(padSlot = 2)
        v.noteOn(padSlot = 3)
        val fourth = v.noteOn(padSlot = 4)
        assertEquals(listOf(first.started.id), fourth.stolen.map { it.id })
        assertEquals(3, v.activeCount)
    }

    @Test
    fun `choke frees the slot before stealing kicks in`() {
        val v = VoiceAllocator(maxVoices = 2)
        v.noteOn(padSlot = 1, muteGroup = 0)
        v.noteOn(padSlot = 3, muteGroup = 1)
        // The choke empties a slot; nothing needs stealing.
        val hit = v.noteOn(padSlot = 4, muteGroup = 1)
        assertEquals(1, hit.choked.size)
        assertTrue(hit.stolen.isEmpty())
    }

    @Test
    fun `voiceEnded and allOff clean up`() {
        val v = VoiceAllocator()
        val a = v.noteOn(padSlot = 1)
        v.noteOn(padSlot = 2)
        v.voiceEnded(a.started.id)
        assertEquals(1, v.activeCount)
        v.voiceEnded(9999) // unknown id is a no-op
        assertEquals(1, v.activeCount)

        v.noteOn(padSlot = 3)
        val all = v.allOff()
        assertEquals(2, all.size)
        assertEquals(0, v.activeCount)
    }

    @Test
    fun `serials are monotonic and velocity is validated`() {
        val v = VoiceAllocator()
        val a = v.noteOn(padSlot = 1, velocity = 0.5f)
        val b = v.noteOn(padSlot = 2, velocity = 1f)
        assertTrue(b.started.serial > a.started.serial)
        assertEquals(0.5f, a.started.velocity)
        assertFailsWith<IllegalArgumentException> { v.noteOn(padSlot = 1, velocity = 1.2f) }
        assertFailsWith<IllegalArgumentException> { VoiceAllocator(0) }
    }
}
