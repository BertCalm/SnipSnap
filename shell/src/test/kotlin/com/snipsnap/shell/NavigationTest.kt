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
    fun `the gear's benches name where back goes, not BACK`() {
        val nav = Navigation()
        nav.open(Bench.SETUP)
        assertEquals("◄ SHELF", nav.backChip, "the shelf has no stage: its place")
        nav.closeBench()
        nav.go(Place.PROJECT)
        nav.stage(Stage.BEAT)
        nav.open(Bench.HELP)
        assertEquals("◄ BEAT", nav.backChip, "a stage is a more exact place")
        nav.closeBench()
        nav.open(Bench.PAD_SHEET)
        nav.open(Bench.HELP)
        assertEquals("◄ PAD", nav.backChip, "under a bench: the bench's short name, one rule for every bench")
        nav.closeBench()
        nav.closeBench()
        nav.open(Bench.SETUP)
        nav.open(Bench.HELP)
        assertEquals("◄ SETUP", nav.backChip, "HELP from SETUP goes back to SETUP")
        nav.closeBench()
        nav.closeBench()
        nav.open(Bench.MIDI)
        assertEquals("◄ BEAT", nav.backChip, "MIDI is behind the gear too: it names where back goes")
        nav.open(Bench.HELP)
        assertEquals("◄ MIDI", nav.backChip, "HELP from MIDI goes back to MIDI")
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
        assertTrue(nav.back(), "Back goes to before the pad sheet was opened")
        assertEquals(Location(Place.SHELF), nav.location, "and not to the sheet, the splice, or PADS again")
        assertFalse(nav.canGoBack)
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
    fun `a landing goes straight where it says, benches included, and is a normal step back`() {
        val nav = Navigation()
        assertFalse(nav.canGoBack, "a fresh session has nowhere to go back to")
        nav.goTo(Location(Place.PROJECT, Stage.BEAT, listOf(Bench.ORBIT)))
        assertEquals(Location(Place.PROJECT, Stage.BEAT, listOf(Bench.ORBIT)), nav.location)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(Location(Place.SHELF), nav.location)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back(), "nothing left to pop: the app's cue to leave")
    }

    // Closing a screen steps back (PR 3a): closeBench never adds history.

    private fun Navigation.walkBack(): List<Location> {
        val seen = mutableListOf(location)
        while (back()) seen += location
        return seen
    }

    @Test
    fun `closing a bench steps back, so Back does not reopen it`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.stage(Stage.BEAT)
        nav.open(Bench.ORBIT)
        val before = nav.taps

        assertEquals(Location(Place.PROJECT, Stage.BEAT), nav.closeBench())
        assertEquals(before + 1, nav.taps, "closing is still one navigation")

        assertTrue(nav.back())
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.location, "Back undoes the stage change, as it did before ORBIT was opened")
        val rest = nav.walkBack()
        assertTrue(rest.none { it.benches.isNotEmpty() }, "ORBIT is nowhere in the history: $rest")
    }

    @Test
    fun `closing a landed bench replaces it in place, and Back goes to where the user came from`() {
        val nav = Navigation()
        nav.goTo(Location(Place.SHELF))
        nav.goTo(Location(Place.MAKE, benches = listOf(Bench.SYNTH)))

        assertEquals(Location(Place.MAKE), nav.closeBench())
        assertTrue(nav.back())
        assertEquals(Location(Place.SHELF), nav.location, "the shelf it was landed from, not the lab")
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `stacked benches close one at a time and Back never reopens any of them`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.open(Bench.PAD_SHEET)
        nav.open(Bench.SPLICE)

        assertEquals(Location(Place.PROJECT, Stage.PADS, listOf(Bench.PAD_SHEET)), nav.closeBench())
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.closeBench())

        val rest = nav.walkBack()
        assertEquals(listOf(Location(Place.PROJECT, Stage.PADS), Location(Place.SHELF)), rest, "PADS, then where it was left from")
    }

    @Test
    fun `opening and closing a bench over and over leaves the history as it was`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        repeat(200) {
            nav.open(Bench.PAD_SHEET)
            nav.closeBench()
        }
        assertEquals(listOf(Location(Place.PROJECT, Stage.PADS), Location(Place.SHELF)), nav.walkBack())
    }

    @Test
    fun `closing a bench that was landed on the stage the place is on keeps the stage for later`() {
        val nav = Navigation()
        nav.goTo(Location(Place.PROJECT, Stage.OUT, listOf(Bench.HELP)))
        nav.closeBench()
        nav.go(Place.SHELF)
        nav.go(Place.PROJECT)
        assertEquals(Stage.OUT, nav.location.stage, "the place still opens on the stage it was last on")
    }

    @Test
    fun `a back chip is null on a bare stage, and a closeBench on nothing moves nothing`() {
        val nav = Navigation()
        assertNull(nav.backChip)
        val before = nav.taps
        nav.closeBench()
        assertEquals(before, nav.taps)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `a back chip names what closing the bench reaches`() {
        fun chip(place: Place, stage: Stage?, vararg benches: Bench) =
            Navigation(Location(place, stage, benches.toList())).backChip

        assertEquals("◄ PADS", chip(Place.PROJECT, Stage.PADS, Bench.SNIPS), "SNIPS over PADS")
        assertEquals("◄ DECK", chip(Place.TAPE, Stage.DECK, Bench.SNIPS), "SNIPS over DECK")
        assertEquals("◄ LOOP", chip(Place.PROJECT, Stage.BEAT, Bench.LOOP, Bench.SNIPS), "SNIPS over LOOP, which is over BEAT")
        assertEquals("◄ INSTRUMENTS", chip(Place.MAKE, null, Bench.INSTRUMENTS, Bench.KEYS), "KEYS over INSTRUMENTS")
        assertEquals("◄ BEAT", chip(Place.PROJECT, Stage.BEAT, Bench.ORBIT), "ORBIT over BEAT")
        assertEquals("◄ PAD", chip(Place.PROJECT, Stage.PADS, Bench.PAD_SHEET, Bench.SPLICE), "SPLICE over PAD_SHEET")
        assertEquals("◄ PADS", chip(Place.PROJECT, Stage.PADS, Bench.SETUP), "SETUP over PADS")
        assertEquals("◄ PAD", chip(Place.PROJECT, Stage.PADS, Bench.PAD_SHEET, Bench.SETUP), "SETUP over PAD_SHEET")
        assertEquals("◄ SETUP", chip(Place.SHELF, null, Bench.SETUP, Bench.HELP), "HELP over SETUP")

        assertEquals("◄ MAKE", chip(Place.MAKE, null, Bench.SYNTH), "a lab on bare MAKE: the place, which has no stage")
        assertEquals("◄ SHELF", chip(Place.SHELF, null, Bench.SNIPS), "SNIPS over the shelf")
        assertEquals("◄ STACK", chip(Place.PROJECT, Stage.PADS, Bench.STACK, Bench.HELP), "a long label gives its short name")
        assertEquals("◄ VERSIONS", chip(Place.PROJECT, Stage.PADS, Bench.VERSIONS, Bench.HELP), "a long label gives its short name")
        assertNull(chip(Place.PROJECT, Stage.PADS), "a bare stage has nothing to go back from")
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
    fun `every bench is filed under a real place and has a short name for a back chip to use`() {
        val longest = Bench.INSTRUMENTS.backName.length
        for (bench in Bench.entries) {
            assertTrue(bench.label.isNotBlank(), "${bench.name}: a label")
            assertTrue(bench.backName.isNotBlank(), "${bench.name}: a name for the chip over it")
            assertTrue(bench.backName.length <= longest, "${bench.name}: '${bench.backName}' is longer than the chips were drawn for")
            // Whatever is opened over it, the chip over this bench reads its backName.
            val over = Navigation(Location(Place.PROJECT, Stage.PADS, listOf(bench, Bench.HELP)))
            assertEquals("◄ ${bench.backName}", over.backChip, "${bench.name}: the chip of what is opened over it")
        }
    }

    @Test
    fun `a bench opened alone over its own place names the stage showing, or the place`() {
        for (bench in Bench.entries) {
            val stage = Stage.defaultFor(bench.home)
            val nav = Navigation(Location(bench.home, stage, listOf(bench)))
            assertEquals("◄ ${stage?.label ?: bench.home.label}", nav.backChip, bench.name)
        }
    }

    @Test
    fun `the history is bounded so a long session cannot grow it without end`() {
        val nav = Navigation()
        repeat(500) { i -> nav.go(if (i % 2 == 0) Place.TAPE else Place.MAKE) }
        var steps = 0
        while (nav.back()) steps++
        assertTrue(steps in 1..64, "popped $steps")
    }

    // backOutOf: a page that turned out to have nothing to show (a starter kit
    // that failed to render, with its placeholders on screen).

    private val kitPage = Location(Place.PROJECT, Stage.PADS)
    private val shelfPage = Location(Place.SHELF)

    @Test
    fun `backing out of the page the user is still on goes back, and leaves no entry for it`() {
        val nav = Navigation()
        nav.goTo(kitPage)

        assertTrue(nav.backOutOf(kitPage, shelfPage), "it moved the user")
        assertEquals(shelfPage, nav.location, "to the shelf the page was asked for from")
        assertFalse(nav.canGoBack, "and the page is not left in the history: Back from the shelf leaves the app")
    }

    @Test
    fun `a user who has moved on is left where they are`() {
        val nav = Navigation()
        nav.goTo(kitPage)
        nav.go(Place.TAPE)
        val tapsBefore = nav.taps

        assertFalse(nav.backOutOf(kitPage, shelfPage), "the page is not what is on screen")
        assertEquals(Place.TAPE, nav.location.place, "TAPE is where they chose to be")
        assertEquals(tapsBefore, nav.taps, "and nothing moved")
        assertTrue(nav.canGoBack)
    }

    @Test
    fun `when Back does not reach the shelf it is landed on, with the page still out of the history`() {
        val nav = Navigation()
        nav.go(Place.TAPE)
        nav.goTo(kitPage)

        assertTrue(nav.backOutOf(kitPage, shelfPage))
        assertEquals(shelfPage, nav.location)
        // Back from here walks what was there before, and the page is not in it.
        val seen = mutableListOf(nav.location)
        while (nav.back()) seen += nav.location
        assertFalse(kitPage in seen, "the page is gone from the history: $seen")
    }
}
