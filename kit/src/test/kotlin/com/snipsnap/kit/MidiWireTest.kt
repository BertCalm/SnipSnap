package com.snipsnap.kit

import com.snipsnap.kit.MidiWire.Event
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MidiWireTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `a note on and its note off come out typed, with velocity as 0 to 1`() {
        val wire = MidiWire()
        val events = wire.feed(bytes(0x90, 60, 127, 0x80, 60, 64))
        assertEquals(listOf(Event.NoteOn(0, 60, 1f), Event.NoteOff(0, 60)), events)
    }

    @Test
    fun `a note on at velocity zero is a note off`() {
        assertEquals(listOf(Event.NoteOff(2, 48)), MidiWire().feed(bytes(0x92, 48, 0)))
    }

    @Test
    fun `running status carries the channel status across notes and across deliveries`() {
        val wire = MidiWire()
        val first = wire.feed(bytes(0x91, 60, 100, 62, 100))
        assertEquals(listOf(Event.NoteOn(1, 60, 100 / 127f), Event.NoteOn(1, 62, 100 / 127f)), first)
        // The next delivery is data alone: the keyboard sent its status once.
        assertEquals(listOf(Event.NoteOn(1, 64, 100 / 127f), Event.NoteOff(1, 60)), wire.feed(bytes(64, 100, 60, 0)))
    }

    @Test
    fun `a message split across deliveries is finished by the next one`() {
        val wire = MidiWire()
        assertEquals(emptyList(), wire.feed(bytes(0x90)))
        assertEquals(emptyList(), wire.feed(bytes(60)))
        assertEquals(listOf(Event.NoteOn(0, 60, 90 / 127f)), wire.feed(bytes(90)))
    }

    @Test
    fun `real-time bytes in the middle of a message are stepped over and leave running status alone`() {
        val wire = MidiWire()
        // Clock between the status and its data, active sensing between the data bytes.
        val events = wire.feed(bytes(0x90, 0xF8, 60, 0xFE, 100, 0xF8, 0xFA, 62, 100))
        assertEquals(listOf(Event.NoteOn(0, 60, 100 / 127f), Event.NoteOn(0, 62, 100 / 127f)), events)
    }

    @Test
    fun `a SysEx is skipped whole and cancels running status, even split across deliveries`() {
        val wire = MidiWire()
        assertEquals(listOf(Event.NoteOn(0, 60, 1f)), wire.feed(bytes(0x90, 60, 127, 0xF0, 0x7E, 0x7F)))
        // Still inside the SysEx: a byte that would be a data pair is not one.
        assertEquals(emptyList(), wire.feed(bytes(0x09, 0x01)))
        // Terminated; data after it has no status to lean on, so it is noise...
        assertEquals(emptyList(), wire.feed(bytes(0xF7, 62, 100)))
        // ...until a status byte arrives again.
        assertEquals(listOf(Event.NoteOn(0, 62, 100 / 127f)), wire.feed(bytes(0x90, 62, 100)))
    }

    @Test
    fun `a stray end-of-exclusive is system common too - it cancels running status and a half-built note`() {
        val wire = MidiWire()
        // Running status, then a bare 0xF7 with no 0xF0 before it: the data
        // after it has no status to lean on, so it must not become a note.
        assertEquals(listOf(Event.NoteOn(0, 60, 100 / 127f)), wire.feed(bytes(0x90, 60, 100)))
        assertEquals(emptyList(), wire.feed(bytes(0xF7, 62, 100)), "running status is cancelled, not reused")
        // Half a note, then the stray byte: the pending data byte is dropped
        // with the status, so the next data byte cannot finish a stale note.
        assertEquals(emptyList(), wire.feed(bytes(0x90, 64, 0xF7, 100)))
        assertEquals(listOf(Event.NoteOn(0, 65, 100 / 127f)), wire.feed(bytes(0x90, 65, 100)), "a fresh status starts clean")
    }

    @Test
    fun `one-byte and three-byte messages nothing here plays keep the stream in step`() {
        val wire = MidiWire()
        // Program change (1 data byte), pitch bend (2), aftertouch (1), poly pressure (2), then a note.
        val events = wire.feed(bytes(0xC0, 5, 0xE0, 0, 64, 0xD0, 50, 0xA0, 60, 10, 0x90, 60, 80))
        assertEquals(listOf(Event.NoteOn(0, 60, 80 / 127f)), events)
    }

    @Test
    fun `a controller comes out with its number and value`() {
        assertEquals(
            listOf(Event.Control(3, MidiWire.CC_SUSTAIN, 127), Event.Control(3, MidiWire.CC_SUSTAIN, 0)),
            MidiWire().feed(bytes(0xB3, 64, 127, 64, 0)),
        )
    }

    @Test
    fun `song position cancels running status where a clock does not`() {
        val wire = MidiWire()
        wire.feed(bytes(0x90, 60, 100))
        // Song Position Pointer: 0xF2 plus two data bytes, which must not be read as a note.
        assertEquals(emptyList(), wire.feed(bytes(0xF2, 0x10, 0x20)))
        assertEquals(emptyList(), wire.feed(bytes(62, 100)), "data after a system common has no status to use")
    }

    @Test
    fun `velocity is the inverse of what MidiGroove writes, bit for bit`() {
        for (midi in 1..127) {
            val v = MidiWire.velocity(midi)
            assertTrue(v > 0f && v <= 1f, "velocity $midi maps inside (0, 1], got $v")
            // MidiGroove.write: (velocity * 127).roundToInt().coerceIn(1, 127)
            assertEquals(midi, (v * 127f).roundToInt().coerceIn(1, 127), "round trip of $midi")
        }
        assertTrue(abs(MidiWire.velocity(0) - MidiWire.velocity(1)) < 1e-6, "zero is never asked for, and answers as the floor")
    }

    @Test
    fun `an event carries the stamp of the delivery that completed it`() {
        val wire = MidiWire()
        // One delivery, one stamp, on every event it completes - note, note-off and controller alike.
        assertEquals(
            listOf(
                Event.NoteOn(0, 60, 100 / 127f, timestampNanos = 1_000L),
                Event.NoteOff(0, 60, timestampNanos = 1_000L),
                Event.Control(0, MidiWire.CC_SUSTAIN, 127, timestampNanos = 1_000L),
            ),
            wire.feed(bytes(0x90, 60, 100, 0x80, 60, 0, 0xB0, 64, 127), timestampNanos = 1_000L),
        )
        // Running status across deliveries: each delivery's notes carry its own stamp, not the first one's.
        assertEquals(listOf(Event.NoteOn(1, 60, 100 / 127f, timestampNanos = 2_000L)), wire.feed(bytes(0x91, 60, 100), timestampNanos = 2_000L))
        assertEquals(listOf(Event.NoteOn(1, 62, 100 / 127f, timestampNanos = 3_000L)), wire.feed(bytes(62, 100), timestampNanos = 3_000L))
    }

    @Test
    fun `a message split across deliveries is stamped by the delivery that finished it`() {
        val wire = MidiWire()
        assertEquals(emptyList(), wire.feed(bytes(0x90, 60), timestampNanos = 5_000L))
        // The status and the note arrived at 5000; the velocity, which made it a note, at 7000.
        assertEquals(listOf(Event.NoteOn(0, 60, 90 / 127f, timestampNanos = 7_000L)), wire.feed(bytes(90), timestampNanos = 7_000L))
        // The stamp does not linger: a delivery with no stamp completes an unstamped note.
        assertEquals(listOf(Event.NoteOn(0, 62, 90 / 127f)), wire.feed(bytes(0x90, 62, 90)))
        assertEquals(0L, wire.feed(bytes(0x90, 64, 90)).single().timestampNanos)
    }

    @Test
    fun `an unstamped event equals the same event stamped zero, so the rules in shell keep comparing by note`() {
        assertEquals(Event.NoteOn(0, 60, 1f), Event.NoteOn(0, 60, 1f, timestampNanos = 0L))
        assertEquals(Event.NoteOff(0, 60), Event.NoteOff(0, 60, timestampNanos = 0L))
    }

    @Test
    fun `the window is checked`() {
        // Each refusal must be the window guard's own. With the guard gone, the first of these still throws, but an
        // ArrayIndexOutOfBoundsException, and a negative count reads nothing and answers an empty list.
        for ((offset, count) in listOf(1 to 5, 0 to -1, -1 to 1, 3 to 0)) {
            val refused = assertFailsWith<IllegalArgumentException>("window $offset+$count") {
                MidiWire().feed(bytes(0x90, 60), offset = offset, count = count)
            }
            assertEquals("window $offset+$count is outside 2 bytes", refused.message)
        }
    }

    @Test
    fun `an offset window reads only its bytes`() {
        val wire = MidiWire()
        assertEquals(listOf(Event.NoteOff(0, 60)), wire.feed(bytes(0x11, 0x80, 60, 0, 0x90), offset = 1, count = 3))
    }

    @Test
    fun `a status byte ends a SysEx that lost its end-of-exclusive, so the notes after it are heard`() {
        val wire = MidiWire()
        assertEquals(listOf(Event.NoteOn(0, 60, 100 / 127f)), wire.feed(bytes(0x90, 60, 100)))
        // A SysEx whose 0xF7 never comes, then the held key's note-off.
        assertEquals(
            listOf(Event.NoteOff(0, 60)),
            wire.feed(bytes(0xF0, 0x7E, 0x7F, 0x80, 60, 0)),
            "a channel status byte must end a SysEx with no 0xF7, or the held key's note-off is swallowed",
        )
        assertEquals(listOf(Event.NoteOff(0, 62)), wire.feed(bytes(62, 0)), "the status that ended the SysEx is the running status after it")
        // A SysEx left open at the end of one delivery ends at the next delivery's status byte.
        assertEquals(emptyList(), wire.feed(bytes(0xF0, 0x43, 0x10)))
        assertEquals(
            listOf(Event.NoteOn(1, 64, 90 / 127f)),
            wire.feed(bytes(0x91, 64, 90)),
            "a channel status byte must end a SysEx left open by the last delivery",
        )
    }

    @Test
    fun `real-time bytes inside a SysEx are stepped over, and its data stays skipped`() {
        val wire = MidiWire()
        assertEquals(listOf(Event.NoteOn(0, 60, 100 / 127f)), wire.feed(bytes(0x90, 60, 100)))
        // Clock and active sensing between the SysEx's data bytes: none of its data becomes a note.
        // This pins output only: 0xF0 clears running status, so a real-time byte that wrongly ended
        // the SysEx would still leave its data bytes with no status to complete.
        assertEquals(emptyList(), wire.feed(bytes(0xF0, 0xF8, 60, 0xFE, 100, 0xF8, 62, 100)), "a real-time byte inside a SysEx")
        assertEquals(listOf(Event.NoteOn(0, 64, 100 / 127f)), wire.feed(bytes(0xF7, 0x90, 64, 100)))
    }

    @Test
    fun `a window whose end overflows an Int is refused by the window guard`() {
        // offset + count wraps negative here, and a negative end passes a plain `offset + count <= size` and reads nothing.
        for ((offset, count) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val refused = assertFailsWith<IllegalArgumentException>("window $offset+$count overflows: the window guard must refuse it") {
                MidiWire().feed(bytes(0x90, 60, 100), offset = offset, count = count)
            }
            assertEquals("window $offset+$count is outside 3 bytes", refused.message)
        }
    }
}
