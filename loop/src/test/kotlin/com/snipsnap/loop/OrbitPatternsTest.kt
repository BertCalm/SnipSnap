package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitPatternsTest {

    private fun ring(steps: Int, voice: List<Int> = listOf(1), hits: List<OrbitHit> = emptyList()) =
        Orbit("r", steps, PatternOrbit("kit", hits), voice = voice)

    @Test
    fun `euclid lands the textbook forms, onset first`() {
        assertEquals(listOf(0, 3, 6), OrbitPatterns.euclid(3, 8), "the tresillo")
        assertEquals(listOf(0, 2, 3, 5, 6), OrbitPatterns.euclid(5, 8), "the cinquillo")
        assertEquals(listOf(0, 4, 8, 12), OrbitPatterns.euclid(4, 16), "four on the floor")
        assertEquals(listOf(0), OrbitPatterns.euclid(1, 4))
        assertEquals((0 until 5).toList(), OrbitPatterns.euclid(5, 5))
        assertTrue(OrbitPatterns.euclid(0, 8).isEmpty())
    }

    @Test
    fun `rotation moves the pattern later and wraps`() {
        assertEquals(listOf(0, 2, 5), OrbitPatterns.euclid(3, 8, rotation = 2))
        assertEquals(listOf(1, 4, 7), OrbitPatterns.euclid(3, 8, rotation = 1))
    }

    @Test
    fun `spread replaces one pad's hits and leaves the others alone`() {
        val r = ring(8, voice = listOf(1, 2), hits = listOf(OrbitHit(1, 1), OrbitHit(4, 2)))
        val out = OrbitPatterns.spread(r, 1, 3)
        val hits = (out.content as PatternOrbit).hits
        assertEquals(listOf(0, 3, 6), hits.filter { it.slot == 1 }.map { it.step })
        assertEquals(listOf(4), hits.filter { it.slot == 2 }.map { it.step })
        assertEquals(OrbitPatterns.DOWNBEAT_VELOCITY, hits.first { it.slot == 1 && it.step == 0 }.velocity)
    }

    @Test
    fun `spread refuses a pad the ring does not play`() {
        val e = runCatching { OrbitPatterns.spread(ring(8), 5, 3) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
    }

    @Test
    fun `clear empties the ring and keeps its shape`() {
        val r = ring(12, voice = listOf(3), hits = listOf(OrbitHit(0, 3), OrbitHit(6, 3)))
        val out = OrbitPatterns.clear(r)
        assertTrue((out.content as PatternOrbit).hits.isEmpty())
        assertEquals(12, out.steps)
        assertEquals(listOf(3), out.pads)
    }

    @Test
    fun `scramble is deterministic per seed, stays on the ring, and rolls again with a new seed`() {
        val r = ring(16, voice = listOf(9, 10, 11))
        val a = OrbitPatterns.scramble(r, 7)
        val b = OrbitPatterns.scramble(r, 7)
        val c = OrbitPatterns.scramble(r, 8)
        assertEquals(a, b)
        val hits = (a.content as PatternOrbit).hits
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.step in 0 until 16 && it.slot in r.pads })
        assertTrue(hits.all { it.velocity in setOf(OrbitPatterns.SOFT_VELOCITY, OrbitPatterns.HIT_VELOCITY, OrbitPatterns.ACCENT_VELOCITY) })
        // No pad hits the same step twice.
        assertEquals(hits.size, hits.map { it.step to it.slot }.toSet().size)
        assertTrue(a != c, "a new seed should roll differently")
        // Every pad in the voice gets something.
        assertEquals(r.pads.toSet(), hits.map { it.slot }.toSet())
    }

    @Test
    fun `velocity cycles soft, normal, accent`() {
        assertEquals(OrbitPatterns.HIT_VELOCITY, OrbitPatterns.nextVelocity(OrbitPatterns.SOFT_VELOCITY))
        assertEquals(OrbitPatterns.ACCENT_VELOCITY, OrbitPatterns.nextVelocity(OrbitPatterns.HIT_VELOCITY))
        assertEquals(OrbitPatterns.SOFT_VELOCITY, OrbitPatterns.nextVelocity(OrbitPatterns.ACCENT_VELOCITY))
    }

    @Test
    fun `a snip ring is left alone by every generator`() {
        val s = Orbit("s", 16, SnipOrbit("a.wav"))
        assertEquals(s, OrbitPatterns.clear(s))
        assertEquals(s, OrbitPatterns.scramble(s, 3))
    }
}
