package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MidiKeysTest {

    /** Every call the instrument saw, in order, as words. */
    private class Log : MidiKeys.Voice {
        val calls = mutableListOf<String>()
        override fun noteOn(note: Int, velocity: Float) { calls += "on $note ${"%.3f".format(java.util.Locale.ROOT, velocity)}" }
        override fun noteOff(note: Int) { calls += "off $note" }
        override fun allOff() { calls += "all off" }
    }

    private fun pedal(down: Boolean) = Event.Control(0, MidiWire.CC_SUSTAIN, if (down) 127 else 0)

    @Test
    fun `a key down is a note on at the keyboard's own velocity and a key up is a note off`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.NoteOn(0, 60, 0.5f))
        keys.handle(Event.NoteOff(0, 60))
        assertEquals(listOf("on 60 0.500", "off 60"), log.calls)
    }

    @Test
    fun `under the pedal a key up holds, and the pedal rising lets every held note go`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(pedal(true))
        assertTrue(keys.sustaining)
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOn(0, 64, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.NoteOff(0, 64))
        assertEquals(listOf("on 60 1.000", "on 64 1.000"), log.calls, "nothing let go while the pedal is down")
        assertEquals(setOf(60, 64), keys.heldNotes)
        keys.handle(pedal(false))
        assertFalse(keys.sustaining)
        assertEquals(listOf("on 60 1.000", "on 64 1.000", "off 60", "off 64"), log.calls)
        assertTrue(keys.heldNotes.isEmpty())
    }

    @Test
    fun `the switch threshold is the spec's 64, and a repeated pedal value changes nothing`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 63))
        assertFalse(keys.sustaining)
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 64))
        assertTrue(keys.sustaining)
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 100))
        assertEquals(setOf(60), keys.heldNotes, "a second 'down' does not release what the first is holding")
    }

    @Test
    fun `a held note struck again is live again, and its next key up under the pedal holds it afresh`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(pedal(true))
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.NoteOn(0, 60, 0.5f))
        assertTrue(keys.heldNotes.isEmpty(), "restruck: no longer waiting on the pedal")
        keys.handle(pedal(false))
        assertEquals(listOf("on 60 1.000", "on 60 0.500"), log.calls, "the pedal rising has nothing held to let go")
        keys.handle(Event.NoteOff(0, 60))
        assertEquals("off 60", log.calls.last())
    }

    @Test
    fun `the two panics silence everything and forget the pedal`() {
        for (cc in listOf(MidiWire.CC_ALL_NOTES_OFF, MidiWire.CC_ALL_SOUND_OFF)) {
            val log = Log()
            val keys = MidiKeys(log)
            keys.handle(pedal(true))
            keys.handle(Event.NoteOn(0, 60, 1f))
            keys.handle(Event.NoteOff(0, 60))
            keys.handle(Event.Control(0, cc, 0))
            assertEquals("all off", log.calls.last(), "CC $cc")
            assertFalse(keys.sustaining, "CC $cc clears the pedal")
            assertTrue(keys.heldNotes.isEmpty(), "CC $cc clears the held set")
            // The pedal is now up, so a later key up lets go at once.
            keys.handle(Event.NoteOn(0, 62, 1f))
            keys.handle(Event.NoteOff(0, 62))
            assertEquals("off 62", log.calls.last())
        }
    }

    @Test
    fun `a controller nothing here plays is ignored, and every channel is heard`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.Control(0, 1, 127))
        keys.handle(Event.Control(0, 7, 0))
        keys.handle(Event.NoteOn(9, 36, 1f))
        keys.handle(Event.NoteOn(15, 48, 1f))
        assertEquals(listOf("on 36 1.000", "on 48 1.000"), log.calls)
    }
}
