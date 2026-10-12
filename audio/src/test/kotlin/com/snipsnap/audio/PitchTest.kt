package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PitchTest {
    private val rate = 44_100
    private fun cents(hz: Double, wanted: Double) = 1200 * ln(hz / wanted) / ln(2.0)

    @Test
    fun `inharmonic upper modes cannot pull a confident fundamental out of key`() {
        val root = Scales.midiToHz(60).toDouble()
        val note = Snip(FloatArray(rate / 2) { i ->
            val t = i.toDouble() / rate
            (.6 * exp(-2 * t) * sin(2 * PI * root * t) +
                .24 * exp(-3 * t) * sin(2 * PI * root * 2.73 * t) +
                .03 * exp(-5 * t) * sin(2 * PI * root * 5.43 * t)).toFloat()
        }, 1, rate)

        val heard = assertNotNull(Pitch.detect(note))
        assertTrue(heard.confidence > Tuner.MIN_CONFIDENCE)
        assertTrue(abs(cents(heard.hz.toDouble(), root)) < 5,
            "inharmonic modes bend the root estimate: $heard, expected $root Hz")

        // C4 is outside D major. A fine-only adjustment would leave the actual
        // fundamental between notes, even if correlation reported high confidence.
        val tune = assertNotNull(Tuner.inKey(note, 2, Scale.MAJOR))
        assertEquals(1, abs(tune.tuneCoarse))
        val shifted = root * 2.0.pow((tune.tuneCoarse * 100 + tune.tuneFine) / 1200.0)
        assertTrue(abs(cents(shifted, Scales.midiToHz(tune.targetMidi).toDouble())) < 5,
            "the correction misses its in-key note: $tune")
    }

    @Test
    fun `spectral refinement preserves the period when the fundamental is weak or missing`() {
        val root = 220.0
        for (rootAmplitude in listOf(0.0, .07)) {
            val note = Snip(FloatArray(rate / 2) { i ->
                val phase = 2 * PI * root * i / rate
                (rootAmplitude * sin(phase) + .6 * sin(2 * phase) + .45 * sin(3 * phase)).toFloat()
            }, 1, rate)
            val heard = assertNotNull(Pitch.detect(note))
            assertTrue(heard.confidence > Tuner.MIN_CONFIDENCE)
            assertTrue(abs(cents(heard.hz.toDouble(), root)) < 10,
                "strong upper harmonics changed the selected octave: $heard")
        }
    }

    @Test
    fun `refinement resolves a tone between integer correlation periods`() {
        for (root in listOf(82.41, 261.6256, 997.0)) {
            val note = Snip(FloatArray(rate / 2) { i ->
                (.6 * sin(2 * PI * root * i / rate)).toFloat()
            }, 1, rate)
            val heard = assertNotNull(Pitch.detect(note))
            assertTrue(abs(cents(heard.hz.toDouble(), root)) < 5,
                "integer lag quantization remains audible at $root Hz: $heard")
        }
    }
}
