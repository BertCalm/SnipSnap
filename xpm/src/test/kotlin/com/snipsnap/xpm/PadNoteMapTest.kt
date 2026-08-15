package com.snipsnap.xpm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PadNoteMapTest {

    @Test
    fun `map covers every pad exactly once`() {
        assertEquals(PadNoteMap.PAD_COUNT, PadNoteMap.DEFAULT.size)
        assertEquals(PadNoteMap.PAD_COUNT, PadNoteMap.DEFAULT.toSet().size)
    }

    @Test
    fun `every note is a legal midi note`() {
        PadNoteMap.DEFAULT.forEach { note ->
            assertEquals(true, note in 0..127, "illegal MIDI note: $note")
        }
    }

    @Test
    fun `bank A is the classic MPC drum layout`() {
        // Kick on A02, closed hat on A03, snare on A06 — the layout every MPC
        // user has muscle memory for. Change these and the kits feel wrong.
        assertEquals(37, PadNoteMap.noteForPad(1))
        assertEquals(36, PadNoteMap.noteForPad(2))
        assertEquals(42, PadNoteMap.noteForPad(3))
        assertEquals(38, PadNoteMap.noteForPad(6))
        assertEquals(53, PadNoteMap.noteForPad(16))
    }

    @Test
    fun `pad labels follow bank and position`() {
        assertEquals("A01", PadNoteMap.labelForPad(1))
        assertEquals("A16", PadNoteMap.labelForPad(16))
        assertEquals("B01", PadNoteMap.labelForPad(17))
        assertEquals("H16", PadNoteMap.labelForPad(128))
    }

    @Test
    fun `rejects pads outside the grid`() {
        assertFailsWith<IllegalArgumentException> { PadNoteMap.noteForPad(0) }
        assertFailsWith<IllegalArgumentException> { PadNoteMap.noteForPad(129) }
        assertFailsWith<IllegalArgumentException> { PadNoteMap.labelForPad(0) }
    }
}
