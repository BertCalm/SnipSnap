package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Signal contracts for an input effect, not a pitched Patch or a held loop.
 * Python fixtures independently pin the approved sound and seeded schedule;
 * the other probes check the behavior those arrays alone cannot explain.
 */
class ObliterateCoreTest {
    private val rate = 44_100

    private fun tone(hz: Double = 330.0, seconds: Double = .5, gain: Double = .2): Snip =
        Snip(FloatArray((seconds * rate).roundToInt()) { frame ->
            (gain * sin(2.0 * PI * hz * frame / rate)).toFloat()
        }, 1, rate)

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun difference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        return sqrt(a.indices.sumOf { i -> (a[i].toDouble() - b[i]).let { it * it } } / a.size)
    }

    private fun channelMean(snip: Snip, from: Int, until: Int, channel: Int): Double =
        (from until until).sumOf { snip.samples[it * snip.channels + channel].toDouble() } / (until - from)

    private fun resource(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/obliterate-core/$name"),
    ) { "missing independent Python fixture $name" }.use { it.readBytes() }

    private val reference by lazy { Json.parse(resource("reference-manifest.json").toString(Charsets.UTF_8)).obj() }

    private fun fixtureSettings(case: Map<String, JsonValue>) = ObliterateCore.Settings(
        amount = case.getValue("amount").num(), bpm = case.getValue("bpm").num(),
        division = case.getValue("division").int(), seed = case.getValue("seed").long(),
    )

    private fun fixturePcm(case: Map<String, JsonValue>, kind: String): FloatArray {
        val name = case.getValue("${kind}File").str()
        val bytes = resource(name)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(case.getValue("${kind}Sha256").str(), digest, "fixture $name changed")
        assertEquals(0, bytes.size % 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    private fun assertReferenceEvents(expected: List<JsonValue>, actual: List<ObliterateCore.GrainEvent>, id: String) {
        assertEquals(expected.size, actual.size, "$id event count")
        for ((index, pair) in expected.zip(actual).withIndex()) {
            val e = pair.first.obj()
            val a = pair.second
            val where = "$id event $index"
            assertEquals(e.getValue("onset_frame").int(), a.onsetFrame, "$where onset")
            assertEquals(e.getValue("length_frames").int(), a.lengthFrames, "$where length")
            assertEquals(e.getValue("source_first_frame").num(), a.sourceFirstFrame, 1e-8, "$where first read")
            assertEquals(e.getValue("source_last_frame").num(), a.sourceLastFrame, 1e-8, "$where last read")
            assertEquals(e.getValue("pitch_ratio").num(), a.pitchRatio, 1e-12, "$where pitch")
            assertEquals(e.getValue("semitones").num(), a.semitones, 1e-12, "$where interval")
            assertEquals(e.getValue("reverse").bool(), a.reverse, "$where direction")
            assertEquals(e.getValue("balance").num(), a.balance, 1e-12, "$where balance")
        }
    }

    @Test
    fun `zero is a same object hard bypass with original channels and signed zero`() {
        for (channels in 1..2) for (stages in 1..3) {
            val source = Snip(floatArrayOf(-0.0f, .3f, -.7f, 0f), channels, rate)
            val bits = source.samples.map { it.toRawBits() }
            val result = ObliterateCore.render(source, ObliterateCore.Settings(amount = 0.0), stages)
            assertSame(source, result.audio)
            assertEquals(bits, result.audio.samples.map { it.toRawBits() })
            assertTrue(result.diagnostics.bypass)
            assertEquals(0, result.diagnostics.tailFrames)
            assertEquals(1.0, result.diagnostics.guardGain)
            assertTrue(result.diagnostics.grainEvents.isEmpty())
        }
    }

    @Test
    fun `settings reject unsupported amounts clocks seeds and stages`() {
        assertEquals(.35, ObliterateCore.Settings().amount, "the Kotlin port starts at the approved 35 percent setting")
        assertEquals(120.0, ObliterateCore.Settings().bpm)
        assertEquals(16, ObliterateCore.Settings().division)
        assertEquals(20261023L, ObliterateCore.Settings().seed)
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.001, 1.001)) {
            assertFailsWith<IllegalArgumentException> { ObliterateCore.Settings(amount = amount) }
        }
        for (bpm in listOf(Double.NaN, Double.POSITIVE_INFINITY, 39.999, 240.001)) {
            assertFailsWith<IllegalArgumentException> { ObliterateCore.Settings(bpm = bpm) }
        }
        for (division in listOf(0, 3, 12, 64)) {
            assertFailsWith<IllegalArgumentException> { ObliterateCore.Settings(division = division) }
        }
        for (seed in listOf(-1L, 4_294_967_296L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ObliterateCore.Settings(seed = seed) }
        }
        // Both unsigned-32 endpoints are valid, without a signed Int narrowing.
        assertEquals(0L, ObliterateCore.Settings(seed = 0).seed)
        assertEquals(4_294_967_295L, ObliterateCore.Settings(seed = 4_294_967_295L).seed)
        val source = tone(seconds = .01)
        for (stages in listOf(0, 4)) {
            assertFailsWith<IllegalArgumentException> { ObliterateCore.render(source, stages = stages) }
        }
    }

    @Test
    fun `render refuses nonnative rate empty invalid and overbudget PCM`() {
        for (amount in listOf(0.0, .35)) {
            val settings = ObliterateCore.Settings(amount = amount)
            for (sampleRate in listOf(8_000, 48_000, 96_000)) {
                assertFailsWith<IllegalArgumentException> {
                    ObliterateCore.render(Snip(floatArrayOf(.1f), 1, sampleRate), settings)
                }
            }
            for (samples in listOf(
                FloatArray(0), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY),
                floatArrayOf(Float.NEGATIVE_INFINITY), floatArrayOf(1.0001f), floatArrayOf(-1.0001f),
            )) {
                assertFailsWith<IllegalArgumentException> { ObliterateCore.render(Snip(samples, 1, rate), settings) }
            }
            assertFailsWith<IllegalArgumentException> {
                ObliterateCore.render(Snip(FloatArray(12 * rate + 1), 1, rate), settings)
            }
        }
    }

    @Test
    fun `silence remains exact silence through every stage with a fixed finite tail`() {
        for (channels in 1..2) for (stages in 1..3) {
            val source = Snip(FloatArray(700 * channels), channels, rate)
            val result = ObliterateCore.render(source, ObliterateCore.Settings(amount = 1.0), stages)
            assertEquals(2, result.audio.channels)
            assertEquals(rate, result.audio.sampleRate)
            assertEquals(700 + 33_075, result.audio.frameCount)
            assertEquals(33_075, result.diagnostics.tailFrames)
            assertTrue(result.audio.samples.all { it.isFinite() && it == 0f })
            assertEquals(1.0, result.diagnostics.guardGain)
            assertFalse(result.diagnostics.bypass)
        }
    }

    @Test
    fun `fresh state is deterministic and another seed changes the fragments`() {
        val source = tone(seconds = .6)
        val before = source.samples.copyOf()
        val settings = ObliterateCore.Settings(amount = .7)
        val first = ObliterateCore.render(source, settings)
        ObliterateCore.render(tone(900.0, .1), ObliterateCore.Settings(1.0, 143.0, 32, 8))
        val second = ObliterateCore.render(source, settings)
        assertContentEquals(first.audio.samples, second.audio.samples)
        assertEquals(first.diagnostics, second.diagnostics)
        assertContentEquals(before, source.samples, "the pre-board source remains available for later edits")
        val changed = ObliterateCore.render(source, settings.copy(seed = 20))
        assertTrue(first.diagnostics.grainEvents != changed.diagnostics.grainEvents)
        assertTrue(difference(first.audio.samples, changed.audio.samples) > .001, "seed changes must reach the sound")
    }

    @Test
    fun `the three intensities change audio without clipping or leaving a tail step`() {
        val dry = tone(164.81, .7, .4)
        val source = dry.copy(samples = FloatArray(dry.frameCount) { i ->
            (dry.samples[i] * exp(-i / 9000.0)).toFloat()
        })
        val renders = listOf(.35, .7, 1.0).map { amount ->
            ObliterateCore.render(source, ObliterateCore.Settings(amount)).audio
        }
        for (audio in renders) {
            assertEquals(source.frameCount + 33_075, audio.frameCount)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.peak() <= .980001f)
            assertTrue(audio.samples.takeLast(2).all { abs(it) == 0f })
            assertTrue(audio.samples.takeLast(2 * 4410).all { abs(it) < 1e-6f }, "filters must release inside the fixed budget")
            for (channel in 0..1) assertTrue(abs(channelMean(audio, 0, audio.frameCount, channel)) < .005)
            assertTrue(difference(
                FloatArray(audio.frameCount) { audio.samples[2 * it] },
                FloatArray(audio.frameCount) { audio.samples[2 * it + 1] },
            ) > .001, "mono input should acquire an audible stereo difference")
        }
        assertTrue(difference(renders[0].samples, renders[1].samples) > .005)
        assertTrue(difference(renders[1].samples, renders[2].samples) > .005)
    }

    @Test
    fun `peak protection engages on a real step transient and never makes quiet PCM louder`() {
        // A settled negative DC input followed by a positive step excites the
        // causal high-pass. This exercises the guard, rather than only testing
        // that an unneeded gain happens to equal one on an already quiet tone.
        val source = Snip(FloatArray(8820) { if (it < 4410) -1f else 1f }, 1, rate)
        val result = ObliterateCore.render(source, ObliterateCore.Settings(amount = .01), stages = 1)
        val d = result.diagnostics
        assertTrue(d.preGuardPeak > .98, "fixture must require attenuation")
        assertTrue(d.guardGain > 0.0 && d.guardGain < 1.0)
        assertEquals(.98 / d.preGuardPeak, d.guardGain, 1e-12)
        assertEquals(.98, result.audio.peak().toDouble(), 1e-6)
        val quiet = ObliterateCore.render(source.copy(samples = FloatArray(source.frameCount) { source.samples[it] * .001f }),
            ObliterateCore.Settings(amount = .01), stages = 1)
        assertEquals(1.0, quiet.diagnostics.guardGain, "peak protection must not normalize a quiet source upward")
    }

    @Test
    fun `stereo is processed without folding or crossfeeding channels`() {
        val mono = tone()
        val leftOnly = Snip(FloatArray(mono.frameCount * 2) { i -> if (i % 2 == 0) mono.samples[i / 2] else 0f }, 2, rate)
        val rendered = ObliterateCore.render(leftOnly).audio
        assertTrue((0 until rendered.frameCount).all { rendered.samples[2 * it + 1] == 0f })
        assertTrue((0 until rendered.frameCount).any { rendered.samples[2 * it] != 0f })
        val antiphase = leftOnly.copy(samples = FloatArray(mono.frameCount * 2) { i ->
            mono.samples[i / 2] * if (i % 2 == 0) 1f else -1f
        })
        assertTrue(rms(ObliterateCore.render(antiphase).audio.samples) > .005,
            "antiphase input must drive the effect instead of cancelling in a mono fold")
    }

    @Test
    fun `very short sources keep their allocations valid at the fastest clock`() {
        for (frames in listOf(1, 2, 31, 100, 700, 2000)) {
            val source = Snip(FloatArray(frames) { .1f }, 1, rate)
            val rendered = ObliterateCore.render(source, ObliterateCore.Settings(1.0, 240.0, 32)).audio
            assertEquals(frames + 33_075, rendered.frameCount)
            assertTrue(rendered.samples.all { it.isFinite() })
        }
    }

    @Test
    fun `schedule captures only past recent samples and bounds all interpolated reads and tails`() {
        val frames = 12 * rate
        for (amount in listOf(.35, .5, .7, 1.0)) for ((bpm, division) in listOf(40.0 to 4, 123.0 to 16, 240.0 to 32)) {
            val events = ObliterateCore.schedule(frames, ObliterateCore.Settings(amount, bpm, division))
            assertTrue(events.isNotEmpty())
            assertTrue(events.size <= 1024)
            assertTrue(events.zipWithNext().all { (a, b) -> a.onsetFrame < b.onsetFrame })
            if (amount == .5) {
                // Python round(4630.5) is ties-to-even. Ordinary Kotlin
                // roundToInt would print a grain one frame longer at AMT50%.
                assertEquals(4630, events.maxOf { it.lengthFrames }, "AMT50% must retain Python's half-sample rounding")
            }
            for (event in events) {
                val lower = minOf(event.sourceFirstFrame, event.sourceLastFrame)
                val upper = maxOf(event.sourceFirstFrame, event.sourceLastFrame)
                assertTrue(lower >= maxOf(0, event.onsetFrame - 14_112) - 1e-8)
                assertTrue(floor(upper).toInt() + 1 < event.onsetFrame, "linear interpolation's second read must also predate capture")
                assertTrue(event.onsetFrame < frames)
                assertTrue(event.onsetFrame + event.lengthFrames <= frames + 33_075)
                assertEquals(event.reverse, event.sourceLastFrame < event.sourceFirstFrame)
            }
        }
    }

    @Test
    fun `memory and chop preserve quiet scaling without per grain normalization`() {
        val frames = rate / 2 + 33_075
        val source = Array(2) { channel -> DoubleArray(frames) { frame ->
            if (frame < rate / 2) .2 * sin(2.0 * PI * 330 * frame / rate) * if (channel == 0) 1.0 else -.6 else 0.0
        } }
        val before = source.map { it.copyOf() }
        val settings = ObliterateCore.Settings(amount = .7)
        val events = ObliterateCore.schedule(rate / 2, settings)
        val a = ObliterateCore.fragments(source, settings, events)
        val b = ObliterateCore.fragments(Array(2) { ch -> DoubleArray(frames) { source[ch][it] * .01 } }, settings, events)
        val gains = ObliterateCore.chopGains(frames, settings)
        var memoryError = 0.0
        var choppedError = 0.0
        for (ch in 0..1) for (f in 0 until frames) {
            memoryError = maxOf(memoryError, abs(a[ch][f] * .01 - b[ch][f]))
            choppedError = maxOf(choppedError, abs(a[ch][f] * gains[ch][f] * .01 - b[ch][f] * gains[ch][f]))
        }
        assertTrue(memoryError < 1e-12, "memory normalization changed quiet scaling: $memoryError")
        assertTrue(choppedError < 1e-12, "chop changed quiet scaling: $choppedError")
        for (ch in 0..1) assertContentEquals(before[ch], source[ch])
    }

    @Test
    fun `saved sixteenth clock alternates stereo and retains its fractional period`() {
        val gains = ObliterateCore.chopGains(rate, ObliterateCore.Settings(1.0, 120.0, 16))
        for (cycle in 2..5) {
            val leftOpen = ((cycle * .125 + .020) * rate).roundToInt()
            val rightOpen = ((cycle * .125 + .085) * rate).roundToInt()
            assertTrue(gains[0][leftOpen] > .999)
            assertEquals(0.0, gains[1][leftOpen])
            assertEquals(0.0, gains[0][rightOpen])
            assertTrue(gains[1][rightOpen] > .999)
        }
        for (channel in gains) assertTrue(channel.take(794).all { it == 1.0 }, "first 18 ms preserve the source attack")
        val step = 60.0 / 123.0 * 4.0 / 32
        val fractional = ObliterateCore.chopGains(12 * rate, ObliterateCore.Settings(1.0, 123.0, 32))
        for (cycle in listOf(2, 50, 100, 190)) {
            val opened = ((cycle * step + .010) * rate).roundToInt()
            val closed = ((cycle * step + .8 * step) * rate).roundToInt()
            assertTrue(fractional[0][opened] > .999, "cycle $cycle must follow absolute musical time")
            assertEquals(0.0, fractional[0][closed])
        }
        val fast = ObliterateCore.chopGains(rate, ObliterateCore.Settings(1.0, 240.0, 32))
        assertTrue(fast.all { channel -> channel.all { it in 0.0..1.0 } })
        assertTrue(fast.all { channel -> (4411 until rate).all { abs(channel[it] - channel[it - 1]) < .013 } },
            "3 ms edges must not become one-sample hard gates")
    }

    @Test
    fun `octave fuzz adds an even harmonic and absolute drive keeps input level sensitivity`() {
        val source = DoubleArray(rate) { .2 * sin(2.0 * PI * 440 * it / rate) }
        val fuzz = ObliterateCore.fuzz(source, 1.0)
        fun amplitude(hz: Double): Double {
            var real = 0.0
            var imaginary = 0.0
            for (i in 4410 until rate - 4410) {
                val angle = 2.0 * PI * hz * i / rate
                real += fuzz[i] * cos(angle)
                imaginary += fuzz[i] * sin(angle)
            }
            return sqrt(real * real + imaginary * imaginary)
        }
        assertTrue(amplitude(880.0) > amplitude(440.0) * 2, "ordinary odd-harmonic clipping would not meet this octave claim")
        val quiet = ObliterateCore.fuzz(DoubleArray(rate) { source[it] * .1 }, 1.0)
        val error = sqrt(source.indices.sumOf { i -> (quiet[i] - fuzz[i] * .1).let { it * it } } / rate)
        assertTrue(error > .005, "drive is absolute, not a peak-relative gain-invariant shaper")
    }

    @Test
    fun `DC input settles and its release dies inside the printed tail`() {
        val result = ObliterateCore.render(Snip(FloatArray(rate * 2) { .1f }, 2, rate), ObliterateCore.Settings(1.0))
        for (channel in 0..1) {
            assertTrue(abs(channelMean(result.audio, rate / 2, rate, channel)) < 1e-5)
            assertTrue((result.audio.frameCount - 4410 until result.audio.frameCount).all {
                abs(result.audio.samples[2 * it + channel]) < 1e-7f
            })
        }
        assertFalse(result.diagnostics.streamingCausal, "offline FIR compensation and whole-file guard are disclosed")
        assertTrue(result.diagnostics.grainReadsBeforeEventOnset)
    }

    @Test
    fun `Kotlin audio and event schedules match the independent Python Float32 boundary fixtures`() {
        assertEquals(1, reference.getValue("schema").int())
        val cases = reference.getValue("cases").arr().map { it.obj() }
        assertEquals(7, cases.size, "retain all stages, amounts, source channel shapes and fractional-clock cases")
        for (case in cases) {
            val id = case.getValue("id").str()
            val input = fixturePcm(case, "input")
            val expected = fixturePcm(case, "output")
            val channels = case.getValue("inputChannels").int()
            assertEquals(case.getValue("inputFrames").int() * channels, input.size, "$id input shape")
            val result = ObliterateCore.render(Snip(input, channels, rate), fixtureSettings(case), case.getValue("stages").int())
            assertEquals(case.getValue("outputChannels").int(), result.audio.channels, "$id channels")
            assertEquals(case.getValue("outputFrames").int(), result.audio.frameCount, "$id frames")
            assertEquals(expected.size, result.audio.samples.size)
            var maxError = 0.0
            var squareError = 0.0
            for (i in expected.indices) {
                val error = abs(result.audio.samples[i].toDouble() - expected[i])
                maxError = maxOf(maxError, error)
                squareError += error * error
            }
            val rmsError = sqrt(squareError / expected.size)
            // This bounds cross-language numerical error, not PCM hash equality.
            // The reference starts from the exact same Float32 Snip input.
            assertTrue(maxError <= 2e-7, "$id Python max sample error $maxError")
            assertTrue(rmsError <= 2e-9, "$id Python RMS sample error $rmsError")
            assertEquals(case.getValue("guardGain").num(), result.diagnostics.guardGain, 1e-10, "$id guard")
            assertEquals(case.getValue("clockStepSeconds").num(), result.diagnostics.clockStepSeconds, 1e-14, "$id clock")
            assertReferenceEvents(case.getValue("grainEvents").arr(), result.diagnostics.grainEvents, id)
            println("OBLITERATE Python parity $id: max=$maxError rms=$rmsError")
        }
    }

    @Test
    fun `NumPy schedule goldens retain unsigned seeds and random draws consumed by skipped grains`() {
        val cases = reference.getValue("scheduleCases").arr().map { it.obj() }
        assertTrue(cases.size >= 5)
        val seeds = cases.map { it.getValue("seed").long() }.toSet()
        assertTrue(seeds.containsAll(listOf(0L, 1L, 20261023L, 4_294_967_295L)))
        assertTrue(cases.any { it.getValue("inputFrames").int() == 2000 })
        for ((index, case) in cases.withIndex()) {
            val events = ObliterateCore.schedule(case.getValue("inputFrames").int(), fixtureSettings(case))
            assertReferenceEvents(case.getValue("grainEvents").arr(), events, "schedule golden $index")
        }
    }
}
