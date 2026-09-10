package com.snipsnap.loop

import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitClipTest {

    private fun pattern(name: String, steps: Int, slot: Int, hits: List<Int>, span: OrbitSpan = OrbitSpan.FREE, engaged: Boolean = true) =
        Orbit(name, steps, PatternOrbit("kit", hits.map { OrbitHit(it, slot, 0.8f) }), span = span, engaged = engaged)

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), 120f, 48_000)

    @Test
    fun `one cycle of 16 against 20 is a five-bar clip with every firing on the pulse grid`() {
        val s = set(pattern("four", 16, 1, listOf(0)), pattern("five", 20, 2, listOf(0, 10)))
        val clip = OrbitClip.clip(s)
        assertEquals(5, clip.bars)
        assertEquals("ORBIT 4:5", clip.name)
        val fours = clip.notes.filter { it.note == 36 }.map { it.timePulses }
        val fives = clip.notes.filter { it.note == 37 }.map { it.timePulses }
        assertEquals((0 until 5).map { it * Mpc3Clip.PULSES_PER_BAR }, fours)
        val fiveBeats = 20 * Mpc3Clip.PULSES_PER_16TH
        assertEquals((0 until 4).flatMap { listOf(it * fiveBeats, it * fiveBeats + 10 * Mpc3Clip.PULSES_PER_16TH) }, fives)
    }

    @Test
    fun `a locked triplet lands on thirds of the bar`() {
        val s = set(pattern("three", 3, 4, listOf(0, 1, 2), span = OrbitSpan.ONE))
        val clip = OrbitClip.clip(s)
        assertEquals(1, clip.bars)
        assertEquals(listOf(0L, 1280L, 2560L), clip.notes.map { it.timePulses })
        assertEquals(listOf(39, 39, 39), clip.notes.map { it.note })
    }

    @Test
    fun `a triplet across two bars lands on thirds of two bars`() {
        val s = set(pattern("slow", 3, 4, listOf(0, 1, 2), span = OrbitSpan.TWO))
        val clip = OrbitClip.clip(s)
        assertEquals(2, clip.bars)
        val twoBars = 2 * Mpc3Clip.PULSES_PER_BAR
        assertEquals(listOf(0L, Math.round(twoBars / 3.0), Math.round(2 * twoBars / 3.0)), clip.notes.map { it.timePulses })
    }

    @Test
    fun `the clip counts bars of sixteen whatever the set's bar`() {
        // A 3/4 set: a free 16 against the 12-step bar meets every 48 steps, four bars of twelve — three of the clip's.
        val waltz = OrbitSet(listOf(pattern("four", 16, 1, listOf(0))), 120f, 48_000, lapSteps = 12)
        assertEquals(3, OrbitClip.bars(waltz))
        assertTrue(OrbitClip.countsDifferently(waltz))
        assertEquals(3, OrbitClip.clip(waltz).bars)
        // A 5/4 set with one free 20: twenty steps need a second bar of sixteen.
        val five = OrbitSet(listOf(pattern("five", 20, 1, listOf(0, 10))), 120f, 48_000, lapSteps = 20)
        assertEquals(2, OrbitClip.bars(five))
        assertEquals(listOf(0L, 10L * Mpc3Clip.PULSES_PER_16TH), OrbitClip.clip(five).notes.map { it.timePulses })
        // The default bar counts as it always did.
        assertTrue(!OrbitClip.countsDifferently(set(pattern("a", 16, 1, listOf(0)))))
    }

    @Test
    fun `muted rings and snip rings leave no notes`() {
        val s = set(
            pattern("off", 16, 1, listOf(0), engaged = false),
            Orbit("tape", 16, SnipOrbit("a.wav")),
            pattern("on", 16, 2, listOf(4)),
        )
        assertEquals(listOf(37), OrbitClip.clip(s).notes.map { it.note })
    }

    @Test
    fun `a cycle past 64 bars is refused in words, with the number`() {
        val s = set(pattern("a", 16, 1, listOf(0)), pattern("b", 17, 2, listOf(0)), pattern("c", 19, 3, listOf(0)))
        val refusal = OrbitClip.refusal(s)
        assertTrue(refusal != null && "323" in refusal, "expected the cycle length in the refusal: $refusal")
        val e = runCatching { OrbitClip.clip(s) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `saving replaces the last ORBIT clip and keeps every other groove`() {
        val dir = Files.createTempDirectory("orbitclip").toFile()
        val captured = Mpc3Clip("Break", 2, listOf(Mpc3Note(36, 0, 0.9f)))
        val progE = Mpc3Clip("Break E", 2, listOf(Mpc3Note(38, 240, 0.7f)))
        GrooveStore.save(dir, listOf(captured, progE, Mpc3Clip("ORBIT 4:5", 5, emptyList())))

        val written = OrbitClip.save(dir, set(pattern("four", 16, 1, listOf(0))))
        val stored = GrooveStore.load(dir)
        assertEquals(listOf("Break", "Break E", "ORBIT 1"), stored.map { it.name })
        assertEquals(written, stored.last())
        assertEquals(1, stored.count { OrbitClip.isOrbit(it) })
    }
}
