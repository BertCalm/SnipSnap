package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockModelTest {

    private val out = Door(Places.DOOR_OUT, Location(Place.PROJECT, Stage.OUT))

    @Test
    fun `rests on the ready line with no door`() {
        val dock = DockModel()
        assertEquals(DockModel.Now(Copy.DOCK_READY, null), dock.now)
        assertEquals(Places.LISTEN, dock.captureLabel)
        assertEquals(Places.STOPPED, dock.transportLabel)
    }

    @Test
    fun `an offer stays until it is taken, through any number of plain lines`() {
        val dock = DockModel()
        dock.offer("THE PADS ARE LOADED. TAKE THE KIT OUT.", out)
        dock.say("DUSTED A02.")
        dock.say("KEPT.")
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
        dock.offer("ON TAPE.", Door(Places.DOOR_CUT, Location(Place.TAPE, Stage.CUT)))
        dock.offer("THE PADS ARE LOADED.", out)
        assertEquals(out, dock.now.door)
        dock.clear()
        assertEquals(DockModel.Now(Copy.DOCK_READY, null), dock.now)
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
}
