package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calibrate the bright-family decision with audio, independent of synth-engine metadata. */
class BrightTonalClassifierTest {
    @Test
    fun `high musical roots remain pitched percussion across durations and levels`() {
        // Every root is above the high band's 2 kHz boundary. A short pluck and
        // a sustained note must both avoid a snare; decay cannot fix this alone.
        for (hz in listOf(2093.0, 3136.0, 4186.0)) {
            for (seconds in listOf(.12f, .30f, 1.2f)) for (gain in listOf(.05f, 1f)) {
                val rate = DrumSynth.RATE
                val sound = Snip(FloatArray((seconds * rate).toInt()) { i ->
                    val t = i.toDouble() / rate
                    val attack = (t / .004).coerceAtMost(1.0)
                    val envelope = if (seconds < .5f) exp(-12.0 * t) else 1.0
                    (gain * .7 * attack * envelope *
                        (sin(2 * PI * hz * t) + .25 * sin(2 * PI * 2 * hz * t))).toFloat()
                }, 1, rate)
                val features = FeatureExtractor.extract(sound)
                assertTrue(features.highRatio > .9f, "$hz Hz does not exercise the bright-family decision")
                assertEquals(DrumClass.PERC, Classifier.classify(sound).drumClass,
                    "$hz Hz / $seconds s / gain $gain is mistaken for a noise drum")
                assertEquals(Classifier.classify(sound), Classifier.classify(features),
                    "audio and feature-only classification disagree")
            }
        }
    }

    @Test
    fun `body-heavy and noise-heavy snares keep their identity at quiet levels`() {
        // Mix .1 has a strong 190 Hz membrane and weak wires, while mix 1 is
        // all wires. Both are measured noisy spectra, with one finite strike.
        for (mix in listOf(.1f, .3f, .5f, 1f)) for (seed in 1..4) for (gain in listOf(.05f, 1f)) {
            val source = DrumSynth.snare(noiseMix = mix, seed = seed)
            val sound = Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, source.sampleRate)
            val features = FeatureExtractor.extract(sound)
            assertTrue(features.flatness > .4f, "snare mix $mix seed $seed loses its noise character")
            assertEquals(DrumClass.SNARE, Classifier.classify(sound).drumClass,
                "snare mix $mix seed $seed gain $gain loses its identity")
            assertEquals(Classifier.classify(sound), Classifier.classify(features))
        }
    }
}
