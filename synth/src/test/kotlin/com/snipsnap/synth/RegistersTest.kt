package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guard the prototype's actual pre-level sound and genuine independently generated loop evidence. */
class RegistersTest {
    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0

    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it }

    private fun unitDifference(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ea = sqrt(a.take(n).sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
        val eb = sqrt(b.take(n).sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
        return (0 until n).sumOf { (a[it] / ea - b[it] / eb).let { d -> d * d } }
    }

    private fun measuredFundamental(samples: FloatArray, hz: Double): Double {
        val n = 65536
        val count = minOf(32768, samples.size - Dsp.RATE / 3)
        val first = Dsp.RATE / 3
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val binHz = Dsp.RATE.toDouble() / n
        val low = ((hz - 10) / binHz).roundToInt().coerceAtLeast(1)
        val high = ((hz + 10) / binHz).roundToInt().coerceAtMost(n / 2 - 2)
        val powers = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val k = (low..high).maxBy { powers[it] }
        val a = ln(powers[k - 1].coerceAtLeast(1e-30))
        val b = ln(powers[k].coerceAtLeast(1e-30))
        val c = ln(powers[k + 1].coerceAtLeast(1e-30))
        val offset = (.5 * (a - c) / (a - 2 * b + c)).coerceIn(-.5, .5)
        return (k + offset) * binHz
    }

    @Test
    fun `fresh finite renders and serialized recipes replay bit for bit`() {
        for (voice in RegistersVoice.entries) {
            val macros = Registers.defaults(voice) + mapOf("COLOR" to .63f, "AIR" to .72f, "EDGE" to .48f)
            val patch = RegistersPatch("Speech and beating", voice, macros, 60, .73f)
            val saved = RegistersPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, saved)
            val first = patch.render()
            assertContentEquals(first.samples, saved.render().samples)
            assertEquals(Dsp.RATE, first.sampleRate)
            assertEquals(1, first.channels)
            assertTrue(first.samples.all { it.isFinite() })
            assertTrue(peak(first.samples) <= .991)
            assertTrue(first.frameCount.toDouble() / Dsp.RATE in 2.0..3.0)
            assertTrue(abs(first.samples.last()) < 1e-6f, "finite release must reach silence")
        }
        val partial = RegistersPatch("Defaults saved", RegistersVoice.PIPE, mapOf("AIR" to .6f))
        val saved = RegistersPatch.fromJsonText(partial.toJsonText())
        assertEquals(Registers.defaults(RegistersVoice.PIPE).keys, saved.macros.keys)
        assertContentEquals(partial.probe().samples, saved.probe().samples)
        assertFailsWith<JsonException> { RegistersPatch.fromJsonText(partial.toJsonText().replace("\"model\": ${Registers.MODEL_VERSION}", "\"model\": 999")) }
        assertFailsWith<IllegalArgumentException> { RegistersPatch("Bad", RegistersVoice.PIPE, mapOf("AIR" to Float.NaN)) }
    }

    @Test
    fun `sustained timbral controls change the signal after gain removal`() {
        for (voice in RegistersVoice.entries) {
            for (name in listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")) {
                // An electronic key-click is a brief additive transient, not a sustained
                // timbre change. RegistersTransistorTest checks its 12ms difference RMS,
                // unchanged duration, exact settled tail and absence from held loops.
                if (voice == RegistersVoice.TRANSISTOR && name == "AIR") continue
                val low = Registers.probe(voice, mapOf(name to 0f), midi = 60).samples
                val high = Registers.probe(voice, mapOf(name to 1f), midi = 60).samples
                val difference = unitDifference(low, high)
                assertTrue(difference > .005, "$voice $name disappeared after gain removal: $difference")
            }
            val short = Registers.probe(voice, mapOf("HOLD" to 0f), 60)
            val long = Registers.probe(voice, mapOf("HOLD" to .9f), 60)
            assertTrue(long.samples.size > short.samples.size)
            assertFalse(short.isLoop)
            assertFalse(long.isLoop)
            assertEquals(48, Registers.probe(voice, mapOf("TUNE" to 0f)).midi)
            assertEquals(72, Registers.probe(voice, mapOf("TUNE" to 1f)).midi)
            assertEquals(69, Registers.probe(voice, mapOf("TUNE" to 0f), midi = 69).midi)
        }
    }

    @Test
    fun `silent velocity is silent and low velocity changes voice excitation`() {
        for (voice in RegistersVoice.entries) {
            for (hold in listOf(.35f, 1f)) {
                val silent = Registers.probe(voice, mapOf("HOLD" to hold), 60, 0f)
                assertTrue(silent.samples.all { it == 0f })
                assertEquals(0.0, silent.rawPeak)
                assertTrue(Registers.render(voice, mapOf("HOLD" to hold), 60, 0f).samples.all { it == 0f })
            }
            val soft = Registers.probe(voice, emptyMap(), 60, .2f)
            val hard = Registers.probe(voice, emptyMap(), 60, 1f)
            assertTrue(energy(soft.samples) < energy(hard.samples) * .45)
            assertTrue(unitDifference(soft.samples, hard.samples) > .001, "$voice velocity must alter timbre")
        }
    }

    @Test
    fun `steady finite pitch follows MIDI in each audition register`() {
        for (voice in RegistersVoice.entries) for (midi in listOf(48, 60, 69)) {
            val probe = Registers.probe(voice, mapOf("SPREAD" to 0f, "MOTION" to 0f, "EDGE" to 0f), midi)
            val measured = measuredFundamental(probe.samples, probe.requestedHz)
            val cents = abs(1200 * ln(measured / probe.requestedHz) / ln(2.0))
            assertTrue(cents < 1.5, "$voice MIDI $midi pitch is $cents cents away")
        }
    }

    @Test
    fun `held motion reeds and noise recur across independently synthesized cycles`() {
        for (voice in RegistersVoice.entries) for (midi in listOf(48, 60, 69)) {
            val probe = Registers.probe(voice, mapOf("AIR" to .8f, "EDGE" to .7f,
                "SPREAD" to .8f, "MOTION" to .8f, "HOLD" to 1f), midi)
            assertTrue(probe.isLoop)
            assertEquals(probe.samples.size, probe.loopFrames)
            val previous = requireNotNull(probe.previousCycle)
            assertFalse(previous === probe.samples)
            val measuredError = previous.indices.sumOf { (previous[it] - probe.samples[it].toDouble()).let { d -> d * d } } /
                energy(previous).coerceAtLeast(1e-30)
            assertEquals(measuredError, probe.cycleError)
            assertTrue(requireNotNull(probe.cycleError) < 1e-5)
            assertTrue(requireNotNull(probe.seamError) < Keys.MAX_SEAM_ERROR)
            val cents = abs(1200 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0))
            assertTrue(cents < .1, "$voice held MIDI $midi moved by $cents cents")
            val seconds = probe.samples.size.toDouble() / Dsp.RATE
            if (probe.spreadModulationHz == 0.0) {
                assertTrue(abs(probe.effectiveSpreadHz * seconds - (probe.effectiveSpreadHz * seconds).roundToInt()) < 1e-8)
            } else {
                // Model-6 TRANSISTOR closes its ensemble phase, retaining a continuous
                // nominal swing instead of forcing the small bank offset onto this grid.
                assertEquals(RegistersVoice.TRANSISTOR, voice)
                assertEquals(1.0, probe.spreadModulationHz * seconds, 1e-12)
            }
            assertTrue(abs(probe.motionHz * seconds - (probe.motionHz * seconds).roundToInt()) < 1e-8)
            assertTrue(probe.samples.take(2048).any { abs(it) > .01f }, "a held loop has no onset ramp")
            assertTrue(probe.rawPeak <= Registers.RAW_PEAK_CEILING)
        }
    }

    @Test
    fun `extreme macro corners remain finite and bounded before matching`() {
        // Pairwise opposite corners exercise max pressure, bright partials, detuning and short release.
        val corners = listOf(
            listOf(0f, 0f, 0f, 0f, 0f), listOf(1f, 1f, 1f, 1f, 1f),
            listOf(1f, 0f, 1f, 0f, 1f), listOf(0f, 1f, 0f, 1f, 0f),
        )
        val names = listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")
        for (voice in RegistersVoice.entries) for (midi in listOf(24, 96)) for (corner in corners) {
            val macros = names.zip(corner).toMap()
            for (hold in listOf(0f, 1f)) {
                val p = Registers.probe(voice, macros + ("HOLD" to hold), midi)
                assertTrue(p.samples.all { it.isFinite() })
                assertTrue(p.rawPeak <= Registers.RAW_PEAK_CEILING)
                assertTrue(peak(p.samples) < .9)
                assertTrue(energy(p.samples) > 1e-4)
            }
        }
    }

    private fun sampleHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
    }

    @Test
    fun `model one reproduces float fixtures captured before model two edits`() {
        // Captured directly from the compiled, published model-1 renderer on 2026-10-14.
        // SHA256 covers raw IEEE Float32 bits in little-endian order, including signed zero.
        val fixtures = listOf(
            Triple(RegistersVoice.PIPE, .35f, listOf(
                "dd8ab327bf0365fa9c9e38d8b06a324ad33d65a3a87e4dd8317847e409cd1d29",
                "483cce66a8596a299f4b61d1a0d382f2dc557e12d4a4196ac3a01af71ae3d0fb")),
            Triple(RegistersVoice.PIPE, 1f, listOf(
                "beec4899c040215c1803faa99554ee31cccccbc265785a8440c2e1bfdd970cad",
                "5c1f6056b09019f4e2de3d180f01d9549580b1ed13046cae638c6adddcc01e19")),
            Triple(RegistersVoice.MUSETTE, .35f, listOf(
                "3ed254e02112db3c96159d5f5457d275119e3dd36422656f7c852b76bfec5bf0",
                "852b6824191bb89396ab7383a33182eb52f6b0b805927e4cd4ae7939cf73cbab")),
            Triple(RegistersVoice.MUSETTE, 1f, listOf(
                "321f531c21cfe685fd3f500f0aa26da915c6195d26ba72d453f2e4b2a39997a5",
                "03f269f49e5bc9f27a395f576be9b0844d34650698a39549a06686b6a3ded8b8")),
        )
        for ((voice, hold, hashes) in fixtures) {
            val macros = mapOf("HOLD" to hold)
            assertEquals(hashes[0], sampleHash(Registers.probe(voice, macros, 60, 1f, model = 1).samples))
            assertEquals(hashes[1], sampleHash(Registers.render(voice, macros, 60, 1f, model = 1).samples))
        }
        val expressive = mapOf("COLOR" to .55f, "AIR" to .72f, "EDGE" to .62f,
            "SPREAD" to .62f, "MOTION" to .55f, "HOLD" to .35f)
        assertEquals("0a162f8f09128cec05e314a024b86019b9fb0447fe5d798a4909d533db52925a",
            sampleHash(Registers.probe(RegistersVoice.PIPE, expressive, 69, .7f, model = 1).samples))
        assertEquals("ae8c629a59fbcc1c121f036829df37f599d1cd261cad5ad7526d22a80e0808a6",
            sampleHash(Registers.render(RegistersVoice.PIPE, expressive, 69, .7f, model = 1).samples))
    }

    private fun onsetBandEnergy(samples: FloatArray): Double {
        val first = (.020 * Dsp.RATE).roundToInt()
        val count = (.060 * Dsp.RATE).roundToInt()
        val n = 8192
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val low = (1_800.0 * n / Dsp.RATE).roundToInt()
        val high = (12_000.0 * n / Dsp.RATE).roundToInt()
        return (low..high).sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    @Test
    fun `model two makes finite pipe speech stronger without changing its settled raw tone`() {
        val gestures = listOf(
            "default" to emptyMap(),
            "restrained" to mapOf("COLOR" to .18f, "AIR" to .20f, "EDGE" to .08f,
                "SPREAD" to .06f, "MOTION" to .05f, "HOLD" to .35f),
            "expressive" to mapOf("COLOR" to .55f, "AIR" to .72f, "EDGE" to .62f,
                "SPREAD" to .62f, "MOTION" to .55f, "HOLD" to .35f),
        )
        for ((name, macros) in gestures) {
            val oldRaw = Registers.probe(RegistersVoice.PIPE, macros, 60, model = 1)
            val newRaw = Registers.probe(RegistersVoice.PIPE, macros, 60, model = 2)
            val tailStart = (.120 * Dsp.RATE).roundToInt()
            assertContentEquals(oldRaw.samples.copyOfRange(tailStart, oldRaw.samples.size),
                newRaw.samples.copyOfRange(tailStart, newRaw.samples.size), "$name settled raw tone changed")
            val oldMatched = Registers.render(RegistersVoice.PIPE, macros, 60, model = 1).samples
            val newMatched = Registers.render(RegistersVoice.PIPE, macros, 60, model = 2).samples
            val ratio = onsetBandEnergy(newMatched) / onsetBandEnergy(oldMatched)
            println("PIPE $name model2/model1 matched 20–80ms 1.8–12kHz energy ratio: $ratio")
            assertTrue(ratio > 1.5, "$name speaking-band increase was too small: $ratio")
            assertTrue(newRaw.rawPeak <= Registers.RAW_PEAK_CEILING)
        }
        val silentEdge = mapOf("EDGE" to 0f)
        assertContentEquals(Registers.probe(RegistersVoice.PIPE, silentEdge, model = 1).samples,
            Registers.probe(RegistersVoice.PIPE, silentEdge, model = 2).samples, "EDGE 0 must disable the added chiff")
    }

    @Test
    fun `model two leaves musette and held pipe bit identical and saves explicit model`() {
        for (voice in listOf(RegistersVoice.PIPE, RegistersVoice.MUSETTE)) for (hold in listOf(.35f, 1f)) {
            if (voice == RegistersVoice.PIPE && hold < Registers.LOOP_THRESHOLD) continue
            val macros = mapOf("AIR" to .8f, "EDGE" to .9f, "SPREAD" to .8f, "MOTION" to .7f, "HOLD" to hold)
            assertContentEquals(Registers.probe(voice, macros, model = 1).samples,
                Registers.probe(voice, macros, model = 2).samples)
            assertContentEquals(Registers.render(voice, macros, model = 1).samples,
                Registers.render(voice, macros, model = 2).samples)
        }
        val current = RegistersPatch("New speech", RegistersVoice.PIPE, emptyMap())
        assertEquals(Registers.MODEL_VERSION, current.model)
        assertEquals(Registers.MODEL_VERSION, RegistersPatch.fromJsonText(current.toJsonText()).model)
        val old = current.copy(model = 1)
        assertEquals(1, RegistersPatch.fromJsonText(old.toJsonText()).model)
        assertContentEquals(old.render().samples, RegistersPatch.fromJsonText(old.toJsonText()).render().samples)
        val missingModel = JsonValue.Obj(old.toJsonValue().entries.filterKeys { it != "model" })
        val decoded = RegistersPatch.fromJsonValue(missingModel)
        assertEquals(1, decoded.model)
        assertContentEquals(old.render().samples, decoded.render().samples)
        assertTrue(Registers.supportsModel(1))
        assertTrue(Registers.supportsModel(2))
        assertTrue(Registers.supportsModel(3))
        assertTrue(Registers.supportsModel(4))
        assertFalse(Registers.supportsModel(999))
        assertFailsWith<IllegalArgumentException> { Registers.probe(RegistersVoice.PIPE, model = 999) }
    }

    @Test
    fun `model two reproduces float fixtures captured before sustained airflow edits`() {
        // Captured from the compiled, accepted R2 renderer before model-3 source edits.
        val fixtures = listOf(
            Triple(RegistersVoice.PIPE, .35f, listOf(
                "631cd1e82c487f0a7de15d5dc6b7ecc102490afdae49d158a47f6da76e638d73",
                "a3e15917476629b24028b2f04a2735748c3cb3b207a790961b25777a4d7cb415")),
            Triple(RegistersVoice.PIPE, 1f, listOf(
                "beec4899c040215c1803faa99554ee31cccccbc265785a8440c2e1bfdd970cad",
                "5c1f6056b09019f4e2de3d180f01d9549580b1ed13046cae638c6adddcc01e19")),
            Triple(RegistersVoice.MUSETTE, .35f, listOf(
                "3ed254e02112db3c96159d5f5457d275119e3dd36422656f7c852b76bfec5bf0",
                "852b6824191bb89396ab7383a33182eb52f6b0b805927e4cd4ae7939cf73cbab")),
            Triple(RegistersVoice.MUSETTE, 1f, listOf(
                "321f531c21cfe685fd3f500f0aa26da915c6195d26ba72d453f2e4b2a39997a5",
                "03f269f49e5bc9f27a395f576be9b0844d34650698a39549a06686b6a3ded8b8")),
        )
        for ((voice, hold, hashes) in fixtures) {
            val macros = mapOf("HOLD" to hold)
            assertEquals(hashes[0], sampleHash(Registers.probe(voice, macros, 60, 1f, model = 2).samples))
            assertEquals(hashes[1], sampleHash(Registers.render(voice, macros, 60, 1f, model = 2).samples))
        }
        val expressive = mapOf("COLOR" to .55f, "AIR" to .72f, "EDGE" to .62f,
            "SPREAD" to .62f, "MOTION" to .55f, "HOLD" to .35f)
        assertEquals("d0f517ea6128b7858de6995386e71b1f2d378023f6bdc1a7d00e0bc98802f6a3",
            sampleHash(Registers.probe(RegistersVoice.PIPE, expressive, 69, .7f, model = 2).samples))
        assertEquals("0372cff7ec8ff024b92612685660564184d524148d1f99de727de0286e260b7b",
            sampleHash(Registers.render(RegistersVoice.PIPE, expressive, 69, .7f, model = 2).samples))
        val saved = RegistersPatch("Accepted speech", RegistersVoice.PIPE, emptyMap(), model = 2)
        val decoded = RegistersPatch.fromJsonText(saved.toJsonText())
        assertEquals(2, decoded.model)
        assertContentEquals(saved.render().samples, decoded.render().samples)
    }

    private fun windowRms(samples: FloatArray, from: Int, until: Int): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun assertBroadColoredAir(samples: FloatArray, hz: Double, from: Int) {
        val n = 16384
        val count = 8192
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[from + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val binHz = Dsp.RATE.toDouble() / n
        val powers = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val bins = powers.indices.filter { it * binHz in 100.0..6_000.0 }
        val total = bins.sumOf { powers[it] }
        val offHarmonic = bins.filter { abs(it * binHz - (it * binHz / hz).roundToInt() * hz) > 12.0 }
            .sumOf { powers[it] } / total
        assertTrue(offHarmonic > .65, "airflow should add broad nonharmonic energy: $offHarmonic")
        val active = bins.count { powers[it] > total / bins.size * .15 }
        assertTrue(active > 400, "airflow is too spectrally sparse: $active active bins")
        val high = powers.indices.filter { it * binHz > 10_000.0 }.sumOf { powers[it] }
        assertTrue(high < total * .15, "airflow should remain colored rather than harsh high hiss: ${high / total}")
    }

    @Test
    fun `sustained pipe airflow preserves accepted attack and adds soft continuous colored energy`() {
        for (midi in listOf(48, 60, 69)) {
            val old = Registers.probe(RegistersVoice.PIPE, midi = midi, model = 2)
            val current = Registers.probe(RegistersVoice.PIPE, midi = midi, model = 3)
            val attackEnd = (.080 * Dsp.RATE).roundToInt()
            assertContentEquals(old.samples.copyOf(attackEnd), current.samples.copyOf(attackEnd),
                "MIDI $midi changed the accepted raw attack")
            val flow = requireNotNull(current.airflowSamples)
            assertEquals(current.samples.size, flow.size)
            assertTrue(flow.take(attackEnd).all { it == 0f })
            assertBroadColoredAir(flow, current.effectiveHz, (.300 * Dsp.RATE).roundToInt())
            val from = current.airflowAnalysisStartFrame
            val until = current.airflowAnalysisEndFrame
            assertEquals((.300 * Dsp.RATE).roundToInt(), from)
            val difference = FloatArray(current.samples.size) { current.samples[it] - old.samples[it] }
            val differenceRms = windowRms(difference, from, until)
            assertTrue(abs(differenceRms / current.airflowRms - 1.0) < 1e-4,
                "MIDI $midi added-flow tap disagrees with actual waveform difference")
            val relativeDb = 20 * ln(current.airflowRms / current.bodyRms) / ln(10.0)
            println("PIPE MIDI $midi default sustained airflow/body RMS: $relativeDb dB")
            assertTrue(relativeDb in -32.0..-25.0, "MIDI $midi airflow balance is $relativeDb dB")
            // Every settled 100 ms interval must retain air, rather than another short puff.
            val block = (.100 * Dsp.RATE).roundToInt()
            for (start in from until until - block step block) {
                val local = windowRms(flow, start, start + block)
                assertTrue(local > current.airflowRms * .70 && local < current.airflowRms * 1.30,
                    "MIDI $midi airflow is clumped around frame $start")
            }
            val release = flow.copyOfRange(until, flow.size)
            assertTrue(windowRms(release, (release.size * .75).roundToInt(), release.size) < current.airflowRms * .15)
            assertTrue(current.rawPeak <= Registers.RAW_PEAK_CEILING)
        }
        for (hold in listOf(.35f, 1f)) {
            val macros = mapOf("AIR" to 0f, "HOLD" to hold)
            assertContentEquals(Registers.probe(RegistersVoice.PIPE, macros, model = 2).samples,
                Registers.probe(RegistersVoice.PIPE, macros, model = 3).samples, "AIR 0 must bypass flow exactly")
            assertContentEquals(Registers.render(RegistersVoice.PIPE, macros, model = 2).samples,
                Registers.render(RegistersVoice.PIPE, macros, model = 3).samples)
        }
    }

    @Test
    fun `dense held airflow recurs independently while musette stays unchanged`() {
        for (midi in listOf(48, 60, 69)) {
            val macros = mapOf("AIR" to .36f, "SPREAD" to .7f, "MOTION" to .8f, "HOLD" to 1f)
            val old = Registers.probe(RegistersVoice.PIPE, macros, midi, model = 2)
            val current = Registers.probe(RegistersVoice.PIPE, macros, midi, model = 3)
            assertEquals(old.loopFrames, current.loopFrames)
            assertEquals(old.effectiveHz, current.effectiveHz)
            assertEquals(old.effectiveSpreadHz, current.effectiveSpreadHz)
            assertEquals(old.motionHz, current.motionHz)
            assertEquals(0, current.airflowAnalysisStartFrame)
            assertEquals(current.samples.size, current.airflowAnalysisEndFrame)
            assertTrue(current.airflowRms > 0.0)
            assertTrue(20 * ln(current.airflowRms / current.bodyRms) / ln(10.0) in -32.0..-25.0)
            assertTrue(requireNotNull(current.cycleError) < 1e-5)
            assertTrue(requireNotNull(current.seamError) < Keys.MAX_SEAM_ERROR)
            assertTrue(requireNotNull(current.airflowCycleError) < 1e-5)
            assertTrue(requireNotNull(current.airflowSeamError) < Keys.MAX_SEAM_ERROR)
            val previousFlow = requireNotNull(current.airflowPreviousCycle)
            assertFalse(previousFlow === current.airflowSamples)
            assertEquals(requireNotNull(current.airflowSamples).size, previousFlow.size)
            val previous = requireNotNull(current.previousCycle)
            assertFalse(previous === current.samples)
            assertTrue(energy(FloatArray(current.samples.size) { current.samples[it] - old.samples[it] }) > 1e-5)
            for (hold in listOf(.35f, 1f)) {
                val reedMacros = macros + ("HOLD" to hold)
                assertContentEquals(Registers.probe(RegistersVoice.MUSETTE, reedMacros, midi, model = 2).samples,
                    Registers.probe(RegistersVoice.MUSETTE, reedMacros, midi, model = 3).samples)
                assertContentEquals(Registers.render(RegistersVoice.MUSETTE, reedMacros, midi, model = 2).samples,
                    Registers.render(RegistersVoice.MUSETTE, reedMacros, midi, model = 3).samples)
            }
        }
    }
}
