package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.loop.OrbitSection
import com.snipsnap.loop.OrbitSet
import com.snipsnap.loop.OrbitSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What ORBIT's folds say closed, and what its pickers and SURFACE's BARS sheet say under each row. */
class OrbitPanelsTest {

    private fun section(name: String, bars: Int) = OrbitSection(name, bars, emptySet())

    @Test
    fun `THE SET's closed line is the bar, the swing and the tempo`() {
        assertEquals("4/4 · STRAIGHT · 92 BPM", OrbitPanels.setSummary(16, OrbitSet.STRAIGHT_SWING, 92f))
        assertEquals("3/4 · SWING 66 · 120 BPM", OrbitPanels.setSummary(12, 66, 120f))
        assertEquals("5/16 · STRAIGHT · 100 BPM", OrbitPanels.setSummary(5, OrbitSet.STRAIGHT_SWING, 100f))
    }

    @Test
    fun `SECTIONS says there is no plan, or the plan's length and each section`() {
        assertEquals("NO ARRANGEMENT · EVERY RING TURNS FOREVER", OrbitPanels.sectionsSummary(emptyList()))
        assertEquals("12 BARS · A 4 · B 8", OrbitPanels.sectionsSummary(listOf(section("A", 4), section("B", 8))))
        assertEquals("1 BAR · A 1", OrbitPanels.sectionsSummary(listOf(section("A", 1))))
    }

    @Test
    fun `ARP says its shape, its octaves unless it is a chord, and its gate`() {
        assertEquals("UP · 1 OCT · GATE 100%", OrbitPanels.arpSummary("UP", chord = false, octaves = 1, gate = 1f))
        assertEquals("UP-DOWN · 3 OCT · GATE 50%", OrbitPanels.arpSummary("UP-DOWN", chord = false, octaves = 3, gate = 0.5f))
        assertEquals("CHORD · GATE 100%", OrbitPanels.arpSummary("CHORD", chord = true, octaves = 2, gate = 1f))
    }

    @Test
    fun `ONE TURN OUT says how long a turn is and names only the ways out that are open`() {
        assertEquals("ONE TURN IS 4 BARS", OrbitPanels.outSummary("4 BARS"))
        assertEquals("BOUNCE TO SNIPS · CLIP TO KIT · GROOVE TO RINGS", OrbitPanels.outAlso(canBounce = true, canClip = true))
        assertEquals("CLIP TO KIT · GROOVE TO RINGS", OrbitPanels.outAlso(canBounce = false, canClip = true))
        assertEquals("GROOVE TO RINGS", OrbitPanels.outAlso(canBounce = false, canClip = false))
    }

    @Test
    fun `no closed line says untouched, and none is blank`() {
        val lines = listOf(
            OrbitPanels.setSummary(16, OrbitSet.STRAIGHT_SWING, 92f),
            OrbitPanels.sectionsSummary(emptyList()),
            OrbitPanels.arpSummary("UP", false, 1, 1f),
            OrbitPanels.outSummary("1 BAR"),
            OrbitPanels.outAlso(false, false),
        )
        for (line in lines) {
            assertTrue(line.isNotBlank())
            assertFalse("UNTOUCHED" in line.uppercase(), line)
        }
    }

    @Test
    fun `the steps picker gives beats for fours, drift for odds, and nothing else`() {
        assertEquals("A BAR · 4 BEATS", OrbitPanels.stepsNote(16))
        assertEquals("ONE BEAT", OrbitPanels.stepsNote(4))
        assertEquals("5 BEATS", OrbitPanels.stepsNote(20))
        assertEquals("3 BEATS", OrbitPanels.stepsNote(12))
        assertEquals("ODD · DRIFTS FURTHEST", OrbitPanels.stepsNote(7))
        assertNull(OrbitPanels.stepsNote(6))
        assertEquals("3 STEPS", OrbitPanels.stepsName(3))
    }

    @Test
    fun `every span has a note, and a free ring is the one that is as long as its steps`() {
        for (span in OrbitSpan.entries) assertTrue(OrbitPanels.spanNote(span).isNotBlank())
        assertEquals("AS LONG AS ITS STEPS", OrbitPanels.spanNote(OrbitSpan.FREE))
        assertEquals("ONE TURN IS 2 BARS WHATEVER THE STEPS", OrbitPanels.spanNote(OrbitSpan.TWO))
    }

    @Test
    fun `spread offers one to twelve, never more than the steps, and names the two it knows`() {
        assertEquals(1..8, OrbitPanels.spreadCounts(8))
        assertEquals(1..12, OrbitPanels.spreadCounts(32))
        assertEquals("THE TRESILLO", OrbitPanels.spreadNote(3, 8))
        assertEquals("THE CINQUILLO", OrbitPanels.spreadNote(5, 8))
        assertNull(OrbitPanels.spreadNote(3, 16), "three round sixteen is not the tresillo")
        assertEquals("1 HIT", OrbitPanels.spreadName(1))
        assertEquals("SPREAD HOW MANY HITS ROUND 1 STEP?", OrbitPanels.spreadTitle(1))
    }

    @Test
    fun `the BARS sheet says the seconds a length comes to, capped at what a print can hold`() {
        assertEquals("NO FIXED LENGTH · UP TO 60 SECONDS", PrintLength.note(0, 120f, 60f))
        assertEquals("2 SECONDS AT 120 BPM", PrintLength.note(1, 120f, 60f))
        assertEquals("16 SECONDS AT 120 BPM", PrintLength.note(8, 120f, 60f))
        assertEquals("32 SECONDS AT 60 BPM", PrintLength.note(8, 60f, 60f))
        assertEquals("60 SECONDS AT 30 BPM", PrintLength.note(8, 30f, 60f), "64 seconds is more than a print holds")
    }

    @Test
    fun `a sheet opener ends in the arrow and a chosen row is marked`() {
        assertEquals("2 BARS ▾", Places.opener("2 BARS"))
        assertEquals("● 2 BARS", Places.pickedRow("2 BARS", true))
        assertEquals("2 BARS", Places.pickedRow("2 BARS", false))
    }

    @Test
    fun `YOURS cycles only while there are three or fewer, because more than three is a sheet`() {
        assertTrue(GrooveEdit.CYCLE_UP_TO <= 3)
    }
}
