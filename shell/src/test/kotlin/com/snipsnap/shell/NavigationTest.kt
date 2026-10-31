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
        assertFalse(nav.canGoBack, "open then close from a fresh root leaves nothing to go back to")
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
    fun `Back from a pad tool lands on the pad editor, and the editor then closes to the grid`() {
        for (tool in listOf(Bench.GRAIN_FIELD, Bench.SPLICE, Bench.STACK)) {
            val nav = Navigation()
            nav.go(Place.PROJECT)
            nav.open(Bench.PAD_SHEET)
            nav.open(tool)
            assertEquals("◄ PAD", nav.backChip, "$tool over the editor names the editor")

            assertEquals(
                Location(Place.PROJECT, Stage.PADS, listOf(Bench.PAD_SHEET)),
                nav.closeBench(),
                "the chip or system Back from $tool lands on the pad editor, not the grid",
            )
            assertEquals("◄ PADS", nav.backChip, "and the editor over the grid names the grid")
            assertEquals(Location(Place.PROJECT, Stage.PADS), nav.closeBench(), "the editor's own Back lands on the grid")
            assertEquals(
                listOf(Location(Place.PROJECT, Stage.PADS), Location(Place.SHELF)),
                nav.walkBack(),
                "and the opening and closing left nothing in the history for a later Back to reopen",
            )
        }
    }

    @Test
    fun `AUDITION leaves the pad editor in the history, and Back from the stage it lands on is the editor`() {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.open(Bench.PAD_SHEET)
        // onAudition arms the comparison and lands on GROOVE, which is BEAT: a stage change, with the editor
        // as the location the history keeps. The editor is not closed first.
        nav.goTo(Location(Place.PROJECT, Stage.BEAT))
        assertEquals(Location(Place.PROJECT, Stage.BEAT), nav.location, "GROOVE shows no bench")

        assertTrue(nav.back())
        assertEquals(
            Location(Place.PROJECT, Stage.PADS, listOf(Bench.PAD_SHEET)),
            nav.location,
            "system Back from GROOVE is the pad editor",
        )
        assertEquals("◄ PADS", nav.backChip)
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.closeBench(), "and the editor closes to the grid it was opened over")
    }

    @Test
    fun `a bench opened again over itself stacks a copy, so what moves the pad editor between pads must not open it`() {
        // The editor's pad arrows (and the gathering hop) move it from pad to pad with App's
        // retargetPadSheet, which sets the pad and never calls open. The model has no retarget
        // to test, because it needs none: this is why. Had the arrows opened the editor each
        // time, every pad stepped through would be one more PAD_SHEET on the stack and one more
        // Back to get out. ConventionTest pins that retargetPadSheet never opens a bench.
        val nav = Navigation()
        nav.go(Place.PROJECT)
        nav.open(Bench.PAD_SHEET)
        nav.open(Bench.PAD_SHEET)
        assertEquals(listOf(Bench.PAD_SHEET, Bench.PAD_SHEET), nav.location.benches)

        // With the pad arrows retargeting instead, the editor is one opening and one step out.
        val once = Navigation()
        once.go(Place.PROJECT)
        once.open(Bench.PAD_SHEET)
        assertEquals(Location(Place.PROJECT, Stage.PADS), once.closeBench(), "one Back, not one per pad")
        assertEquals(listOf(Location(Place.PROJECT, Stage.PADS), Location(Place.SHELF)), once.walkBack())
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
    fun `closing a bench records the stage under it, so the place reopens there`() {
        val nav = Navigation()
        nav.goTo(Location(Place.PROJECT, Stage.PADS, listOf(Bench.PAD_SHEET)))
        nav.stage(Stage.BEAT)
        // Back reaches the landed sheet without touching the stage the place remembers: that is still BEAT.
        assertTrue(nav.back())
        assertEquals(Location(Place.PROJECT, Stage.PADS, listOf(Bench.PAD_SHEET)), nav.location)

        // So only closing the sheet can put PADS back as the stage to reopen on.
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.closeBench())
        nav.go(Place.SHELF)
        nav.go(Place.PROJECT)
        assertEquals(Stage.PADS, nav.location.stage, "the place reopens on the stage the closed bench was over, not BEAT")
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
        assertEquals("◄ PADS", chip(Place.PROJECT, Stage.PADS, Bench.SPLIT), "SPLIT over PADS")
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

    // Hand-offs open over where the user is (PR 3b): the chip, system Back and the screen's own
    // close all return to the same place, and the chip names it.

    /** Builds a location with [build], then closes the top bench by the chip in one session and by system Back in another. */
    private fun assertChipAndBackAgree(expected: Location, expectedChip: String, build: Navigation.() -> Unit) {
        val viaChip = Navigation().apply(build)
        assertEquals(expectedChip, viaChip.backChip)
        assertEquals(expected, viaChip.closeBench(), "the chip")
        val viaBack = Navigation().apply(build)
        assertTrue(viaBack.back())
        assertEquals(expected, viaBack.location, "system Back")
    }

    @Test
    fun `ORBIT opened over SNAP returns to SNAP by the chip and by Back, not to a BEAT nobody visited`() {
        assertChipAndBackAgree(Location(Place.MAKE, null, listOf(Bench.SNAP)), "◄ SNAP") {
            go(Place.MAKE)
            open(Bench.SNAP)
            open(Bench.ORBIT)
        }
    }

    @Test
    fun `SURFACE opened over SYNTH returns to SYNTH by the chip and by Back`() {
        assertChipAndBackAgree(Location(Place.MAKE, null, listOf(Bench.SYNTH)), "◄ SYNTH") {
            go(Place.MAKE)
            open(Bench.SYNTH)
            open(Bench.SURFACE)
        }
    }

    @Test
    fun `BIN opened over SNIPS returns to SNIPS, and SNIPS then names the stage it is over`() {
        val over = Location(Place.PROJECT, Stage.PADS, listOf(Bench.SNIPS))
        assertChipAndBackAgree(over, "◄ SNIPS") {
            go(Place.PROJECT)
            open(Bench.SNIPS)
            open(Bench.BIN)
        }
        val nav = Navigation().apply {
            go(Place.PROJECT)
            open(Bench.SNIPS)
            open(Bench.BIN)
            closeBench()
        }
        assertEquals("◄ PADS", nav.backChip, "SNIPS over PADS, not the shelf it is usually reached from")
    }

    // Room for a screen that opens after an await: one already showing is never opened twice,
    // and at most two screens (the gear's three not counted) stack above a stage.

    /** PROJECT ▸ BEAT with [benches] opened one over the other, so the history is what those taps would have left. */
    private fun beatWith(vararg benches: Bench) = Navigation().apply {
        go(Place.PROJECT)
        stage(Stage.BEAT)
        benches.forEach { open(it) }
    }

    @Test
    fun `with two screens showing, room for a third closes the top one and says to open`() {
        val nav = beatWith(Bench.LOOP, Bench.SNIPS)

        assertTrue(nav.roomFor(Bench.XRAY))
        assertEquals(listOf(Bench.LOOP), nav.location.benches, "the top screen closed; the one under it stays")

        nav.open(Bench.XRAY)
        assertEquals("◄ LOOP", nav.backChip)
        assertEquals(beatWith(Bench.LOOP, Bench.XRAY).walkBack(), nav.walkBack(), "the history is as if SNIPS had never been opened")
    }

    @Test
    fun `a gear bench over two screens closes, and then one screen closes, because the gear does not count`() {
        val nav = beatWith(Bench.LOOP, Bench.SNIPS, Bench.HELP)

        assertTrue(nav.roomFor(Bench.XRAY))
        assertEquals(listOf(Bench.LOOP), nav.location.benches, "HELP closed (two screens were still showing) and then SNIPS")
    }

    @Test
    fun `a gear bench over one screen leaves room, so nothing closes`() {
        val nav = beatWith(Bench.LOOP, Bench.MIDI)
        val taps = nav.taps

        assertTrue(nav.roomFor(Bench.XRAY))
        assertEquals(listOf(Bench.LOOP, Bench.MIDI), nav.location.benches)
        assertEquals(taps, nav.taps, "nothing closed")
    }

    @Test
    fun `when there is room already, nothing closes and the answer is to open`() {
        val bare = beatWith()
        val bareTaps = bare.taps
        assertTrue(bare.roomFor(Bench.ORBIT))
        assertEquals(Location(Place.PROJECT, Stage.BEAT), bare.location)
        assertEquals(bareTaps, bare.taps)

        val one = beatWith(Bench.SNIPS)
        val oneTaps = one.taps
        assertTrue(one.roomFor(Bench.XRAY))
        assertEquals(listOf(Bench.SNIPS), one.location.benches)
        assertEquals(oneTaps, one.taps)
    }

    @Test
    fun `a screen that is already on top is not opened again, and nothing moves`() {
        val nav = beatWith(Bench.ORBIT)
        val location = nav.location
        val taps = nav.taps

        assertFalse(nav.roomFor(Bench.ORBIT))
        assertEquals(location, nav.location)
        assertEquals(taps, nav.taps, "no navigation happened")
        assertEquals(beatWith(Bench.ORBIT).walkBack(), nav.walkBack(), "and no history was pushed")
    }

    @Test
    fun `a screen showing lower down is brought to the top by closing what is over it, and not opened again`() {
        val nav = beatWith(Bench.ORBIT, Bench.HELP)

        assertFalse(nav.roomFor(Bench.ORBIT))
        assertEquals(listOf(Bench.ORBIT), nav.location.benches, "HELP closed; ORBIT is on top")
        assertEquals("◄ BEAT", nav.backChip, "ORBIT's chip names the stage under it, not itself")
        assertEquals(beatWith(Bench.ORBIT).walkBack(), nav.walkBack(), "closing HELP took it off the history")
    }

    @Test
    fun `a screen showing under a screen and a gear bench is brought to the top too`() {
        val nav = beatWith(Bench.LOOP, Bench.SNIPS, Bench.SETUP)

        assertFalse(nav.roomFor(Bench.LOOP))
        assertEquals(listOf(Bench.LOOP), nav.location.benches)
    }

    @Test
    fun `a screen the user leaves for another place is restored by Back with its screen on top`() {
        // X-RAY (its reading kept by the app) and DOUBLES (whose GO lands on a pad) both rely on this.
        val nav = Navigation()
        nav.open(Bench.XRAY)
        nav.go(Place.TAPE)
        assertTrue(nav.back())
        assertEquals(Location(Place.SHELF, null, listOf(Bench.XRAY)), nav.location)

        val doubles = Navigation()
        doubles.open(Bench.DOUBLES)
        doubles.goTo(Location(Place.PROJECT, Stage.PADS))
        assertTrue(doubles.back())
        assertEquals(Location(Place.SHELF, null, listOf(Bench.DOUBLES)), doubles.location, "Back from the pad lands on the list GO came from")
        assertEquals("◄ SHELF", doubles.backChip)
    }

    // The kit-bound screens (PR 3c): VERSIONS and PAD CAPTURE over PADS, SONG over BEAT. The app closes one
    // that no longer fits the open kit by `closeBench` on arrival, which is only a step back if the model
    // leaves nothing behind.

    @Test
    fun `VERSIONS opened over PADS returns to PADS by the chip and by Back, and names PADS`() {
        assertChipAndBackAgree(Location(Place.PROJECT, Stage.PADS), "◄ PADS") {
            go(Place.PROJECT)
            open(Bench.VERSIONS)
        }
    }

    @Test
    fun `PAD CAPTURE closed by its chip leaves nothing for Back to find, and names PADS`() {
        val nav = Navigation().apply { go(Place.PROJECT) }
        val before = Navigation().apply { go(Place.PROJECT) }.walkBack()

        nav.open(Bench.PAD_CAPTURE)
        assertEquals("◄ PADS", nav.backChip)
        assertEquals(Location(Place.PROJECT, Stage.PADS), nav.closeBench())

        assertEquals(before, nav.walkBack(), "PAD CAPTURE was opened and closed, so the history is as if it never was")
    }

    @Test
    fun `SONG opened over BEAT survives a place tap, and Back restores it with its chip still naming BEAT`() {
        val nav = beatWith(Bench.SONG)
        assertEquals("◄ BEAT", nav.backChip)

        nav.go(Place.SHELF)
        assertNull(nav.backChip, "a bare place has nothing to go back from")
        assertTrue(nav.back())

        assertEquals(Location(Place.PROJECT, Stage.BEAT, listOf(Bench.SONG)), nav.location, "Back restores SONG itself, not the bare BEAT under it")
        assertEquals("◄ BEAT", nav.backChip)
    }

    @Test
    fun `a kit-bound screen restored by Back and closed at once leaves the stage it was over, with no trace`() {
        // What the app does on arrival at a VERSIONS, PAD CAPTURE or SONG opened for a kit that is not the open one.
        val songTrip = beatWith(Bench.SONG).apply {
            go(Place.SHELF)
            back()
        }
        assertEquals(Location(Place.PROJECT, Stage.BEAT, listOf(Bench.SONG)), songTrip.location)
        assertEquals(Location(Place.PROJECT, Stage.BEAT), songTrip.closeBench())
        assertEquals(beatWith().walkBack(), songTrip.walkBack(), "SONG left the history as it would be had it never been opened")

        val versionsTrip = Navigation().apply {
            go(Place.PROJECT)
            open(Bench.VERSIONS)
            go(Place.SHELF)
            back()
        }
        assertEquals(Location(Place.PROJECT, Stage.PADS, listOf(Bench.VERSIONS)), versionsTrip.location)
        assertEquals(Location(Place.PROJECT, Stage.PADS), versionsTrip.closeBench())
        assertEquals(Navigation().apply { go(Place.PROJECT) }.walkBack(), versionsTrip.walkBack())
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
    fun `every bench has a label and a back name of at most 11 characters, which the chip over it reads`() {
        for (bench in Bench.entries) {
            assertTrue(bench.label.isNotBlank(), "${bench.name}: a label")
            assertTrue(bench.backName.isNotBlank(), "${bench.name}: a name for the chip over it")
            // 11 is what a back chip holds beside the title ("INSTRUMENTS" is the longest the chips were drawn for).
            assertTrue(bench.backName.length <= 11, "${bench.name}: '${bench.backName}' is longer than a back chip holds")
            // Whatever is opened over it, the chip over this bench reads its backName.
            val over = Navigation(Location(Place.PROJECT, Stage.PADS, listOf(bench, Bench.HELP)))
            assertEquals("◄ ${bench.backName}", over.backChip, "${bench.name}: the chip of what is opened over it")
        }
    }

    @Test
    fun `a bench opened alone names the stage showing or the place, and the BEAT benches are filed under PROJECT and the shelf ones under SHELF`() {
        for (bench in Bench.entries) {
            val stage = Stage.defaultFor(bench.home)
            val nav = Navigation(Location(bench.home, stage, listOf(bench)))
            assertEquals("◄ ${stage?.label ?: bench.home.label}", nav.backChip, bench.name)
        }
        // What the retired grouping law pinned, with the home spelled out rather than read back from the bench.
        for (bench in listOf(Bench.ORBIT, Bench.SONG, Bench.LOOP)) {
            assertEquals(Place.PROJECT, bench.home, "${bench.name}: filed under PROJECT")
            assertEquals("◄ BEAT", Navigation(Location(Place.PROJECT, Stage.BEAT, listOf(bench))).backChip, "${bench.name}: over BEAT")
        }
        for (bench in listOf(Bench.SNIPS, Bench.BIN, Bench.XRAY, Bench.DOUBLES)) {
            assertEquals(Place.SHELF, bench.home, "${bench.name}: filed under SHELF")
            assertEquals("◄ SHELF", Navigation(Location(Place.SHELF, null, listOf(bench))).backChip, "${bench.name}: over the shelf")
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
