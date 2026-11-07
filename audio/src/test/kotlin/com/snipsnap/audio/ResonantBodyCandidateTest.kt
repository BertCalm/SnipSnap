package com.snipsnap.audio

import kotlin.math.*
import kotlin.test.*

class ResonantBodyCandidateTest {
    private fun settings() = BodySettings(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION)

    private fun impulse(rate: Int = 44_100) =
        Snip(floatArrayOf(0.8f), 1, rate)

    private fun noise(rate: Int, channels: Int, frames: Int): Snip {
        var state = 0x13579
        return Snip(FloatArray(frames * channels) { i ->
            state = state * 1664525 + 1013904223
            val value = (((state ushr 8) / 8388608.0 - 1) * 0.7).toFloat()
            if (channels == 2 && i % 2 == 1) value * -0.5f else value
        }, channels, rate)
    }

    private fun coefficients(s: BodySettings, rate: Int) = bodyCoefficients(BodyCoefficientKey(
        BodyModels.identity(s), s.coefficientVersion, rate, s.referenceHz, s.rootT60, s.damp.toDouble()))

    @Test fun `candidate damping shortens decay without increasing injection or moving resonances`() {
        for (rate in listOf(44_100, 48_000)) for (tune in listOf(0f, 1f)) for (ring in listOf(0f, 0.5f, 1f)) {
            val s = settings().copy(tune = tune, ring = ring)
            val rows = listOf(0f, 0.5f, 1f).map { coefficients(s.copy(damp = it), rate) }
            assertEquals(8, rows.first().size)
            for (row in rows) {
                assertEquals(s.referenceHz, row[0].hz)
                assertEquals(s.referenceHz, row[4].hz)
                assertEquals(1.0, row.take(4).sumOf { it.weight }, 1e-12)
                assertEquals(1.0, row.drop(4).sumOf { it.weight }, 1e-12)
                assertTrue(row[4].weight < row[0].weight, "the BAR candidate must not retain the old identical root weight")
                for (mode in row) {
                    assertTrue(mode.hz > 0 && mode.hz < rate / 2.0)
                    assertTrue(mode.t60 in 0.01..(s.rootT60 + 1e-12))
                    assertTrue(mode.radius > 0 && mode.radius < 1)
                    val injection = hypot(mode.injectReal, mode.injectImaginary)
                    assertTrue(mode.radius + injection <= 1.0 + 2e-15,
                        "rotation plus input gain must preserve the pre-guard state bound")
                }
            }
            for (i in rows.first().indices) {
                for (j in 1 until rows.size) {
                    assertEquals(rows[0][i].hz, rows[j][i].hz)
                    assertEquals(rows[0][i].injectReal, rows[j][i].injectReal)
                    assertEquals(rows[0][i].injectImaginary, rows[j][i].injectImaginary)
                    assertTrue(rows[j][i].t60 <= rows[j - 1][i].t60 + 1e-12,
                        "DAMP may reach its floor but must not lengthen decay")
                }
            }
            assertTrue(rows.last()[1].t60 < rows.first()[1].t60, "a non-root mode must respond to DAMP")
        }
    }

    @Test fun `candidate native-rate control corners stay deterministic finite and bounded`() {
        val processor = ResonantBody()
        for (rate in listOf(44_100, 48_000)) for (channels in listOf(1, 2)) {
            val source = noise(rate, channels, 512)
            val original = source.samples.copyOf()
            for (tune in listOf(0f, 1f)) for (body in listOf(0f, 0.5f, 1f))
                for (ring in listOf(0f, 1f)) for (damp in listOf(0f, 1f)) {
                    val s = settings().copy(tune = tune, body = body, ring = ring, damp = damp, mix = 0.6f)
                    val a = processor.process(source, s, captureWet = true)
                    val b = processor.process(source, s, captureWet = true)
                    assertContentEquals(a.audio.samples, b.audio.samples, s.toString())
                    assertContentEquals(assertNotNull(a.rawWet).samples, assertNotNull(b.rawWet).samples)
                    assertContentEquals(original, source.samples)
                    assertEquals(source.frameCount + ceil(s.rootT60 * rate).toInt(), a.audio.frameCount)
                    assertEquals(channels, a.audio.channels)
                    assertEquals(rate, a.audio.sampleRate)
                    assertTrue(a.audio.samples.all { it.isFinite() })
                    assertTrue(a.rawWet!!.samples.all { it.isFinite() })
                    assertTrue(a.audio.peak() <= 0.980001f)
                    assertTrue(a.diagnostics.statePeak <= a.diagnostics.inputPeak * (1 + 1e-9) + 1e-12)
                    assertTrue(a.audio.samples.takeLast(channels).all { it == 0f })
                    assertEquals(2, a.diagnostics.modelVersion)
                    assertEquals(2, a.diagnostics.coefficientVersion)
                }
        }
    }

