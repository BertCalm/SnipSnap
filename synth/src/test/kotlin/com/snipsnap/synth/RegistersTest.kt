package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonException
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
        assertFailsWith<JsonException> { RegistersPatch.fromJsonText(partial.toJsonText().replace("\"model\": 1", "\"model\": 999")) }
        assertFailsWith<IllegalArgumentException> { RegistersPatch("Bad", RegistersVoice.PIPE, mapOf("AIR" to Float.NaN)) }
    }

    @Test
    fun `every timbral control changes the signal before and after gain removal`() {
        for (voice in RegistersVoice.entries) {
            for (name in listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")) {
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
    fun `silent velocity is silent and low velocity changes reed excitation`() {
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
            assertTrue(abs(probe.effectiveSpreadHz * seconds - (probe.effectiveSpreadHz * seconds).roundToInt()) < 1e-8)
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
}
