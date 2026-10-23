package com.snipsnap.audio

import kotlin.math.*
import kotlin.test.*

class ResonantBodyTest {
    private fun impulse(rate: Int = 44_100, channels: Int = 1, frames: Int = 128) =
        Snip(FloatArray(frames * channels).also { it[0] = 0.8f }, channels, rate)

    private fun tone(hz: Double, rate: Int = 44_100, seconds: Double = 0.2, amplitude: Double = 0.5) =
        Snip(FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }, 1, rate)

    @Test fun `zero mix preserves the exact source without PCM format validation or cache work`() {
        val processor = ResonantBody()
        for (source in listOf(Snip(floatArrayOf(-0f, Float.NaN, Float.POSITIVE_INFINITY, 9f), 2, 123), Snip(floatArrayOf(), 1, 1))) {
            val result = processor.process(source, BodySettings(mix = 0f), captureWet = true)
            assertSame(source, result.audio)
            assertSame(source.samples, result.audio.samples)
            assertNull(result.rawWet)
            assertEquals(0, result.diagnostics.activeModes)
            assertEquals(0, result.diagnostics.tailFrames)
        }
        assertFalse(processor.process(impulse()).diagnostics.coefficientCacheHit)
    }

    @Test fun `bad settings and enabled PCM refuse rather than sanitizing audio`() {
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { BodySettings(tune = v) }
            assertFailsWith<IllegalArgumentException> { BodySettings(body = v) }
            assertFailsWith<IllegalArgumentException> { BodySettings(ring = v) }
            assertFailsWith<IllegalArgumentException> { BodySettings(damp = v) }
            assertFailsWith<IllegalArgumentException> { BodySettings(mix = v) }
        }
        assertFailsWith<IllegalArgumentException> { BodySettings(modelVersion = 2) }
        assertFailsWith<IllegalArgumentException> { BodySettings(to = BodyFamily.HARMONIC) }
        val processor = ResonantBody()
        for (source in listOf(Snip(floatArrayOf(), 1, 44_100), impulse(22_050),
            Snip(FloatArray(8 * 44_100 + 1), 1, 44_100),
            Snip(floatArrayOf(Float.NaN), 1, 44_100), Snip(floatArrayOf(Float.NEGATIVE_INFINITY), 1, 44_100),
            Snip(floatArrayOf(1.01f), 1, 44_100))) {
            assertFailsWith<IllegalArgumentException> { processor.process(source) }
        }
    }

    @Test fun `impulse agrees with the closed form rather than a second recurrence`() {
        val settings = BodySettings(body = 0.5f, ring = 1f, damp = 0.4f, mix = 1f)
        val result = ResonantBody().process(impulse(frames = 1), settings, captureWet = true)
        val ratios = listOf(1.0, 2.0, 3.0, 4.0, 1.0, 2.756, 5.404, 8.933)
        val weights = listOf(1.0, 0.65, 0.4, 0.25)
        for (n in 0 until 2000) {
            val expected = ratios.indices.sumOf { mode ->
                val ratio = ratios[mode]
                val t60 = max(0.01, settings.rootT60 / (1 + 4 * settings.damp * log2(ratio)))
                val radius = exp(-ln(1000.0) / (t60 * 44_100))
                4 * 0.8f.toDouble() * 0.5 * weights[mode % 4] / weights.sum() * (1 - radius) *
                    radius.pow(n) * sin((n + 1) * 2 * PI * settings.referenceHz * ratio / 44_100)
            }
            assertEquals(expected, result.rawWet!!.samples[n].toDouble(), 1e-8, "frame $n")
        }
        assertEquals(1 + 26_460, result.audio.frameCount)
        assertEquals(0f, result.audio.samples.last())
    }

    @Test fun `bounded states and exact repeatability survive the control cube at both rates and layouts`() {
        val processor = ResonantBody()
        for (rate in listOf(44_100, 48_000)) for (channels in 1..2)
            for (tune in listOf(0f, 1f)) for (body in listOf(0f, 0.5f, 1f))
                for (ring in listOf(0f, 1f)) for (damp in listOf(0f, 1f)) {
                    val source = impulse(rate, channels)
                    val settings = BodySettings(tune = tune, body = body, ring = ring, damp = damp, mix = 1f)
                    val result = processor.process(source, settings)
                    val repeated = processor.process(source, settings)
                    assertContentEquals(result.audio.samples, repeated.audio.samples)
                    assertTrue(result.diagnostics.statePeak <= source.peak() * (1 + 1e-9) + 1e-12)
                    assertTrue(result.audio.samples.all { it.isFinite() })
                    assertTrue(result.audio.peak() <= 0.980001f)
                    assertEquals(channels, result.audio.channels)
                    assertEquals(rate, result.audio.sampleRate)
                    assertEquals(source.frameCount + ceil(settings.rootT60 * rate).toInt(), result.audio.frameCount)
                    assertEquals(0f, result.audio.samples.last())
                }
    }

    @Test fun `stereo has no crossfeed and equal channels remain exactly equal`() {
        val mono = tone(130.8128)
        val left = Snip(FloatArray(mono.frameCount * 2) { if (it % 2 == 0) mono.samples[it / 2] else 0f }, 2, mono.sampleRate)
        val equal = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, mono.sampleRate)
        val processor = ResonantBody()
        val a = processor.process(left).audio
        val b = processor.process(equal).audio
        for (frame in 0 until a.frameCount) {
            assertEquals(0f, a.samples[2 * frame + 1])
            assertEquals(b.samples[2 * frame], b.samples[2 * frame + 1])
        }
        val silence = processor.process(Snip(FloatArray(100), 2, 48_000), captureWet = true)
        assertTrue(silence.audio.samples.all { it.toRawBits() == 0 })
        assertTrue(silence.rawWet!!.samples.all { it.toRawBits() == 0 })
    }

    @Test fun `low-level dynamics stay linear and delivery attenuation is shared by stereo`() {
        val processor = ResonantBody()
        val source = tone(130.8128, amplitude = 0.02)
        val full = processor.process(source, BodySettings(body = 0.5f))
        val half = processor.process(source.copy(samples = source.samples.map { it * 0.5f }.toFloatArray()), BodySettings(body = 0.5f))
        assertEquals(1.0, full.diagnostics.attenuation)
        for (i in full.audio.samples.indices) assertEquals(full.audio.samples[i] * 0.5f, half.audio.samples[i], 1e-8f)
        // The square wave has enough fundamental energy to exercise the delivery guard.
        val square = tone(130.8128, seconds = 1.0).samples.map { if (it >= 0) 1f else -1f }.toFloatArray()
        val stereo = Snip(FloatArray(square.size * 2) { if (it % 2 == 0) square[it / 2] else square[it / 2] * 0.25f }, 2, 44_100)
        val guarded = processor.process(stereo, BodySettings(ring = 1f, mix = 1f))
        assertTrue(guarded.diagnostics.attenuation < 1, "the loud fixture must exercise the guard")
        assertTrue(guarded.audio.peak() <= 0.980001f)
        assertTrue(guarded.diagnostics.statePeak <= 1 + 1e-9)
        for (frame in 0 until guarded.audio.frameCount) {
            assertEquals(guarded.audio.samples[2 * frame] * 0.25f, guarded.audio.samples[2 * frame + 1], 1e-7f)
        }
    }

    @Test fun `all mode frequencies have the expected impulse spectrum across the body blend`() {
        for (tune in listOf(0f, 1f / 3f, 1f)) for (body in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val s = BodySettings(tune = tune, body = body, ring = 1f, damp = 0f, mix = 1f)
            val wet = ResonantBody().process(impulse(frames = 1), s).audio
            val re = wet.samples.copyOf(65_536)
            val im = FloatArray(re.size)
            Fft.forward(re, im)
            val ratios = (if (body < 1) listOf(1.0, 2.0, 3.0, 4.0) else emptyList()) +
                (if (body > 0) listOf(1.0, 2.756, 5.404, 8.933) else emptyList())
            for (ratio in ratios) {
                val expected = s.referenceHz * ratio
                val bin = (expected * re.size / 44_100).roundToInt()
                val found = (bin - 8..bin + 8).maxBy { re[it].toDouble().pow(2) + im[it].toDouble().pow(2) }
                assertTrue(abs(found * 44_100.0 / re.size - expected) < 2 * 44_100.0 / re.size, "$tune/$body ratio $ratio")
            }
        }
    }

    @Test fun `confident harmonic estimates meet the cents gate and low-register refusals are explicit`() {
        val refused = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        for (tune in listOf(0f, 1f / 3f, 1f)) for (ring in listOf(0f, 0.5f, 1f)) for (damp in listOf(0f, 0.5f, 1f)) {
            val s = BodySettings(tune = tune, ring = ring, damp = damp, mix = 1f)
            val output = ResonantBody().process(impulse(frames = 256), s).audio
            val estimate = Pitch.detect(output, fromSec = 0.002f)
            if (estimate == null || estimate.confidence < 0.3f) {
                refused += "midi=${s.rootMidi},ring=$ring,damp=$damp"
                continue
            }
            val cents = 1200 * log2(estimate.hz / s.referenceHz)
            if (abs(cents) > 25) wrong += "$s: ${estimate.hz} Hz, $cents cents, confidence ${estimate.confidence}"
        }
        println("Harmonic pitch refusals (not pitch passes): $refused")
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertTrue(refused.all { it.startsWith("midi=36,") || "ring=0.0" in it },
            "unexpected detector refusal outside the disclosed low-register or short-decay cases: $refused")
        assertTrue(refused.size <= 10, "at least 17 of the 27 pitch fixtures must retain a confident estimate")
    }

    @Test fun `controls change matched sound and a frozen-body mutant is detected`() {
        fun distance(a: FloatArray, b: FloatArray): Double {
            val ar = sqrt(a.sumOf { it.toDouble().pow(2) })
            val br = sqrt(b.sumOf { it.toDouble().pow(2) })
            return sqrt(a.indices.sumOf { (a[it] / ar - b[it] / br).pow(2) })
        }
        val processor = ResonantBody()
        fun render(body: Float, ring: Float = 1f, damp: Float = 0.5f) =
            processor.process(impulse(), BodySettings(body = body, ring = ring, damp = damp, mix = 1f)).audio.samples
        val endpoints = listOf(0f, 0.5f, 1f).map { render(it) }
        assertTrue(distance(endpoints[0], endpoints[1]) > 0.01)
        assertTrue(distance(endpoints[1], endpoints[2]) > 0.01)
        assertFalse(distance(endpoints[0], endpoints[0]) > 0.01, "a frozen control cannot pass activity")
        assertTrue(distance(render(1f, damp = 0f), render(1f, damp = 1f)) > 0.01)
        val short = processor.process(impulse(), BodySettings(ring = 0f)).audio
        val long = processor.process(impulse(), BodySettings(ring = 1f)).audio
        assertTrue(long.frameCount > short.frameCount + 20_000)
    }

    @Test fun `decay agrees with the free-state envelope and an unstable radius fails the independent window`() {
        val s = BodySettings(ring = 1f, damp = 0f, mix = 1f)
        val out = ResonantBody().process(impulse(frames = 1), s).audio
        fun windowEnergy(samples: FloatArray, from: Int): Double =
            (from until from + 512).sumOf { samples[it].toDouble().pow(2) }
        val a = windowEnergy(out.samples, 2000)
        val b = windowEnergy(out.samples, 10_820) // 0.2 seconds later, well before the wet fade
        val measuredT60 = -6 * 0.2 / log10(b / a)
        assertEquals(0.6, measuredT60, 0.06)
        fun decays(samples: FloatArray): Boolean = windowEnergy(samples, 10_820) < windowEnergy(samples, 2000)
        assertTrue(decays(out.samples))
        val unstable = FloatArray(out.frameCount) { n ->
            (1.0001.pow(n) * sin(2 * PI * s.referenceHz * n / 44_100)).toFloat()
        }
        assertFalse(decays(unstable), "a test-only pole above one must fail the same independent-window observation")
        // Observe both ends of RING on real PCM, not only coefficients or appended frame count.
        for (ring in listOf(0f, 0.5f, 1f)) {
            val controls = BodySettings(tune = 1f, ring = ring, damp = 0f, mix = 1f)
            val rendered = ResonantBody().process(impulse(frames = 1), controls).audio.samples
            val period = 44_100 / controls.referenceHz
            val width = (4 * period).roundToInt()
            val start = period.roundToInt()
            val delta = (floor(controls.rootT60 * 44_100 / (2 * period)) * period).roundToInt()
            fun energy(at: Int) = (at until at + width).sumOf { rendered[it].toDouble().pow(2) }
            val measured = -6 * (delta / 44_100.0) / log10(energy(start + delta) / energy(start))
            assertEquals(controls.rootT60, measured, controls.rootT60 * 0.1, "rendered RING=$ring slope")
        }
        for (damp in listOf(0f, 0.5f, 1f)) {
            val key = BodyCoefficientKey(BodyModels.identity(s), 1, 44_100, s.referenceHz, s.rootT60, damp.toDouble())
            for (mode in bodyCoefficients(key)) {
                val seconds = ln(0.001) / ln(mode.radius) / 44_100
                assertEquals(mode.t60, seconds, mode.t60 * 0.02 + 1.0 / 44_100)
            }
        }
    }

    @Test fun `zero-mean sources remain within the DC gate and no dry timing is changed`() {
        val processor = ResonantBody()
        for (s in listOf(BodySettings(mix = 1f), BodySettings(body = 0.5f), BodySettings(body = 1f, ring = 1f))) {
            val source = tone(s.referenceHz, seconds = 1.0, amplitude = 0.1)
            val result = processor.process(source, s, captureWet = true)
            assertTrue(abs(result.audio.samples.average()) <= 0.005)
            for (i in source.samples.indices step 101) {
                val expected = ((1 - s.mix) * source.samples[i] + s.mix * result.rawWet!!.samples[i]) * result.diagnostics.attenuation
                assertEquals(expected, result.audio.samples[i].toDouble(), 1e-7)
            }
        }
        for (rate in listOf(44_100, 48_000)) {
            val longest = Snip(FloatArray(8 * rate), 1, rate)
            assertEquals((8.6 * rate).roundToInt(), processor.process(longest, BodySettings(ring = 1f)).audio.frameCount)
        }
    }

    @Test fun `sustained excitation on and between resonances obeys the pre-guard state bound`() {
        val processor = ResonantBody()
        for (rate in listOf(44_100, 48_000)) for (tune in listOf(0f, 1f)) {
            val s = BodySettings(tune = tune, body = 0.5f, ring = 1f, damp = 0f, mix = 1f)
            for (ratio in listOf(1.0, 2.0, 2.756, 3.0, 4.0, 5.404, 8.933, 1.03)) {
                val result = processor.process(tone(s.referenceHz * ratio, rate, 0.4, 1.0), s)
                assertTrue(result.diagnostics.statePeak <= result.diagnostics.inputPeak * (1 + 1e-9) + 1e-12)
            }
            val dc = processor.process(Snip(FloatArray(rate / 2) { 1f }, 1, rate), s)
            assertTrue(dc.diagnostics.statePeak <= 1 + 1e-9)
        }
    }

    @Test fun `a stationary bank adds no new spectral lines to a coherent high multitone`() {
        val size = 16_384
        val rate = 44_100
        val bins = listOf(4000, 7000)
        val source = Snip(FloatArray(size * 3) { n -> bins.sumOf { 0.2 * sin(2 * PI * it * n / size) }.toFloat() }, 1, rate)
        val result = ResonantBody().process(source, BodySettings(body = 0.5f, ring = 0f, mix = 1f))
        val re = result.audio.samples.copyOfRange(size, 2 * size)
        val im = FloatArray(size)
        Fft.forward(re, im)
        fun power(bin: Int) = re[bin].toDouble().pow(2) + im[bin].toDouble().pow(2)
        val signal = bins.sumOf(::power)
        val extra = (0 until size / 2).filter { it !in bins }.maxOf(::power)
        assertTrue(extra / signal < 1e-8, "new-line power relative to input-line power: ${extra / signal}")
    }
}
