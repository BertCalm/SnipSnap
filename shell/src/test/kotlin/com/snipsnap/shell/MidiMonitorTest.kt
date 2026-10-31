package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MidiMonitorTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun says(vararg b: Int) = MidiMonitor.describe(bytes(*b))

    @Test fun `notes say their number, velocity and channel from 1`() {
        assertEquals("NOTE ON 36 V100 CH10", says(0x99, 36, 100))
        assertEquals("NOTE OFF 60 CH1", says(0x80, 60, 0))
        assertEquals("NOTE OFF 36 (V0) CH10", says(0x99, 36, 0), "velocity 0 is a note-off, and says so")
    }

    @Test fun `controllers, program and both pressures`() {
        assertEquals("CC 74 = 64 CH1", says(0xB0, 74, 64))
        assertEquals("PROGRAM 5 CH2", says(0xC1, 5))
        assertEquals("PRESSURE 90 CH10", says(0xD9, 90))
        assertEquals("POLY PRESSURE 36 = 70 CH10", says(0xA9, 36, 70))
    }

    @Test fun `bend is signed around the centre`() {
        assertEquals("BEND 0 CH1", says(0xE0, 0x00, 0x40))
        assertEquals("BEND -8192 CH1", says(0xE0, 0x00, 0x00))
        assertEquals("BEND +8191 CH1", says(0xE0, 0x7F, 0x7F))
    }

    @Test fun `running status inside a delivery repeats the last status`() =
        assertEquals("CC 1 = 10 CH1 · CC 1 = 11 CH1", says(0xB0, 1, 10, 1, 11))

    @Test fun `a delivery that starts with data says it continues something`() =
        assertEquals("CONTINUED 2 B", says(1, 11))

    @Test fun `a message cut short by the end or by a new status says PART`() {
        assertEquals("NOTE ON PART CH1", says(0x90, 60))
        assertEquals("NOTE ON PART CH1 · CC 7 = 100 CH1", says(0x90, 60, 0xB0, 7, 100))
    }

    @Test fun `a whole SysEx says its length and maker`() =
        // The MiniLab 3's pad-colour message, from its DAW scripts.
        assertEquals(
            "SYSEX 14 B ID 00 20 6B",
            says(0xF0, 0x00, 0x20, 0x6B, 0x7F, 0x42, 0x02, 0x02, 0x16, 0x34, 0x7F, 0x00, 0x00, 0xF7),
        )

    @Test fun `a SysEx split across deliveries is labelled piece by piece`() {
        assertEquals("SYSEX 4 B ID 00 20 6B PART", says(0xF0, 0x00, 0x20, 0x6B))
        assertEquals("CONTINUED 3 B · SYSEX END", says(0x7F, 0x42, 0x02, 0xF7))
        assertEquals("SYSEX 3 B ID 43", says(0xF0, 0x43, 0xF7), "a one-byte maker id")
    }

    @Test fun `real-time bytes inside a message are listed after it, not lost`() =
        assertEquals("NOTE ON 60 V100 CH1 · START", says(0x90, 0xFA, 60, 100))

    @Test fun `system common messages`() {
        assertEquals("SONG POSITION 130", says(0xF2, 2, 1))
        assertEquals("STOP", says(0xFC))
        assertEquals("UNDEFINED F4", says(0xF4))
    }

    @Test fun `clock and active sensing are counted, not listed`() {
        val m = MidiMonitor()
        m.deliver("A", bytes(0xF8, 0xF8, 0xFE), timestampNanos = 1)
        m.deliver("A", bytes(0x90, 0xF8, 60, 100), timestampNanos = 2)
        val s = m.snapshot()
        assertEquals(3, s.clockTicks)
        assertEquals(1, s.activeSensing)
        assertEquals(1, s.lines.size, "a delivery of clock alone makes no line")
        assertEquals("90 3C 64", s.lines[0].hex, "the clock byte is counted out of the note's line")
        assertEquals("NOTE ON 60 V100 CH1", s.lines[0].meaning)
    }

    @Test fun `lines are newest first, bounded, and counted past the bound`() {
        val m = MidiMonitor(capacity = 3)
        for (n in 1..5) m.deliver("A", bytes(0x90, n, 1), timestampNanos = n.toLong())
        val s = m.snapshot()
        assertEquals(listOf(5, 4, 3).map { "NOTE ON $it V1 CH1" }, s.lines.map { it.meaning })
        assertEquals(5, s.total)
    }

    @Test fun `the delta is per port, from the service's stamps`() {
        val m = MidiMonitor()
        m.deliver("A", bytes(0x90, 1, 1), timestampNanos = 1_000_000)
        m.deliver("B", bytes(0x90, 2, 1), timestampNanos = 2_000_000)
        m.deliver("A", bytes(0x90, 3, 1), timestampNanos = 13_500_000)
        val (a2, b1, a1) = m.snapshot().lines
        assertNull(a1.deltaMillis, "a port's first line has nothing to follow")
        assertNull(b1.deltaMillis)
        assertEquals(12.5, a2.deltaMillis!!, 1e-9, "B's line between them does not count")
    }

    @Test fun `no stamp, no delta`() {
        val m = MidiMonitor()
        m.deliver("A", bytes(0x90, 1, 1), timestampNanos = 0)
        m.deliver("A", bytes(0x90, 2, 1), timestampNanos = 5_000_000)
        assertNull(m.snapshot().lines[0].deltaMillis)
    }

    @Test fun `offset and count select the delivery inside a larger buffer`() {
        val m = MidiMonitor()
        m.deliver("A", bytes(0x00, 0xB0, 7, 99, 0x00), offset = 1, count = 3, timestampNanos = 1)
        assertEquals("CC 7 = 99 CH1", m.snapshot().lines.single().meaning)
        assertFailsWith<IllegalArgumentException> { m.deliver("A", bytes(1, 2), offset = 1, count = 2, timestampNanos = 1) }
    }

    @Test fun `long hex is cut with a count of the rest`() {
        val long = ByteArray(30) { 0x10 }
        val hex = MidiMonitor.hex(long)
        assertTrue(hex.endsWith("... +6 B"), hex)
        assertEquals(MidiMonitor.HEX_BYTES, hex.substringBefore(" ...").split(" ").size)
    }

    @Test fun `clear forgets lines, counters and stamps`() {
        val m = MidiMonitor()
        m.deliver("A", bytes(0xF8, 0x90, 1, 1), timestampNanos = 1_000_000)
        m.clear()
        m.deliver("A", bytes(0x90, 2, 1), timestampNanos = 9_000_000)
        val s = m.snapshot()
        assertEquals(0, s.clockTicks)
        assertEquals(1, s.total)
        assertNull(s.lines.single().deltaMillis, "the stamp before the clear is gone too")
    }
}
