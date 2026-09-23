package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArpTest {

    // 120 BPM at 48 kHz, matching OrbitClockTest's own numbers so the gate
    // assertions below are exact rather than rounded.
    private val rate = 48_000
    private val bpm = 120f

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), bpm, rate)

    private fun ring(steps: Int, span: OrbitSpan = OrbitSpan.FREE) =
        Orbit("arp", steps, PatternOrbit("kit", emptyList()), span = span)

    @Test
    fun `up and down sort the notes either way`() {
        assertEquals(listOf(3, 5, 9), Arp.walk(listOf(9, 3, 5), Arp.Shape.UP))
        assertEquals(listOf(9, 5, 3), Arp.walk(listOf(9, 3, 5), Arp.Shape.DOWN))
    }

    @Test
    fun `up-down does not repeat the peak or the valley`() {
        assertEquals(listOf(1, 2, 3, 4, 3, 2), Arp.walk(listOf(4, 1, 3, 2), Arp.Shape.UP_DOWN))
        // Two notes: the "down" leg would just repeat both ends, so up-down is plain up.
        assertEquals(listOf(1, 2), Arp.walk(listOf(2, 1), Arp.Shape.UP_DOWN))
        // One note: nothing to turn around on.
        assertEquals(listOf(5), Arp.walk(listOf(5), Arp.Shape.UP_DOWN))
    }

    @Test
    fun `as played keeps the pick order, unsorted`() {
        assertEquals(listOf(9, 3, 5), Arp.walk(listOf(9, 3, 5), Arp.Shape.AS_PLAYED))
    }

    @Test
    fun `random is deterministic per seed and rolls again with a new one`() {
        val notes = listOf(1, 2, 3, 4, 5, 6)
        val a = Arp.walk(notes, Arp.Shape.RANDOM, seed = 7)
        val b = Arp.walk(notes, Arp.Shape.RANDOM, seed = 7)
        val c = Arp.walk(notes, Arp.Shape.RANDOM, seed = 8)
        assertEquals(a, b)
        assertEquals(notes.toSet(), a.toSet())
        assertTrue(a != c, "a new seed should roll differently")
    }

    @Test
    fun `chord keeps every note together and never stacks octaves`() {
        assertEquals(listOf(1, 2, 3), Arp.walk(listOf(3, 1, 2), Arp.Shape.CHORD, octaves = 3))
    }

    @Test
    fun `octaves stack copies of the run above the first, low copy first`() {
        assertEquals(
            listOf(1, 2, 17, 18, 33, 34),
            Arp.walk(listOf(2, 1), Arp.Shape.UP, octaves = 3),
        )
        // A custom step is honoured, e.g. reaching two banks at once.
        assertEquals(listOf(1, 33), Arp.walk(listOf(1), Arp.Shape.UP, octaves = 2, octaveStep = 32))
    }

    @Test
    fun `walk refuses no notes and fewer than one octave`() {
        assertFailsWith<IllegalArgumentException> { Arp.walk(emptyList(), Arp.Shape.UP) }
        assertFailsWith<IllegalArgumentException> { Arp.walk(listOf(1), Arp.Shape.UP, octaves = 0) }
    }

    @Test
    fun `run wraps a short run across a longer ring`() {
        val out = Arp.run(set(), ring(8), listOf(1, 2), Arp.Shape.UP)
        val hits = (out.content as PatternOrbit).hits
        assertEquals((0 until 8).toList(), hits.map { it.step })
        assertEquals(listOf(1, 2, 1, 2, 1, 2, 1, 2), hits.map { it.slot })
        assertEquals(listOf(1, 2), out.voice)
    }

    @Test
    fun `run cuts a long run short across a smaller ring`() {
        val out = Arp.run(set(), ring(3), listOf(1, 2, 3, 4, 5), Arp.Shape.UP)
        val hits = (out.content as PatternOrbit).hits
        assertEquals(listOf(1, 2, 3), hits.map { it.slot })
        // The voice still names every note the run could have reached, even
        // the two this ring has no room to play - AS_PLAYED on a bigger ring
        // later reaches them without a voice edit.
        assertEquals(listOf(1, 2, 3, 4, 5), out.voice)
    }

    @Test
    fun `chord fills every step with every note at once`() {
        val out = Arp.run(set(), ring(4), listOf(5, 1, 3), Arp.Shape.CHORD)
        val hits = (out.content as PatternOrbit).hits
        assertEquals(12, hits.size)
        for (step in 0 until 4) {
            assertEquals(listOf(1, 3, 5), hits.filter { it.step == step }.map { it.slot }.sorted())
        }
    }

    @Test
    fun `full gate leaves the sample to play out`() {
        val out = Arp.run(set(), ring(4), listOf(1), Arp.Shape.UP, gate = 1f)
        val hits = (out.content as PatternOrbit).hits
        assertTrue(hits.all { it.length == OrbitHit.WHOLE_SAMPLE })
    }

    @Test
    fun `a half gate cuts a hit to half its step, in pulses`() {
        val out = Arp.run(set(), ring(16), listOf(1), Arp.Shape.UP, gate = 0.5f)
        val hits = (out.content as PatternOrbit).hits
        val stepPulses = OrbitClock.ringStepPulses(set(), ring(16))
        assertEquals((stepPulses * 0.5).toLong(), hits.first().length)
        assertTrue(hits.all { it.gated })
    }

    @Test
    fun `run refuses a gate out of range and a snip ring`() {
        assertFailsWith<IllegalArgumentException> { Arp.run(set(), ring(4), listOf(1), Arp.Shape.UP, gate = 0f) }
        assertFailsWith<IllegalArgumentException> { Arp.run(set(), ring(4), listOf(1), Arp.Shape.UP, gate = 1.1f) }
        val snip = Orbit("s", 4, SnipOrbit("take.wav"))
        assertFailsWith<IllegalArgumentException> { Arp.run(set(), snip, listOf(1), Arp.Shape.UP) }
    }

    @Test
    fun `shape cycles and round-trips its stored name`() {
        assertEquals(Arp.Shape.DOWN, Arp.Shape.UP.next)
        assertEquals(Arp.Shape.UP, Arp.Shape.CHORD.next)
        assertEquals(Arp.Shape.RANDOM, Arp.Shape.fromName("RANDOM"))
        assertEquals(Arp.Shape.UP, Arp.Shape.fromName("not a real shape"))
    }
}
