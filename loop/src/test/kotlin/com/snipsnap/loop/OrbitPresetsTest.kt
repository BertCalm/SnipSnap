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
    fun `a classified kit gets one ring per instrument, each a single-pad voice`() {
        val set = OrbitPresets.fromKit("Break Kit", kit, 92f, 48_000)
        assertEquals(listOf("KICK", "SNARE", "HATS", "PERC", "THREE"), set.orbits.map { it.name })
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(12), listOf(4)), set.orbits.map { it.pads })
        assertEquals("Break Kit", (set.orbits[0].content as PatternOrbit).kit)
        for (ring in set.orbits) {
            val content = ring.content as PatternOrbit
            assertTrue(content.hits.all { it.slot == ring.pads.single() }, "${ring.name} strays off its pad")
        }
    }

    @Test
    fun `free and locked rings are both in the starter set and the cycle is fifteen bars`() {
        val set = OrbitPresets.fromKit("Break Kit", kit, 92f, 48_000)
        assertTrue(set.orbits.any { it.span == OrbitSpan.FREE })
        assertTrue(set.orbits.any { it.span == OrbitSpan.ONE })
        // 16, 16, 12, 20 (and the locked 3 counting as 16): LCM 240 steps.
        assertEquals(240L, OrbitClock.cycleSteps(set))
        assertEquals(15.0, OrbitClock.cycleBars(set))
    }

    @Test
    fun `a kit with tonal pads gets a bass ring whose voice is the tonal pads low to high`() {
        val melodic = Kit(
            "Keys",
            kit.pads + listOf(KitPad(9, "c.wav", drumClass = DrumClass.TONAL), KitPad(11, "g.wav", drumClass = DrumClass.TONAL), KitPad(10, "e.wav", drumClass = DrumClass.TONAL)),
        )
        val set = OrbitPresets.fromKit("Keys", melodic, 92f, 48_000)
        val bass = set.orbits.last()
        assertEquals("BASS", bass.name)
        assertEquals(listOf(9, 10, 11), bass.pads)
        assertEquals(20, bass.steps)
        val notes = (bass.content as PatternOrbit).hits.map { it.slot }.toSet()
        assertTrue(notes.size >= 2, "a bass line uses more than one note: $notes")
        assertTrue(notes.all { it in bass.pads })
    }

    @Test
    fun `two tonal pads is not a line - no bass ring`() {
        val two = Kit("Two", kit.pads + listOf(KitPad(9, "c.wav", drumClass = DrumClass.TONAL), KitPad(10, "e.wav", drumClass = DrumClass.TONAL)))
        assertTrue(OrbitPresets.fromKit("Two", two, 92f, 48_000).orbits.none { it.name == "BASS" })
    }

    @Test
    fun `bassRing itself refuses two pads - the same rule the presets apply`() {
        val e = runCatching { OrbitPresets.bassRing("kit", listOf(9, 10)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
        assertEquals(listOf(9, 10, 11), OrbitPresets.bassRing("kit", listOf(9, 10, 11)).pads)
    }

    @Test
    fun `an unclassified one-pad kit still gets a ring on its only pad`() {
        val lone = Kit("Lone", listOf(KitPad(5, "thing.wav")))
        val set = OrbitPresets.fromKit("Lone", lone, 120f, 44_100)
        assertEquals(1, set.orbits.size)
        assertEquals(listOf(5), set.orbits[0].pads)
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
