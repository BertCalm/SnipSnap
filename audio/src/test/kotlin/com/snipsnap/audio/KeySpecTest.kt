package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeySpecTest {

    @Test
    fun `parses the ways people write keys`() {
        assertEquals(KeySpec(9, Scale.MINOR), KeySpec.parse("Am"))
        assertEquals(KeySpec(0, Scale.MAJOR), KeySpec.parse("C"))
        assertEquals(KeySpec(6, Scale.MINOR_PENTATONIC), KeySpec.parse("F#minpent"))
        assertEquals(KeySpec(3, Scale.MAJOR), KeySpec.parse("Eb major"))
        assertEquals(KeySpec(10, Scale.MINOR), KeySpec.parse("bb minor"))
        assertEquals(KeySpec(7, Scale.CHROMATIC), KeySpec.parse("G chromatic"))
    }

    @Test
    fun `rejects what it cannot read`() {
        assertFailsWith<IllegalArgumentException> { KeySpec.parse("H major") }
        assertFailsWith<IllegalArgumentException> { KeySpec.parse("C mixolydian") }
        assertFailsWith<IllegalArgumentException> { KeySpec.parse("") }
        assertFailsWith<IllegalArgumentException> { KeySpec(12, Scale.MAJOR) }
    }

    @Test
    fun `labels read like key signatures`() {
        assertEquals("A minor", KeySpec.parse("Am").label)
        assertEquals("D# major pentatonic", KeySpec.parse("Ebmajpent").label)
        assertEquals("C", KeySpec.parse("c").noteName)
    }
}
