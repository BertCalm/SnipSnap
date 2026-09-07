package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KeysLayoutTest {

    @Test
    fun `root on A01, chromatic ascends a semitone a pad, the scale layouts skip the wrong notes`() {
        assertEquals(57, KeysLayout.noteFor(1, 57, "CHROMATIC"))
        assertEquals(58, KeysLayout.noteFor(2, 57, "CHROMATIC"))
        assertEquals(72, KeysLayout.noteFor(16, 57, "CHROMATIC"))
        assertEquals(listOf(57, 59, 60, 62, 64, 65, 67, 69), (1..8).map { KeysLayout.noteFor(it, 57, "MINOR") })
        assertEquals(listOf(57, 60, 62, 64, 67), (1..5).map { KeysLayout.noteFor(it, 57, "MIN PENT") })
        assertEquals(69, KeysLayout.noteFor(1, 57, "CHROMATIC", octave = 1))
        assertEquals(45, KeysLayout.noteFor(1, 57, "MAJOR", octave = -1))
        assertNull(KeysLayout.noteFor(16, 120, "CHROMATIC"), "past MIDI's top: no note")
        assertEquals("A3", KeysLayout.label(57))
        assertFailsWith<IllegalArgumentException> { KeysLayout.noteFor(0, 57, "CHROMATIC") }
        assertFailsWith<IllegalArgumentException> { KeysLayout.noteFor(1, 57, "DORIAN") }
        assertFailsWith<IllegalArgumentException> { KeysLayout.noteFor(1, 57, "MAJOR", octave = 4) }
        assertEquals(listOf("CHROMATIC", "MAJOR", "MINOR", "MIN PENT"), KeysLayout.LAYOUTS)
    }
}
