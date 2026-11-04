package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayCaptureTest {
    private fun s(seconds: Double) = (seconds * 1e9).toLong()

    @Test fun `nothing played is nothing to grab`() {
        val capture = PlayCapture()
        assertNull(capture.grab(s(5.0)))
        assertTrue(capture.isEmpty(s(5.0)))
    }

    @Test fun `notes keep their timing and velocity, and the clip starts at the first one`() {
        val capture = PlayCapture()
        // At 120 BPM a second is two beats: 1920 pulses.
        capture.noteOn(60, 0.8f, s(10.0))
        capture.noteOff(60, s(10.5))
        capture.noteOn(64, 0.4f, s(10.5))
        capture.noteOff(64, s(10.75))
        val clip = capture.grab(s(11.0))!!
        assertEquals(listOf(60, 64), clip.notes.map { it.note })
        assertEquals(listOf(0L, 960L), clip.notes.map { it.timePulses })
        assertEquals(listOf(960L, 480L), clip.notes.map { it.lengthPulses })
        assertEquals(listOf(0.8f, 0.4f), clip.notes.map { it.velocity })
        assertEquals(1, clip.bars)
        assertEquals(PlayCapture.NAME, clip.name)
    }

    @Test fun `silence before the first note is not carried along`() {
        val capture = PlayCapture()
        capture.noteOn(60, 1f, s(14.0))
        capture.noteOff(60, s(14.25))
        assertEquals(0L, capture.grab(s(16.0))!!.notes.single().timePulses)
    }

    @Test fun `only the last sixteen seconds are taken, and a note held across the start begins at it`() {
        val capture = PlayCapture()
        capture.noteOn(48, 1f, s(1.0))
        capture.noteOff(48, s(2.0))
        capture.noteOn(50, 1f, s(3.0))
        capture.noteOff(50, s(7.0)) // crosses the window's start at 4 s
        capture.noteOn(52, 1f, s(10.0))
        capture.noteOff(52, s(11.0))
        val clip = capture.grab(s(20.0))!!
        assertEquals(listOf(50, 52), clip.notes.map { it.note })
        assertEquals(0L, clip.notes[0].timePulses)
        assertEquals(3L * 1920, clip.notes[0].lengthPulses, "it kept only the part inside the window: 4 s to 7 s")
        assertEquals(6L * 1920, clip.notes[1].timePulses)
    }

    @Test fun `a note still down ends at the moment of the grab, and a struck note is let go first`() {
        val capture = PlayCapture()
        capture.noteOn(60, 1f, s(1.0))
        capture.noteOn(60, 0.5f, s(2.0)) // struck again with the first still down
        val clip = capture.grab(s(3.0))!!
        assertEquals(listOf(1920L, 1920L), clip.notes.map { it.lengthPulses })
        assertEquals(listOf(0L, 1920L), clip.notes.map { it.timePulses })
    }

    @Test fun `releaseAll lets every held note go, and a release for a note never down is ignored`() {
        val capture = PlayCapture()
        capture.noteOn(60, 1f, s(1.0))
        capture.noteOn(64, 1f, s(1.0))
        capture.noteOff(99, s(1.5))
        capture.releaseAll(s(2.0))
        val clip = capture.grab(s(30.0), windowNanos = s(60.0))!!
        assertEquals(listOf(1920L, 1920L), clip.notes.map { it.lengthPulses })
        assertEquals(2, clip.notes.size)
    }

    @Test fun `the clip is as many whole bars as the notes reach`() {
        val capture = PlayCapture()
        capture.noteOn(60, 1f, s(0.0))
        capture.noteOff(60, s(0.1))
        capture.noteOn(62, 1f, s(5.0)) // 10 beats in: the third bar
        capture.noteOff(62, s(5.1))
        assertEquals(3, capture.grab(s(6.0))!!.bars)
    }

    @Test fun `old notes are forgotten as new ones come in`() {
        val capture = PlayCapture(retainNanos = s(10.0))
        capture.noteOn(40, 1f, s(0.0))
        capture.noteOff(40, s(1.0))
        capture.noteOn(41, 1f, s(30.0))
        capture.noteOff(41, s(31.0))
        // Even a window wide enough to reach back finds only what was kept.
        assertEquals(listOf(41), capture.grab(s(32.0), windowNanos = s(60.0))!!.notes.map { it.note })
    }

    @Test fun `a grab at another tempo scales the same playing`() {
        val capture = PlayCapture()
        capture.noteOn(60, 1f, s(0.0))
        capture.noteOff(60, s(0.5))
        capture.noteOn(62, 1f, s(0.5))
        capture.noteOff(62, s(1.0))
        assertEquals(480L, capture.grab(s(2.0), bpm = 60f)!!.notes[1].timePulses)
        assertEquals(960L, capture.grab(s(2.0), bpm = 120f)!!.notes[1].timePulses)
    }
}
