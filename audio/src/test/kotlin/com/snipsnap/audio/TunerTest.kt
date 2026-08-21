package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunerTest {

    private fun tone(hz: Double, seconds: Float = 0.5f): Snip =
        Snip(
            FloatArray((seconds * 44_100).toInt()) { (0.6 * sin(2.0 * PI * hz * it / 44_100)).toFloat() },
            1, 44_100,
        )

    @Test
    fun `a sharp note gets pulled onto the nearest in-key note`() {
        // 30 cents sharp of A3 (220 Hz): 223.8 Hz. In A minor the target is
        // A3 and the correction is about -30 cents.
        val tune = assertNotNull(Tuner.inKey(tone(223.8), rootSemitone = 9, scale = Scale.MINOR))
        assertEquals("A3", tune.targetName)
        assertEquals(0, tune.tuneCoarse)
        assertTrue(tune.tuneFine in -40..-20, "expected ~-30 cents, got ${tune.tuneFine}")
    }

    @Test
    fun `an out-of-key note moves whole semitones`() {
        // G#3 (207.65 Hz) in A natural minor: nearest in-key notes are G3
        // (-1 semi) and A3 (+1 semi). Either way |coarse*100 + fine| ≈ 100.
        val tune = assertNotNull(Tuner.inKey(tone(207.65), rootSemitone = 9, scale = Scale.MINOR))
        val totalCents = tune.tuneCoarse * 100 + tune.tuneFine
        assertTrue(abs(abs(totalCents) - 100) <= 15, "expected ~±100 cents, got $totalCents")
    }

    @Test
    fun `an in-tune in-key note is left essentially alone`() {
        val tune = assertNotNull(Tuner.inKey(tone(220.0), rootSemitone = 9, scale = Scale.MINOR))
        assertEquals(0, tune.tuneCoarse)
        assertTrue(abs(tune.tuneFine) <= 10, "in-tune input should barely move: ${tune.tuneFine}")
    }

    @Test
    fun `unpitched material is never corrected`() {
        var s = 3
        val noise = Snip(FloatArray(22_050) { s = (s * 1103515245 + 12345) and 0x7fffffff; (s.toFloat() / 0x3fffffff) - 1f }, 1, 44_100)
        assertNull(Tuner.inKey(noise, 0, Scale.MAJOR), "a guessed retune is worse than none")
    }

    @Test
    fun `the applied correction is audibly right`() {
        // Close the loop: retune a sharp tone, apply the correction as a
        // resample-style pitch shift, and the detector must now read the
        // target note.
        val sharp = tone(233.0) // between A#3 and A3-sharp territory
        val tune = assertNotNull(Tuner.inKey(sharp, rootSemitone = 9, scale = Scale.MINOR_PENTATONIC))
        val cents = tune.tuneCoarse * 100 + tune.tuneFine
        val shifted = 233.0 * Math.pow(2.0, cents / 1200.0)
        val target = Scales.midiToHz(tune.targetMidi).toDouble()
        assertTrue(
            abs(shifted - target) / target < 0.01,
            "correction should land on ${tune.targetName}: $shifted vs $target",
        )
    }
}
