package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitClockTest {

    // 120 BPM at 48 kHz: a 16th is exactly 6,000 frames, so every number
    // below is a whole one and the assertions can be exact.
    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000

    private fun pattern(steps: Int, lock: Boolean = false, hits: List<Int> = listOf(0)) =
        Orbit("r$steps", steps, PatternOrbit("kit", hits.map { OrbitHit(it, 1) }), lockToBar = lock)

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), bpm, rate)

    @Test
    fun `a 16th at 120 bpm and 48k is 6000 frames`() {
        assertEquals(step, OrbitClock.stepFrames(set()))
        assertEquals(16L * step, OrbitClock.lapFrames(set()))
    }

    @Test
    fun `free - a bigger ring takes longer to come round`() {
        val s = set()
        assertEquals(16L * step, OrbitClock.periodFrames(s, pattern(16)))
        assertEquals(20L * step, OrbitClock.periodFrames(s, pattern(20)))
        assertEquals(step.toDouble(), OrbitClock.ringStepFrames(s, pattern(20)))
    }

    @Test
    fun `locked to the bar - every ring comes round once a bar whatever its steps`() {
        val s = set()
        val three = pattern(3, lock = true)
        val five = pattern(5, lock = true)
        assertEquals(OrbitClock.lapFrames(s), OrbitClock.periodFrames(s, three))
        assertEquals(OrbitClock.lapFrames(s), OrbitClock.periodFrames(s, five))
        // A triplet's steps are a third of the bar each: 16 steps / 3.
        assertEquals(16.0 * step / 3, OrbitClock.ringStepFrames(s, three), 1e-9)
    }

    @Test
    fun `phase wraps at the period and stepAt follows it`() {
        val s = set()
        val ring = pattern(20)
        val period = 20L * step
        assertEquals(0.0, OrbitClock.phase(s, ring, 0))
        assertEquals(0.5, OrbitClock.phase(s, ring, period / 2))
        assertEquals(0.0, OrbitClock.phase(s, ring, period))
        assertEquals(10, OrbitClock.stepAt(s, ring, period / 2))
        assertEquals(19, OrbitClock.stepAt(s, ring, period - 1))
        assertEquals(0, OrbitClock.stepAt(s, ring, period))
    }

    @Test
    fun `the inner ring completes faster - the tape-loop picture`() {
        // The user's mental model: same needle speed, inner circle finishes first.
        val s = set()
        val inner = pattern(16)
        val outer = pattern(20)
        val frame = 16L * step // one lap of the inner ring
        assertEquals(0.0, OrbitClock.phase(s, inner, frame))
        assertEquals(0.8, OrbitClock.phase(s, outer, frame), 1e-9)
    }

    @Test
    fun `4 against 5 realign after 80 steps - five bars`() {
        val s = set(pattern(16), pattern(20))
        assertEquals(80L, OrbitClock.cycleSteps(s))
        assertEquals(5.0, OrbitClock.cycleBars(s))
        assertEquals(80L * step, OrbitClock.cycleFrames(s))
    }

    @Test
    fun `a bar-locked ring never lengthens the cycle`() {
        val s = set(pattern(16), pattern(3, lock = true), pattern(7, lock = true))
        assertEquals(16L, OrbitClock.cycleSteps(s))
        assertEquals(1.0, OrbitClock.cycleBars(s))
    }

    @Test
    fun `coprime rings make a long cycle - and it is reported not hidden`() {
        val s = set(pattern(16), pattern(17), pattern(19))
        assertEquals(16L * 17 * 19, OrbitClock.cycleSteps(s))
        assertEquals(323.0, OrbitClock.cycleBars(s))
    }

    @Test
    fun `length reads in bars, beats or 16ths - whichever divides`() {
        val s = set()
        assertEquals("1 BAR", OrbitClock.lengthLabel(s, pattern(16)))
        assertEquals("2 BARS", OrbitClock.lengthLabel(s, pattern(32)))
        assertEquals("5 BEATS", OrbitClock.lengthLabel(s, pattern(20)))
        assertEquals("3 BEATS", OrbitClock.lengthLabel(s, pattern(12)))
        assertEquals("7 16THS", OrbitClock.lengthLabel(s, pattern(7)))
        assertEquals("1 BAR", OrbitClock.lengthLabel(s, pattern(3, lock = true)))
    }

    @Test
    fun `the ratio is the distinct lengths reduced, shortest first`() {
        assertEquals("4 : 5", OrbitClock.ratioLabel(set(pattern(16), pattern(20), pattern(3, lock = true))))
        assertEquals("3 : 4 : 5", OrbitClock.ratioLabel(set(pattern(20), pattern(12), pattern(16), pattern(16))))
        assertEquals("1", OrbitClock.ratioLabel(set(pattern(16))))
        assertEquals("", OrbitClock.ratioLabel(set()))
    }

    @Test
    fun `the tail is one 16th of time - long on a fast ring, short on a slow one`() {
        val s = set()
        assertEquals(1.0 / 16, OrbitClock.tailSweep(s, pattern(16)), 1e-12)
        assertEquals(1.0 / 20, OrbitClock.tailSweep(s, pattern(20)), 1e-12)
        assertEquals(1.0 / 16, OrbitClock.tailSweep(s, pattern(3, lock = true)), 1e-12)
    }

    @Test
    fun `a voice is the pads a ring may play, and a hit outside it is refused`() {
        val bass = Orbit("bass", 8, PatternOrbit("kit", listOf(OrbitHit(0, 5), OrbitHit(4, 7))), voice = listOf(5, 6, 7))
        assertEquals(listOf(5, 6, 7), bass.pads)
        // No voice given: the pads are whatever the hits name, in slot order; an empty ring plays pad 1.
        assertEquals(listOf(1, 3), Orbit("r", 8, PatternOrbit("kit", listOf(OrbitHit(0, 3), OrbitHit(2, 1)))).pads)
        assertEquals(listOf(1), Orbit("r", 8, PatternOrbit("kit", emptyList())).pads)
        assertTrue(Orbit("s", 8, SnipOrbit("a.wav")).pads.isEmpty())
        val e = runCatching { Orbit("r", 8, PatternOrbit("kit", listOf(OrbitHit(0, 9))), voice = listOf(1, 2)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
    }

    @Test
    fun `an empty set cycles once a bar`() {
        assertEquals(16L, OrbitClock.cycleSteps(set()))
    }

    @Test
    fun `natural steps rounds a snip to its nearest 16th count`() {
        val s = set()
        assertEquals(20, OrbitClock.naturalSteps(s, 20 * step))
        assertEquals(20, OrbitClock.naturalSteps(s, 20 * step + step / 3))
        assertEquals(1, OrbitClock.naturalSteps(s, 10)) // never zero
        assertEquals(Orbit.MAX_STEPS, OrbitClock.naturalSteps(s, 1_000 * step)) // never past the cap
    }

    @Test
    fun `firings - a free ring's hits land on step multiples every lap`() {
        val s = set()
        val ring = pattern(20, hits = listOf(0, 6))
        val period = 20L * step
        val got = OrbitClock.firings(s, ring, 0, 2 * period).map { it.hit.step to it.frame }
        assertEquals(
            listOf(0 to 0L, 6 to 6L * step, 0 to period, 6 to period + 6L * step),
            got,
        )
    }

    @Test
    fun `firings - a locked ring spreads three hits evenly across the bar`() {
        val s = set()
        val ring = pattern(3, lock = true, hits = listOf(0, 1, 2))
        val lap = 16L * step
        val frames = OrbitClock.firings(s, ring, 0, lap).map { it.frame }
        assertEquals(3, frames.size)
        assertEquals(0L, frames[0])
        assertEquals(Math.round(lap / 3.0), frames[1])
        assertEquals(Math.round(2 * lap / 3.0), frames[2])
    }

    @Test
    fun `firings - a window is half-open and a hit on its edge lands once`() {
        val s = set()
        val ring = pattern(16, hits = listOf(0, 8))
        val lap = 16L * step
        // Two consecutive windows that meet at the hit on step 8.
        val a = OrbitClock.firings(s, ring, 0, 8L * step)
        val b = OrbitClock.firings(s, ring, 8L * step, lap)
        assertEquals(listOf(0L), a.map { it.frame })
        assertEquals(listOf(8L * step), b.map { it.frame })
    }

    @Test
    fun `firings - a window that starts mid-lap still finds the next lap's downbeat`() {
        val s = set()
        val ring = pattern(16)
        val lap = 16L * step
        val got = OrbitClock.firings(s, ring, lap - 100, lap + 100)
        assertEquals(listOf(lap), got.map { it.frame })
    }

    @Test
    fun `frames since firing counts up from the hit and wraps at the period`() {
        val s = set()
        val ring = pattern(16, hits = listOf(8))
        val hit = (ring.content as PatternOrbit).hits[0]
        val at = 8L * step
        assertEquals(0L, OrbitClock.framesSinceFiring(s, ring, hit, at))
        assertEquals(100L, OrbitClock.framesSinceFiring(s, ring, hit, at + 100))
        // Just before it fires this lap, the last firing was a lap ago.
        assertEquals(16L * step - 1, OrbitClock.framesSinceFiring(s, ring, hit, at - 1))
        // Frame 0, before the needle has ever reached step 8: still "a lap ago", never negative.
        assertEquals(8L * step, OrbitClock.framesSinceFiring(s, ring, hit, 0))
    }

    @Test
    fun `firings - a snip ring fires nothing`() {
        val s = set()
        val ring = Orbit("s", 16, SnipOrbit("a.wav"))
        assertTrue(OrbitClock.firings(s, ring, 0, 1_000_000).isEmpty())
    }

    @Test
    fun `a hit past the ring's end is refused at construction`() {
        val e = runCatching { pattern(4, hits = listOf(4)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
    }
}
