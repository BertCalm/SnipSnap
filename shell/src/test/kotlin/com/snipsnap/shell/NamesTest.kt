package com.snipsnap.shell

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The word roll for new projects and kits: the words themselves, and the one rule [Names.roll] keeps. */
class NamesTest {

    @Test
    fun `there are enough words to roll from`() {
        assertTrue(Names.WORDS.size >= 120, "only ${Names.WORDS.size} words; a shelf of thirty projects would be rolling repeats")
    }

    @Test
    fun `every word is one upper-case word of three to eight letters`() {
        for (w in Names.WORDS) {
            assertTrue(w.length in 3..8, "$w is ${w.length} letters")
            assertTrue(w.all { it in 'A'..'Z' }, "$w is not plain upper-case letters")
        }
    }

    @Test
    fun `no word is listed twice`() {
        val repeats = Names.WORDS.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(repeats.isEmpty(), "listed twice: $repeats")
    }

    @Test
    fun `no two words are a letter apart`() {
        val pairs = buildList {
            for (i in Names.WORDS.indices) for (j in i + 1 until Names.WORDS.size) {
                if (distance(Names.WORDS[i], Names.WORDS[j]) < 2) add(Names.WORDS[i] to Names.WORDS[j])
            }
        }
        assertTrue(pairs.isEmpty(), "one letter apart, so they read as one name typed twice: $pairs")
    }

    @Test
    fun `no word is a starter's name or a letter from one`() {
        val starters = StarterKits.ALL.map { it.displayName }
        val near = Names.WORDS.flatMap { w -> starters.filter { distance(w, it) < 2 }.map { w to it } }
        assertTrue(near.isEmpty(), "a rolled kit named after a starter that it is not: $near")
    }

    @Test
    fun `no word names the music`() {
        // The words a kit or project must not be called because they say what it holds, or what it is for.
        val music = setOf(
            "KICK", "SNARE", "HAT", "HATS", "TOM", "TOMS", "CLAP", "DRUM", "DRUMS", "BASS", "BEAT", "BEATS",
            "NOTE", "NOTES", "CHORD", "SCALE", "TEMPO", "TUNE", "RIFF", "LOOP", "GROOVE", "RHYTHM", "BELL",
            "BRASS", "CHIME", "PITCH", "KEY", "KEYS", "MINOR", "MAJOR", "SWING", "CYMBAL", "CRASH", "RIDE",
            "SAMPLE", "PAD", "PADS", "TAPE", "CHOP", "SYNTH", "MELODY", "HARMONY", "OCTAVE", "ECHO", "REVERB",
        )
        assertEquals(emptySet(), Names.WORDS.toSet() intersect music)
    }

    @Test
    fun `a roll is a word, and is never one that is taken`() {
        val random = Random(7)
        repeat(300) {
            val taken = Names.WORDS.shuffled(random).take(random.nextInt(0, Names.WORDS.size - 1)).toSet()
            val rolled = Names.roll(random, taken)
            assertTrue(rolled in Names.WORDS, "rolled $rolled, which is not a word")
            assertFalse(rolled in taken, "rolled $rolled, which was taken")
        }
    }

    @Test
    fun `taken is compared without regard to case or space`() {
        val onlyOneLeft = Names.WORDS.drop(1).map { it.lowercase().replaceFirstChar(Char::uppercaseChar) + " " }.toSet()
        repeat(20) { assertEquals(Names.WORDS.first(), Names.roll(Random(it), onlyOneLeft)) }
    }

    @Test
    fun `the same dice roll the same word`() {
        assertEquals(Names.roll(Random(42)), Names.roll(Random(42)))
        val seen = (0 until 50).map { Names.roll(Random(it)) }.toSet()
        assertTrue(seen.size > 10, "50 different dice rolled only ${seen.size} different words")
    }

    @Test
    fun `with every word taken the roll counts up the way a project name does`() {
        val all = Names.WORDS.toSet()
        val first = Names.roll(Random(1), all)
        assertTrue(first.endsWith(" 2") && first.removeSuffix(" 2") in Names.WORDS, first)

        // Hold the "2" of every word and the next free number is 3.
        val twos = all + Names.WORDS.map { "$it 2" }
        val second = Names.roll(Random(1), twos)
        assertTrue(second.endsWith(" 3") && second.removeSuffix(" 3") in Names.WORDS, second)
        assertNotEquals(first, second)
    }

    /** Levenshtein distance: the fewest single-letter edits (change, add or drop) from [a] to [b]. */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
