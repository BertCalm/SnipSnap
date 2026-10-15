package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTest {

    @Test
    fun `the app opens on the shelf, which has no stages`() {
        val nav = Navigation()
        assertEquals(Location(Place.SHELF, null), nav.location)
        assertEquals(emptyList(), Stage.of(Place.SHELF))
        assertEquals(emptyList(), Stage.of(Place.MAKE))
        assertEquals(listOf(Stage.DECK, Stage.CUT), Stage.of(Place.TAPE))
        assertEquals(listOf(Stage.PADS, Stage.BEAT, Stage.OUT), Stage.of(Place.PROJECT))
    }

    @Test
    fun `a place opens on its first stage, and later on the stage it was left on`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        assertEquals(Stage.PADS, nav.location.stage, "PROJECT opens on PADS")
        nav.stage(Stage.BEAT)
        nav.go(Place.SHELF)
        nav.go(Place.PROJECT)
        assertEquals(Stage.BEAT, nav.location.stage, "back where you were")
    }

    @Test
    fun `a stage belongs to its place`() {
        val nav = Navigation()
        nav.go(Place.TAPE)
        assertFailsWith<IllegalArgumentException> { nav.stage(Stage.PADS) }
        assertFailsWith<IllegalArgumentException> { Location(Place.SHELF, Stage.PADS) }
    }

    @Test
    fun `back pops a bench, then a stage, then a place, in that order`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.stage(Stage.BEAT)
        nav.open(Bench.ORBIT)
        assertEquals("◄ BEAT", nav.backChip)

        assertTrue(nav.back())
        assertEquals(Location(Place.PROJECT, Stage.BEAT), nav.location, "the bench closed")
        assertNull(nav.backChip)
        assertTrue(nav.back())
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.location, "the stage change undone")
        assertTrue(nav.back())
        assertEquals(Location(Place.SHELF, null), nav.location, "the place change undone")
        assertFalse(nav.back(), "nothing left: the app's cue to leave")
    }

    @Test
    fun `back from inside PROJECT lands on SHELF only when SHELF is where you came from`() {
        val nav = Navigation()
        nav.go(Place.MAKE)
        nav.go(Place.PROJECT)
        nav.stage(Stage.OUT)
        nav.back()
        nav.back()
        assertEquals(Place.MAKE, nav.location.place, "J11: Back is back, not 'go to the shelf'")
    }

    @Test
    fun `benches stack and a back chip closes the top one`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.open(Bench.PAD_SHEET)
        nav.open(Bench.SPLICE)
        assertEquals(listOf(Bench.PAD_SHEET, Bench.SPLICE), nav.location.benches)
        assertEquals("◄ PAD", nav.backChip)
        nav.closeBench()
        assertEquals(listOf(Bench.PAD_SHEET), nav.location.benches)
        assertEquals("◄ PADS", nav.backChip)
        nav.closeBench()
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.location)
        assertEquals(nav.location, nav.closeBench(), "closing nothing is nothing")
    }

    @Test
    fun `tapping the place you are on closes its benches and is otherwise a no-op`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        val before = nav.taps
        nav.go(Place.PROJECT)
        assertEquals(before, nav.taps, "already there")
        nav.open(Bench.VERSIONS)
        nav.go(Place.PROJECT)
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.location, "the place row is a way home")
    }

    @Test
    fun `a door lands where it says and is a normal step back`() {
        val nav = Navigation()
        nav.go(Place.TAPE)
        nav.stage(Stage.CUT)
        val out = Door(Places.DOOR_OUT, Location(Place.PROJECT, Stage.OUT))
        nav.take(out)
        assertEquals(Location(Place.PROJECT, Stage.OUT), nav.location)
        nav.back()
        assertEquals(Location(Place.TAPE, Stage.CUT), nav.location)
    }

    @Test
    fun `per-place state survives a stage switch and a trip to another place`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.pads.bank = 1
        nav.pads.selectedSlot = 18
        nav.pads.openBox = PadSheetBoxes.Box.MUTATE
        nav.stage(Stage.BEAT)
        nav.beat.program = "SWING"
        nav.beat.swingPercent = 62
        nav.go(Place.MAKE)
        nav.make.settings(Bench.SYNTH)["engine"] = "RESIN"
        nav.go(Place.PROJECT)
        nav.stage(Stage.PADS)

        assertEquals(1, nav.pads.bank)
        assertEquals(18, nav.pads.selectedSlot)
        assertEquals(PadSheetBoxes.Box.MUTATE, nav.pads.openBox)
        assertEquals("SWING", nav.beat.program)
        assertEquals(62, nav.beat.swingPercent)
        assertEquals("RESIN", nav.make.settings(Bench.SYNTH)["engine"], "the lab remembers")
    }

    @Test
    fun `every bench is filed under a real place and names where it returns to`() {
        for (bench in Bench.entries) {
            assertTrue(bench.backChip.startsWith("◄ "), "${bench.name}: a back chip points back")
            assertTrue(bench.label.isNotBlank())
        }
        assertEquals(setOf(Place.PROJECT), Bench.entries.filter { it.backChip == "◄ BEAT" }.map { it.home }.toSet())
        assertEquals(setOf(Place.SHELF), Bench.entries.filter { it.backChip == "◄ SHELF" }.map { it.home }.toSet())
    }

    @Test
    fun `the history is bounded so a long session cannot grow it without end`() {
        val nav = Navigation()
        repeat(500) { i -> nav.go(if (i % 2 == 0) Place.TAPE else Place.MAKE) }
        var steps = 0
        while (nav.back()) steps++
        assertTrue(steps in 1..64, "popped $steps")
    }
}
