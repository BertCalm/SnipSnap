package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScalesTest {

    // ---------- layouts ----------

    @Test
    fun `chromatic layout is sixteen ascending semitones`() {
        val layout = Scales.layout(Scale.CHROMATIC)
        assertEquals(16, layout.size)
        assertEquals((0..15).toList(), layout.toList())
    }

    @Test
    fun `scale layouts repeat up the octaves with the root on A01`() {
        val layout = Scales.layout(Scale.MINOR_PENTATONIC)
        assertEquals(0, layout[0], "root bottom-left")
        // Five degrees, then the same shape an octave up.
        assertEquals(listOf(0, 3, 5, 7, 10), layout.take(5).toList())
        assertEquals(listOf(12, 15, 17, 19, 22), layout.drop(5).take(5).toList())
        // 16 pads of pentatonic span three octaves and change.
        assertEquals(36, layout[15])
        for (i in 1 until 16) assertTrue(layout[i] > layout[i - 1], "layouts always ascend")
    }

    // ---------- note math ----------

    @Test
    fun `midi and hz round-trip through the anchors`() {
        assertEquals(440f, Scales.midiToHz(69))
        assertEquals(220f, Scales.midiToHz(57), 0.001f)
        assertEquals(69f, Scales.hzToMidi(440f), 0.001f)
        assertEquals("A4", Scales.nameOf(69))
        assertEquals("C4", Scales.nameOf(60))
        assertEquals("A2", Scales.nameOf(45))
    }

    @Test
    fun `nearestInKey snaps out-of-key notes and keeps in-key ones`() {
        val aMinor = 9 // A
        // 452 Hz is a sharp A4: nearest A-minor note is A4 itself.
        assertEquals(69, Scales.nearestInKey(452f, aMinor, Scale.MINOR))
        // G#4 (415.3 Hz) is not in A natural minor; it should snap to a neighbour.
        val snapped = Scales.nearestInKey(415.3f, aMinor, Scale.MINOR)
        assertTrue(snapped == 67 || snapped == 69, "G#4 snaps to G4 or A4, got ${Scales.nameOf(snapped)}")
        // An in-key note stays put.
        assertEquals(64, Scales.nearestInKey(Scales.midiToHz(64), aMinor, Scale.MINOR)) // E4 in A minor
    }

    // ---------- the detector this all sits on ----------

    private fun tone(hz: Double, seconds: Float = 0.5f): Snip =
        Snip(
            FloatArray((seconds * 44_100).toInt()) { (0.6 * sin(2.0 * PI * hz * it / 44_100)).toFloat() },
            1, 44_100,
        )

    @Test
    fun `Pitch detects tones across the working range`() {
        for (hz in doubleArrayOf(55.0, 110.0, 261.6, 440.0, 880.0)) {
            val e = assertNotNull(Pitch.detect(tone(hz)), "no pitch at $hz Hz")
            assertTrue(
                e.hz > hz * 0.97 && e.hz < hz * 1.03,
                "expected ~$hz Hz, measured ${e.hz}",
            )
            assertTrue(e.confidence > 0.5f, "clean tone should be confident at $hz Hz")
        }
    }

    @Test
    fun `Pitch refuses noise and silence`() {
        val noise = Dsp2Noise()
        val noisy = Snip(FloatArray(22_050) { noise.next() * 0.5f }, 1, 44_100)
        assertNull(Pitch.detect(noisy), "white noise is not a note")
        assertNull(Pitch.detect(Snip(FloatArray(22_050), 1, 44_100)), "silence is not a note")
        assertNull(Pitch.detect(Snip(FloatArray(500) { 0.5f }, 1, 44_100)), "too short to measure")
    }

    /** Tiny LCG so this test doesn't depend on synth-module noise. */
    private class Dsp2Noise {
        private var s = 7
        fun next(): Float { s = (s * 1103515245 + 12345) and 0x7fffffff; return (s.toFloat() / 0x3fffffff) - 1f }
    }
}
