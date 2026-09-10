package com.snipsnap.loop

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitPresetsTest {

    private val kit = Kit(
        name = "Break Kit",
        pads = listOf(
            KitPad(1, "kick.wav", drumClass = DrumClass.KICK),
            KitPad(2, "snare.wav", drumClass = DrumClass.SNARE),
            KitPad(3, "hat.wav", drumClass = DrumClass.HAT_CLOSED),
            KitPad(4, "open.wav", drumClass = DrumClass.HAT_OPEN),
            KitPad(12, "shaker.wav", drumClass = DrumClass.PERC),
        ),
    )

    @Test
    fun `a classified kit lands each class on its own ring`() {
        val set = OrbitPresets.fromKit("Break Kit", kit, 92f, 48_000)
        assertEquals(4, set.orbits.size)
        val floor = set.orbits[0].content as PatternOrbit
        assertEquals("Break Kit", floor.kit)
        assertEquals(setOf(1, 2), floor.hits.map { it.slot }.toSet())
        assertEquals(setOf(3), (set.orbits[1].content as PatternOrbit).hits.map { it.slot }.toSet())
        assertEquals(setOf(12), (set.orbits[2].content as PatternOrbit).hits.map { it.slot }.toSet())
        assertEquals(setOf(4), (set.orbits[3].content as PatternOrbit).hits.map { it.slot }.toSet())
    }

    @Test
    fun `both modes are in the starter set and the cycle is fifteen bars`() {
        val set = OrbitPresets.fromKit("Break Kit", kit, 92f, 48_000)
        assertTrue(set.orbits.any { it.mode == OrbitMode.SAME_SPEED })
        assertTrue(set.orbits.any { it.mode == OrbitMode.SAME_LAP })
        // 16, 12, 20 (and the same-lap 3 counting as 16): LCM 240 steps.
        assertEquals(240L, OrbitClock.cycleSteps(set))
        assertEquals(15.0, OrbitClock.cycleBars(set))
    }

    @Test
    fun `an unclassified one-pad kit still gets a set on its only pad`() {
        val lone = Kit("Lone", listOf(KitPad(5, "thing.wav")))
        val set = OrbitPresets.fromKit("Lone", lone, 120f, 44_100)
        val slots = set.orbits.flatMap { (it.content as PatternOrbit).hits.map { h -> h.slot } }.toSet()
        assertEquals(setOf(5), slots)
    }

    @Test
    fun `an empty kit is refused in words`() {
        val e = runCatching { OrbitPresets.fromKit("Empty", Kit("Empty", emptyList()), 120f, 44_100) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
        assertTrue(e!!.message!!.contains("no pads"))
    }

    @Test
    fun `a snip ring is sized to the snip's own length`() {
        val set = OrbitPresets.fromKit("Break Kit", kit, 120f, 48_000)
        val fiveBeats = 20 * OrbitClock.stepFrames(set)
        val ring = OrbitPresets.snipRing(set, "TAPE", "snip_1.wav", fiveBeats)
        assertEquals(20, ring.steps)
        assertEquals(SnipOrbit("snip_1.wav"), ring.content)
    }
}
