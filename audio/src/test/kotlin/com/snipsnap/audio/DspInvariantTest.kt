package com.snipsnap.audio

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pipeline's contracts, pinned as properties instead of examples: over
 * thousands of random synthetic snips, the feature extractor never emits a
 * non-finite or out-of-range value, the classifier always returns a valid
 * class with a confidence in `0..1`, and the chopper's slices stay in
 * bounds, in order, and cover their source. A regression in any of these
 * DSP stages shows up as a broken invariant on some seed, not as a wrong
 * number nobody noticed.
 */
class DspInvariantTest {

    private val rate = 44_100

    /** A varied synthetic snip: noise, a tone, a decaying hit, or silence. */
    private fun synth(rnd: Random): Snip {
        val frames = 512 + rnd.nextInt(60_000)
        val kind = rnd.nextInt(5)
        val buf = FloatArray(frames) { i ->
            when (kind) {
                0 -> (rnd.nextFloat() * 2 - 1) * 0.6f // noise
                1 -> kotlin.math.sin(2.0 * Math.PI * (60 + rnd.nextInt(4000)) * i / rate).toFloat() * 0.6f
                2 -> { // a decaying hit
                    val hz = 80 + rnd.nextInt(3000)
                    (kotlin.math.sin(2.0 * Math.PI * hz * i / rate) * Math.exp(-i / (0.05 * rate))).toFloat()
                }
                3 -> 0f // silence
                else -> if (i < 20) 0.9f else 0f // a lone spike
            }
        }
        val channels = if (rnd.nextBoolean()) 1 else 2
        // Keep a whole number of frames for the chosen channel count.
        val trimmed = if (channels == 2 && buf.size % 2 == 1) buf.copyOf(buf.size - 1) else buf
        return Snip(trimmed, channels, rate)
    }

    @Test
    fun `features are always finite and in range`() {
        repeat(3_000) { seed ->
            val rnd = Random(seed)
            val f = FeatureExtractor.extract(synth(rnd))
            val fields = listOf(
                "centroid" to f.centroidHz, "rolloff" to f.rolloffHz, "flatness" to f.flatness,
                "zcr" to f.zeroCrossingRate, "low" to f.lowRatio, "mid" to f.midRatio,
                "high" to f.highRatio, "duration" to f.durationSeconds, "decay" to f.decayMs,
                "peak" to f.peak,
            )
            for ((name, v) in fields) {
                assertTrue(v.isFinite(), "seed $seed: $name is non-finite ($v)")
            }
            for (name in listOf("flatness", "zcr", "low", "mid", "high")) {
                val v = fields.first { it.first == name }.second
                assertTrue(v in 0f..1.001f, "seed $seed: $name out of 0..1 ($v)")
            }
            assertTrue(f.durationSeconds >= 0f && f.peak >= 0f && f.attackBursts >= 1, "seed $seed: bounds")
        }
    }

    @Test
    fun `classify always returns a valid class with confidence in range`() {
        repeat(3_000) { seed ->
            val rnd = Random(seed)
            val c = Classifier.classify(synth(rnd))
            // drumClass is an enum, so always valid; confidence is the claim.
            assertTrue(c.confidence in 0f..1f && c.confidence.isFinite(), "seed $seed: confidence ${c.confidence}")
        }
    }

    @Test
    fun `chopper slices stay in bounds, in order, and cover the source`() {
        repeat(2_000) { seed ->
            val rnd = Random(seed)
            val snip = synth(rnd)
            // cleanup=null so raw slices run start-to-start and cover fully.
            val slices = Chopper.byTransients(snip, maxSlices = 1 + rnd.nextInt(32), cleanup = null)
            if (slices.isEmpty()) return@repeat // silence yields nothing - fine

            var prev = -1
            for (s in slices) {
                assertTrue(s.sourceFrame in 0 until snip.frameCount, "seed $seed: sourceFrame ${s.sourceFrame}")
                assertTrue(s.sourceFrame > prev, "seed $seed: slices out of order")
                assertTrue(s.snip.frameCount > 0, "seed $seed: empty slice")
                assertTrue(s.snip.channels == snip.channels, "seed $seed: channel count changed")
                prev = s.sourceFrame
            }
            // Contiguous cover: each slice ends where the next begins, the
            // last reaches the end.
            for (i in slices.indices) {
                val end = slices[i].sourceFrame + slices[i].snip.frameCount
                val expected = if (i + 1 < slices.size) slices[i + 1].sourceFrame else snip.frameCount
                assertTrue(end == expected, "seed $seed: slice $i ends $end, expected $expected")
            }
        }
    }
}
