package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockModelTest {

    private val out = Door(Places.DOOR_OUT, Location(Place.PROJECT, Stage.OUT))
    private val cut = Location(Place.TAPE, Stage.CUT)
    private val pads = Location(Place.PROJECT, Stage.PADS)
    private val beat = Location(Place.PROJECT, Stage.BEAT)
    private val outStage = Location(Place.PROJECT, Stage.OUT)

    @Test
    fun `rests on the ready line with no door`() {
        val dock = DockModel()
        assertEquals(DockModel.Now(Copy.DOCK_READY, null, null), dock.now)
        assertEquals(Places.LISTEN, dock.captureLabel)
        assertEquals(Places.STOPPED, dock.transportLabel)
    }

    @Test
    fun `an offer stays until it is taken, through any number of plain lines`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out, pads)
        dock.say("DUSTED A02.", pads)
        dock.say("KEPT.", pads)
        assertEquals(out, dock.now.door, "a line is not an offer; the door stays (J10)")
        assertEquals("KEPT.", dock.now.text)
        assertEquals(out, dock.take())
        assertNull(dock.now.door, "taken means gone")
        assertNull(dock.take(), "and there is nothing to take twice")
        assertEquals("KEPT.", dock.now.text, "taking the door keeps the line")
    }

    @Test
    fun `a new offer replaces the old door, and clear goes back to rest`() {
        val dock = DockModel()
        dock.offer("ON TAPE.", Door(Places.DOOR_CUT, cut), Location(Place.TAPE, Stage.DECK))
        dock.offer("THE PADS ARE LOADED.", out, pads)
        assertEquals(out, dock.now.door)
        dock.clear()
        assertEquals(DockModel.Now(Copy.DOCK_READY, null, null), dock.now)
    }

    @Test
    fun `the capture and transport regions read their state`() {
        val dock = DockModel()
        dock.capture = DockModel.Capture.Armed(41)
        assertEquals("ARMED · 00:41 ON TAPE", dock.captureLabel)
        dock.capture = DockModel.Capture.Armed(125)
        assertEquals("ARMED · 02:05 ON TAPE", dock.captureLabel)
        dock.capture = DockModel.Capture.Snipped
        assertEquals(Places.SNIPPED, dock.captureLabel)

        dock.transport = DockModel.Transport.Playing(94f, 2)
        assertEquals("▶ 94 BPM · BAR 2", dock.transportLabel)
        dock.transport = DockModel.Transport.Playing(null, 0)
        assertEquals("▶ · BAR 1", dock.transportLabel, "no tempo yet; bars count from one")
        dock.transport = DockModel.Transport.Recording(3)
        assertEquals("● BAR 3", dock.transportLabel)
    }

    @Test
    fun `a line said on CUT is gone once you are on PADS`() {
        val dock = DockModel()
        dock.say("NO HITS IN THIS SOURCE.", cut)
        dock.moved(pads)
        assertEquals(DockModel.Now(Copy.DOCK_READY, null, null), dock.now)
    }

    @Test
    fun `a line said on a stage stays on that stage, and under a bench over it`() {
        val dock = DockModel()
        dock.say("KEPT.", pads)
        dock.moved(pads)
        assertEquals("KEPT.", dock.now.text, "moving to where it was said changes nothing")
        dock.moved(pads.copy(benches = listOf(Bench.PAD_SHEET)))
        assertEquals("KEPT.", dock.now.text, "the pad sheet still shows what PADS just said")
        dock.moved(pads)
        assertEquals("KEPT.", dock.now.text, "and Back from the bench")
        dock.moved(beat)
        assertEquals(Copy.DOCK_READY, dock.now.text, "another stage of the same place is another place to say it")
    }

    @Test
    fun `a door and its words survive a move elsewhere`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out, cut)
        dock.moved(pads)
        dock.moved(Location(Place.MAKE))
        assertEquals(out, dock.now.door)
        assertEquals("THE PADS ARE LOADED. TAKE THE KIT OUT.", dock.now.text)
    }

    @Test
    fun `a door whose target you reach another way is dropped and the line rests`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out, cut)
        dock.moved(outStage)
        assertEquals(DockModel.Now(Copy.DOCK_READY, null, null), dock.now)
    }

    @Test
    fun `a door to where you stand is never stood`() {
        val dock = DockModel()
        dock.offer("KEPT.", out, outStage)
        assertNull(dock.now.door)
        assertEquals("KEPT.", dock.now.text, "the words still land, as a plain line")
    }

    @Test
    fun `a line said after an offer keeps the door, and re-scopes to where it was said`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out, cut)
        dock.say("SENT 4 TO THE PADS.", pads)
        assertEquals(out, dock.now.door)
        assertEquals(pads, dock.now.at)
        assertEquals("SENT 4 TO THE PADS.", dock.now.text)
    }

    @Test
    fun `taking the door and arriving rests the line it was offered with`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out, cut)
        val door = dock.take()!!
        dock.moved(door.target)
        assertEquals(DockModel.Now(Copy.DOCK_READY, null, null), dock.now)
    }
}
