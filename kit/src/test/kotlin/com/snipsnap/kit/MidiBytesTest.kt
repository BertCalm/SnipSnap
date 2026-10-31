package com.snipsnap.kit

import com.snipsnap.kit.MidiWire.Event
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class MidiBytesTest {

    private fun parse(bytes: ByteArray) = MidiWire().feed(bytes)

    @Test
    fun `a note on round-trips through the parser`() =
        assertEquals(listOf(Event.NoteOn(9, 36, 100 / 127f)), parse(MidiBytes.noteOn(9, 36, 100)))

    @Test
    fun `a note off round-trips`() =
        assertEquals(listOf(Event.NoteOff(0, 60)), parse(MidiBytes.noteOff(0, 60)))

    @Test
    fun `a controller round-trips`() =
        assertEquals(listOf(Event.Control(3, 64, 127)), parse(MidiBytes.control(3, 64, 127)))

    @Test
    fun `velocity zero is a note off status, not a note on with zero`() {
        val bytes = MidiBytes.noteOn(0, 60, 0)
        assertEquals(0x80, bytes[0].toInt() and 0xFF)
        assertEquals(listOf(Event.NoteOff(0, 60)), parse(bytes))
    }

    @Test
    fun `out of range arguments are clamped, never thrown`() {
        assertEquals(listOf(Event.NoteOn(15, 127, 1f)), parse(MidiBytes.noteOn(99, 300, 999)))
        assertEquals(listOf(Event.NoteOn(0, 0, 1 / 127f)), parse(MidiBytes.noteOn(-1, -5, 1)))
    }

    @Test
    fun `all notes off is controller 123 at zero`() =
        assertEquals(listOf(Event.Control(9, MidiWire.CC_ALL_NOTES_OFF, 0)), parse(MidiBytes.allNotesOff(9)))

    @Test
    fun `velocity is the inverse of what MidiWire reads, bit for bit`() {
        // PadHit.midiVelocity in :shell rounds the same way; :kit cannot depend on it, so the formula is inlined.
        for (v in 1..127) {
            val on = parse(MidiBytes.noteOn(0, 60, v)).single() as Event.NoteOn
            assertEquals(v, (on.velocity * 127).roundToInt(), "velocity $v")
        }
    }
}
