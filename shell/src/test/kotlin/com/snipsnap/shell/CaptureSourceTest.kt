package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureSourceTest {

    @Test
    fun `a stored choice reads back, and anything else is no choice`() {
        for (s in CaptureSource.entries) assertEquals(s, CaptureSource.parse(s.stored))
        assertNull(CaptureSource.parse(null))
        assertNull(CaptureSource.parse(""))
        assertNull(CaptureSource.parse("MIC"), "the label is not the stored word")
        assertNull(CaptureSource.parse("speakers"))
    }

    @Test
    fun `stored words are distinct`() {
        assertEquals(CaptureSource.entries.size, CaptureSource.entries.map { it.stored }.toSet().size)
    }

    @Test
    fun `the idle dock names a kept source and keeps its old word before one is kept`() {
        val dock = DockModel()
        assertEquals("LISTEN · MIC", dock.captureLabel)
        dock.captureSource = CaptureSource.MIC
        assertEquals("LISTEN · MIC", dock.captureLabel)
        dock.captureSource = CaptureSource.APP_AUDIO
        assertEquals("LISTEN · APP AUDIO", dock.captureLabel)
        dock.capture = DockModel.Capture.Armed(41)
        assertEquals("LISTENING · 00:41 BUFFERED", dock.captureLabel, "armed reads as it always did")
        dock.capture = DockModel.Capture.Idle
        dock.captureSource = null
        assertEquals(Places.LISTEN, dock.captureLabel)
    }
}