    @Test fun `candidate silence and stereo separation survive the changed family profiles`() {
        val processor = ResonantBody()
        for (rate in listOf(44_100, 48_000)) {
            val s = settings().copy(body = 0.5f, ring = 1f, damp = 0.5f, mix = 1f)
            val silent = processor.process(Snip(FloatArray(256), 2, rate), s, captureWet = true)
            assertTrue(silent.audio.samples.all { it.toRawBits() == 0 })
            assertTrue(assertNotNull(silent.rawWet).samples.all { it.toRawBits() == 0 })
            assertEquals(0.0, silent.diagnostics.statePeak)
            val mono = noise(rate, 1, 512)
            val left = Snip(FloatArray(mono.samples.size * 2) { if (it % 2 == 0) mono.samples[it / 2] else 0f }, 2, rate)
            val equal = Snip(FloatArray(left.samples.size) { mono.samples[it / 2] }, 2, rate)
            val a = processor.process(left, s).audio
            val b = processor.process(equal, s).audio
            for (frame in 0 until a.frameCount) {
                assertEquals(0f, a.samples[2 * frame + 1])
                assertEquals(b.samples[2 * frame], b.samples[2 * frame + 1])
            }
        }
    }

    @Test fun `candidate free decay loses energy before the final fade and an unstable pole fails that observation`() {
        fun energy(x: FloatArray, from: Int, width: Int) =
            (from until from + width).sumOf { x[it].toDouble().pow(2) }
        for (rate in listOf(44_100, 48_000)) for (body in listOf(0f, 1f)) {
            val s = settings().copy(body = body, ring = 1f, damp = 0f, mix = 1f)
            val x = ResonantBody().process(impulse(rate), s).audio.samples
            val start = (0.04 * rate).roundToInt()
            val late = (0.24 * rate).roundToInt()
            val width = (0.04 * rate).roundToInt()
            fun decays(samples: FloatArray) = energy(samples, late, width) < 0.1 * energy(samples, start, width)
            assertTrue(decays(x), "body=$body must lose energy during the free tail, well before its fade")
            val unstable = FloatArray(x.size) { n ->
                (1.0001.pow(n) * sin(2 * PI * s.referenceHz * n / rate)).toFloat()
            }
            assertFalse(decays(unstable), "the same independent windows must reject a growing pole")
        }
    }

    @Test fun `candidate endpoint shape distinction exceeds the previously weak numerical examples`() {
        val rate = 44_100
        val reference = settings().referenceHz
        var state = 0x13579
        val burst = Snip(FloatArray(rate / 4) { n ->
            state = state * 1664525 + 1013904223
            val value = (state ushr 8) / 8388608.0 - 1
            if (n < 882) (value * 0.5 * sin(PI * n / 881).pow(2)).toFloat() else 0f
        }, 1, rate)
        val harmonic = Snip(FloatArray(rate) { n ->
            (0.2 * (1..4).sumOf { h -> sin(2 * PI * reference * h * n / rate) / h } /
                (1..4).sumOf { 1.0 / it }).toFloat()
        }, 1, rate)
        fun distance(a: FloatArray, b: FloatArray): Double {
            val ar = sqrt(a.sumOf { it.toDouble().pow(2) })
            val br = sqrt(b.sumOf { it.toDouble().pow(2) })
            assertTrue(ar > 0 && br > 0)
            return sqrt(a.indices.sumOf { (a[it] / ar - b[it] / br).pow(2) })
        }
        val processor = ResonantBody()
        for (source in listOf(burst, harmonic)) {
            fun render(version: Int, body: Float) = processor.process(source,
                BodySettings(modelVersion = version, body = body, mix = 1f)).audio.samples
            val oldDistance = distance(render(1, 0f), render(1, 1f))
            val a = render(2, 0f)
            val b = render(2, 1f)
            val candidateDistance = distance(a, b)
            // These are regression limits on two named fixtures, not claims of audibility.
            // The owner's listening decision remains separate from numerical activity.
            assertTrue(candidateDistance > oldDistance + 0.05,
                "the explicit candidate must improve shape separation on the reviewed fixture: old=$oldDistance, candidate=$candidateDistance")
            assertTrue(candidateDistance > 0.25)
            assertFalse(distance(a, a) > 0.25, "a frozen BODY cannot pass the same observation")
            assertTrue(distance(a, render(2, 0.5f)) > 0.01)
            assertTrue(distance(render(2, 0.5f), b) > 0.01)
        }
    }

    @Test fun `candidate impulse spectra retain the authored resonances without a perceived BAR pitch promise`() {
        val rate = 44_100
        for (tune in listOf(1f / 3f, 1f)) for (body in listOf(0f, 1f)) {
            val s = settings().copy(tune = tune, body = body, ring = 1f, damp = 0f, mix = 1f)
            val re = ResonantBody().process(impulse(), s).audio.samples.copyOf(65_536)
            val im = FloatArray(re.size)
            Fft.forward(re, im)
            val ratios = if (body == 0f) listOf(1.0, 2.0, 3.0, 4.0) else listOf(1.0, 2.756, 5.404, 8.933)
            for (ratio in ratios) {
                val expected = s.referenceHz * ratio
                val bin = (expected * re.size / rate).roundToInt()
                val peak = (bin - 8..bin + 8).maxBy { re[it].toDouble().pow(2) + im[it].toDouble().pow(2) }
                assertTrue(abs(peak * rate.toDouble() / re.size - expected) < 2 * rate.toDouble() / re.size,
                    "body=$body tune=$tune reference resonance ratio=$ratio")
            }
        }
    }
}
