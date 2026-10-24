package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScannedChainExtractionTest {
    private fun sine(hz: Double, phase: Double = 0.0, rate: Int = 44_100): Snip =
        Snip(FloatArray(rate) { (.4 * sin(2 * PI * hz * it / rate + phase)).toFloat() }, 1, rate)

    private fun cycle(source: Snip, start: Int = 37): ScannedChainShape =
        ScannedChainShape.fromSample(source, start, ScannedChainSampleMode.TONAL_CYCLE)

    private fun hash(points: FloatArray): String {
        val bytes = ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        points.forEach { bytes.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun harmonicAmplitude(points: FloatArray, harmonic: Int): Double {
        var cosine = 0.0
        var sine = 0.0
        for (i in points.indices) {
            val phase = 2 * PI * harmonic * i / points.size
            cosine += points[i] * cos(phase) * 2 / points.size
            sine += points[i] * sin(phase) * 2 / points.size
        }
        return sqrt(cosine * cosine + sine * sine)
    }

    @Test
    fun `reducing a tonal cycle preserves low partials without folding high source harmonics`() {
        val rate = 48_000
        val source = Snip(FloatArray(rate) { frame ->
            val phase = 2 * PI * 187.5 * frame / rate
            // High content is present throughout the selected cycle. The later
            // pitch-detection body is clean, so this tests extraction aliasing
            // independently of the detector's response to strong upper partials.
            (.5 * sin(phase) + .25 * sin(3 * phase)
                + if (frame < 512) .4 * sin(96 * phase) else 0.0).toFloat()
        }, 1, rate)
        val shape = cycle(source)
        val info = assertNotNull(shape.sample)
        assertTrue(assertNotNull(info.detectedHz) in 186.5f..188.5f)
        val fundamental = harmonicAmplitude(shape.points, 1)
        val third = harmonicAmplitude(shape.points, 3)
        val folded = harmonicAmplitude(shape.points, 32)
        assertTrue(fundamental > .15, "the source fundamental must survive downsampling")
        assertTrue(abs(third / fundamental - .5) < .02,
            "retain the source's low-partial balance rather than remove its third harmonic")
        assertTrue(folded / fundamental < .02,
            "the 18 kHz source harmonic must not alias from spatial96 to spatial32")
    }

    @Test
    fun `a clean tonal cycle remains a sine at fractional source periods`() {
        // 997.3 Hz has about 44.22 source frames per period: rounding to 44
        // introduces a seam even if the old Hann multiplication is removed.
        for (hz in listOf(220.3, 997.3)) {
            for (phase in listOf(0.0, .83, 1.8)) {
                val points = cycle(sine(hz, phase)).points
                var cosine = 0.0
                var sine = 0.0
                for (i in points.indices) {
                    val p = 2 * PI * i / points.size
                    cosine += points[i] * cos(p) * 2 / points.size
                    sine += points[i] * sin(p) * 2 / points.size
                }
                var residualPower = 0.0
                var power = 0.0
                for (i in points.indices) {
                    val p = 2 * PI * i / points.size
                    val difference = points[i] - cosine * cos(p) - sine * sin(p)
                    residualPower += difference * difference
                    power += points[i].toDouble() * points[i]
                }
                assertTrue(sqrt(residualPower / power) < .003,
                    "a $hz Hz cycle at phase $phase must not gain Hann side harmonics or a rounded-period seam")
            }
        }
    }

    @Test
    fun `selected phase survives extraction and the cycle wraps without a duplicated endpoint`() {
        val source = sine(750.0, phase = .73, rate = 48_000)
        val first = cycle(source, start = 0).points
        // One quarter of this source's 64-frame period is 32 of the 128 phases.
        val quarterLater = cycle(source, start = 16).points
        for (i in first.indices) {
            assertTrue(abs(quarterLater[i] - first[(i + 32) % first.size]) < .001f,
                "source position must rotate the saved cycle rather than taper each extraction to zero")
        }
        assertTrue(first[0] > .2f, "a nonzero selected phase must remain nonzero")
        val expectedWrapStep = .35 * (sin(.73) - sin(.73 + 2 * PI * 127 / 128))
        assertTrue(abs((first[0] - first.last()) - expectedWrapStep) < .001,
            "last-to-first is one regular phase step, not a duplicated cycle endpoint")
    }

    @Test
    fun `tonal provenance records the exact stereo interpolation span and survives recall`() {
        val rate = 44_100
        val source = Snip(FloatArray(rate * 2) { sample ->
            val frame = sample / 2
            val p = 2 * PI * 220.3 * frame / rate + .4
            (if (sample % 2 == 0) .45 * sin(p) + .1 * sin(2 * p)
                else .25 * sin(p) - .1 * sin(2 * p)).toFloat()
        }, 2, rate)
        val start = 73
        val shape = cycle(source, start)
        val info = assertNotNull(shape.sample)
        assertEquals(ScannedChainShapeSource.SAMPLE, shape.source)
        assertEquals(ScannedChainSampleMode.TONAL_CYCLE, info.mode)
        assertEquals(rate, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(start, info.startFrame)
        val hz = assertNotNull(info.detectedHz)
        assertTrue(hz in 219f..222f)
        assertTrue(assertNotNull(info.confidence) >= .7f)
        assertEquals(ceil(rate.toDouble() / hz).toInt() + 1, info.frames,
            "the source span includes the interpolation neighbor after the fractional period")
        val monoWindow = FloatArray(info.frames) { i ->
            val sample = (start + i) * 2
            (source.samples[sample] + source.samples[sample + 1]) / 2
        }
        assertEquals(hash(monoWindow), info.windowSha256)
        val saved = shape.toJsonValue()
        source.samples.fill(Float.NaN)
        val recalled = ScannedChainShape.fromJsonValue(saved)
        assertEquals(info, recalled.sample)
        assertContentEquals(shape.points, recalled.points,
            "saved coordinates, not source audio or a new extraction, determine recall")
    }

    @Test
    fun `tonal extraction still refuses silence noise and insufficient pitch evidence`() {
        val rate = 44_100
        val random = Random(9847)
        val sources = listOf(
            Snip(FloatArray(rate / 2), 1, rate),
            Snip(FloatArray(rate / 2) { random.nextFloat() * .8f - .4f }, 1, rate),
            Snip(FloatArray(1_000) { sin(2 * PI * 220 * it / rate).toFloat() }, 1, rate),
        )
        for (source in sources) {
            val error = assertFailsWith<IllegalArgumentException> { cycle(source, start = 0) }
            assertTrue(error.message.orEmpty().contains("No confident source pitch"))
        }
    }

    @Test
    fun `timbral window retains the original point bits and source hashes`() {
        // Frozen from the pre-fix implementation using an integer-derived signal.
        // Both the box-average and short-window circular interpolation paths are covered.
        val source = FloatArray(2_400) { ((it * 37 % 257) - 128) / 256f }
        val expected = listOf(
            Triple(48_000, "95f5da689e114614df6aaa594dc73c7060d7ae436b66d68566efcdf8d697461f",
                "d103451479bf12b2a4ce493b3d986664e86651276fc15bf6db258cf696b29474"),
            Triple(4_000, "112f1f8ec6f5cae5493b22f82ead5e01b11aa0b91e8ddb97815b736632512cd3",
                "95d98a8cdfdd503b6210cdcc8dad5ac4127c1717aac933ba9cc97614954b83f0"),
        )
        for ((rate, pointsHash, windowHash) in expected) {
            val shape = ScannedChainShape.fromSample(Snip(source, 1, rate), startFrame = 37,
                mode = ScannedChainSampleMode.TIMBRAL_WINDOW)
            val info = assertNotNull(shape.sample)
            assertEquals(pointsHash, hash(shape.points), "TIMBRAL_WINDOW point bits at $rate Hz")
            assertEquals(windowHash, info.windowSha256)
            assertEquals(ScannedChainSampleMode.TIMBRAL_WINDOW, info.mode)
            assertEquals(null, info.detectedHz)
            assertEquals(null, info.confidence)
        }
    }
}
