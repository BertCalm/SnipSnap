package com.snipsnap.app.ui

import com.snipsnap.audio.PcmPlayback
import com.snipsnap.synth.Rime
import com.snipsnap.synth.RimePatch
import com.snipsnap.synth.RimeVoice
import org.junit.Assert.*
import org.junit.Test

class RimeOpeningPreviewTest {
    @Test fun openingPlaysOnceAndOnlyItsOwnSustainRepeats() {
        val preview = RimeOpeningPreview()
        val patch = RimePatch("Held contour", RimeVoice.LOOP,
            Rime.defaults(RimeVoice.LOOP) + mapOf("HOLD" to 1f, "UNFOLD" to 1f))
        val note = preview.render(patch)
        val loop = requireNotNull(preview.loopFor(note))
        assertTrue(loop.first > 0)
        val playback = PcmPlayback(note.samples, startFrame = 0, loop = loop)
        val opening = FloatArray(note.frameCount)
        assertEquals(note.frameCount, playback.readFrames(opening))
        assertArrayEquals(note.samples, opening, 0f)
        val wrapped = FloatArray(256)
        assertEquals(256, playback.readFrames(wrapped))
        assertArrayEquals(note.samples.copyOfRange(loop.first, loop.first + 256), wrapped, 0f)
        assertNull(preview.loopFor(note.copy(samples = note.samples.copyOf())))
        preview.render(patch.copy(macros = patch.macros + ("UNFOLD" to 0f)))
        assertNull(preview.loopFor(note))
    }
}
