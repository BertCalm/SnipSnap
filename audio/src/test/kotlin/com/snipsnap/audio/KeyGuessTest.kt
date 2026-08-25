package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyGuessTest {

    @Test
    fun `a scrambled A minor arpeggio lands on A minor, not its relative`() {
        // A×3, C×2, E×2 — coverage ties A minor with C major (relatives
        // share every note); the root the material leans on breaks it.
        val hz = listOf(110f, 130.81f, 164.81f, 220f, 261.63f, 329.63f, 440f)
        val guess = KeyGuess.guess(hz.shuffled(kotlin.random.Random(7)))!!
        assertEquals(KeySpec(9, Scale.MINOR), guess.key, guess.key.label)
        assertEquals(1f, guess.confidence, "every note is in the scale")
    }

    @Test
    fun `a full major scale names its major key`() {
        // E F# G# A B C# D# with E doubled: E major over C# minor.
        val hz = listOf(329.63f, 369.99f, 415.30f, 440f, 493.88f, 554.37f, 622.25f, 659.25f)
        val guess = KeyGuess.guess(hz)!!
        assertEquals(KeySpec(4, Scale.MAJOR), guess.key, guess.key.label)
    }

    @Test
    fun `out-of-key material lowers the confidence`() {
        // An A minor triad plus two chromatic strangers.
        val hz = listOf(110f, 130.81f, 164.81f, 220f, 116.54f, 155.56f) // + A#2, D#3
        val guess = KeyGuess.guess(hz)!!
        assertTrue(guess.confidence < 1f, "strangers cost coverage: ${guess.confidence}")
    }

    @Test
    fun `too little or too uniform material is refused, not guessed`() {
        assertNull(KeyGuess.guess(emptyList()), "silence has no key")
        assertNull(KeyGuess.guess(listOf(110f, 220f)), "two hits are not a key")
        assertNull(KeyGuess.guess(listOf(110f, 110f, 220f, 440f)), "one looped note is not a key")
    }

    @Test
    fun `pitch classes come out of frequencies right`() {
        assertEquals(9, KeyGuess.pitchClass(440f), "A4")
        assertEquals(9, KeyGuess.pitchClass(110f), "A2")
        assertEquals(0, KeyGuess.pitchClass(261.63f), "C4")
        assertEquals(10, KeyGuess.pitchClass(466.16f), "A#4")
    }
}
