package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Preserve the positively received original scene; refinement must remain small. */
class OspreyV3Test {
    private val controls = mapOf("MOTION" to .45f, "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to .45f)
    private fun probe(model: Int, shore: Float, sea: Float, extra: Map<String, Float> = emptyMap(),
        midi: Int = 60, velocity: Float = 1f, seed: Int = 731, seconds: Float = 8f) =
        Osprey.probe(OspreyVoice.SWELL, controls + mapOf("SHORE" to shore, "SEA" to sea) + extra,
            midi, velocity, seed, seconds, modelVersion = model)

    private fun shapeError(a: FloatArray, b: FloatArray): Double {
        var aa = 0.0; var ab = 0.0; var bb = 0.0
        for (i in a.indices) { aa += a[i].toDouble() * a[i]; ab += a[i].toDouble() * b[i]; bb += b[i].toDouble() * b[i] }
        val gain = ab / aa.coerceAtLeast(1e-30)
        var residual = 0.0
        for (i in a.indices) { val d = b[i] - gain * a[i]; residual += d * d }
        return sqrt(residual / bb.coerceAtLeast(1e-30))
    }

    /** Stereo power keeps body panning from looking like spectral cancellation. */
    private fun bands(samples: FloatArray): DoubleArray {
        val n = 32768
        val result = DoubleArray(3)
        for (channel in 0..1) for (start in listOf(13230, 29614, 45998)) {
            val re = FloatArray(n) { i -> samples[(start + i) * 2 + channel] *
                (.5 - .5 * cos(2 * PI * i / (n - 1))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (k in 1 until n / 2) {
                val hz = k * 44100.0 / n
                if (hz < 30 || hz > 18000) continue
                val band = if (hz < 500) 0 else if (hz < 2000) 1 else 2
                result[band] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            }
        }
        val total = result.sum()
        return DoubleArray(3) { result[it] / total }
    }

    private fun envelope(samples: FloatArray): DoubleArray {
        val size = 4410 // 50 ms of stereo samples
        return DoubleArray(samples.size / size) { block ->
            var sum = 0.0
            for (i in block * size until (block + 1) * size) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / size)
        }
    }

    @Test
    fun `gentle coasts retain the original scene and warm spectral balance`() {
        for (shore in listOf(0f, .25f, .5f, .75f, 1f)) for (sea in listOf(.1f, .5f, .9f)) {
            val original = probe(1, shore, sea)
            val gentle = probe(3, shore, sea)
            assertContentEquals(original.ocean, gentle.ocean, "the water exciter changed")
            assertContentEquals(original.creatures, gentle.creatures, "the creature sound changed")
            assertContentEquals(original.sympathy, gentle.sympathy, "the passive modal state changed")
            assertContentEquals(original.modeHz, gentle.modeHz)
            assertEquals(original.callEvents, gentle.callEvents)
            assertEquals(original.metrics.finalModalEnergy, gentle.metrics.finalModalEnergy)
            assertEquals(original.metrics.peakModalEnergy, gentle.metrics.peakModalEnergy)
            assertEquals(original.metrics.excitationStopSeconds, gentle.metrics.excitationStopSeconds)
            val before = bands(original.samples); val after = bands(gentle.samples)
            println("OSPREY R3 shore=$shore sea=$sea originalBands=${before.toList()} gentleBands=${after.toList()} residual=${shapeError(original.samples, gentle.samples)}")
            assertTrue(after[0] >= before[0] - .06, "warm low register disappeared at $shore/$sea")
            assertTrue(after[1] <= before[1] * 1.7 + .002, "midrange takes over at $shore/$sea")
            assertTrue(after[2] <= before[2] * 1.1 + .002, "upper roughness grew at $shore/$sea")
            assertTrue(shapeError(original.samples, gentle.samples) < .20, "the original character was replaced")
            val a = envelope(original.samples); val b = envelope(gentle.samples)
            val envelopeChange = sqrt(a.indices.sumOf { val d = a[it] - b[it]; d * d } / a.sumOf { it * it })
            assertTrue(envelopeChange < .15, "the original movement changed at $shore/$sea: $envelopeChange")
        }
    }

    @Test
    fun `new default controls return to the original while body refinements are not level changes`() {
        assertEquals(Osprey.defaults(OspreyVoice.SWELL, 1), Osprey.defaults(OspreyVoice.SWELL, 3))
        for (shore in listOf(0f, .25f, .5f, .75f, 1f)) {
            val original = probe(1, shore, .5f)
            val gentle = probe(3, shore, .5f)
            assertTrue(shapeError(original.primary, gentle.primary) > .001,
                "shore $shore has no refinement beyond output level")
        }
    }

    @Test
    fun `gentle renders remain deterministic finite and correctly decomposed at extremes`() {
        for ((shore, sea, midi) in listOf(Triple(0f, 0f, 36), Triple(.375f, .65f, 60), Triple(1f, 1f, 84))) {
            val extra = mapOf("MOTION" to 1f, "DEPTH" to 1f, "SPACE" to 0f, "HOLD" to 1f)
            val a = probe(3, shore, sea, extra, midi = midi, seed = -7104, seconds = 3f)
            val b = probe(3, shore, sea, extra, midi = midi, seed = -7104, seconds = 3f)
            assertContentEquals(a.samples, b.samples)
            assertEquals(a.callEvents, b.callEvents)
            assertContentEquals(a.dry, a.samples)
            assertTrue(a.samples.all { it.isFinite() && abs(it) < 1f })
            for (i in a.samples.indices) assertTrue(abs(a.dry[i] - (a.primary[i] + a.sympathy[i] + a.creatures[i])) < 1e-6f)
            val silent = probe(3, shore, sea, extra, midi = midi, velocity = 0f, seconds = .25f)
            assertTrue(silent.samples.all { it == 0f })
        }
    }

    @Test
    fun `saved model two partial recipes retain their published defaults and sample bits`() {
        // Captured from the already compiled 861dfbfd renderer before compiling model 3.
        val cases = listOf(
            emptyMap<String, Float>() to "eefcc6bff527748604bc91a3600cdda25cdfc70f2dd63592035efe7eaaad3835",
            mapOf("SHORE" to .4f) to "8bfd8809161ca93986b68a19eca7452349653bb474ad115381a472094493d875",
            mapOf("SHORE" to .75f, "SEA" to .9f, "SPACE" to 0f) to "3d6319626deac3d41c64412d5150427632351bf0da04fec3f44c6493bb5a73a4",
        )
        for ((macros, hash) in cases) {
            val patch = OspreyPatch("Published R2", OspreyVoice.SWELL, macros, velocity = .65f,
                seed = 731, durationSeconds = 1.5f, modelVersion = 2)
            val digest = MessageDigest.getInstance("SHA-256")
            for (sample in patch.render().samples) {
                val bits = sample.toRawBits()
                for (shift in 0 until 32 step 8) digest.update((bits ushr shift).toByte())
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash, actual, "stored model-2 defaults changed")
        }
    }
}
