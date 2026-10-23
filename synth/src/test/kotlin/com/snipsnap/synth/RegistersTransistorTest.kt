package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Causal guards for the new electronic source, rather than only its persistence plumbing. */
class RegistersTransistorTest {
    private val voice = RegistersVoice.TRANSISTOR
    private val static = mapOf("AIR" to 0f, "SPREAD" to 0f, "MOTION" to 0f)

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun spectrum(samples: FloatArray): DoubleArray {
        val count = 32768
        val size = 65536
        val re = FloatArray(size)
        val im = FloatArray(size)
        val from = Dsp.RATE / 3
        for (i in 0 until count) re[i] = samples[from + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        return DoubleArray(size / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun harmonicPower(power: DoubleArray, hz: Double, harmonic: Int): Double {
        val center = (hz * harmonic * 65536 / Dsp.RATE).roundToInt()
        return ((center - 4).coerceAtLeast(1)..(center + 4).coerceAtMost(power.lastIndex)).sumOf { power[it] }
    }

    @Test
    fun `current and historic transistor recipes replay and reject unsupported models`() {
        val patch = RegistersPatch("Bright combo", voice, Registers.defaults(voice) + ("COLOR" to .76f), 69, .72f)
        assertEquals(5, patch.model)
        for (model in 4..5) {
            val original = patch.copy(model = model)
            val loaded = RegistersPatch.fromJsonText(original.toJsonText())
            assertEquals(original, loaded)
            assertContentEquals(original.probe().samples, loaded.probe().samples)
            assertTrue(Registers.supportsModel(model, voice))
        }
        for (old in 1..3) {
            assertFalse(Registers.supportsModel(old, voice))
            assertFailsWith<IllegalArgumentException> { patch.copy(model = old) }
            assertFailsWith<IllegalArgumentException> { Registers.probe(voice, model = old) }
            val json = JsonValue.Obj(patch.toJsonValue().entries + ("model" to JsonValue.Num(old.toDouble())))
            assertFailsWith<JsonException> { RegistersPatch.fromJsonValue(json) }
        }
        val absent = JsonValue.Obj(patch.toJsonValue().entries.filterKeys { it != "model" })
        assertFailsWith<JsonException> { RegistersPatch.fromJsonValue(absent) }
        // Changing the default model must not change accepted PIPE/MUSETTE synthesis.
        for (oldVoice in listOf(RegistersVoice.PIPE, RegistersVoice.MUSETTE)) for (hold in listOf(.35f, 1f)) {
            val macros = mapOf("AIR" to .72f, "SPREAD" to .8f, "MOTION" to .7f, "HOLD" to hold)
            val accepted = Registers.probe(oldVoice, macros, model = 3).samples
            for (model in 4..5) assertContentEquals(accepted,
                Registers.probe(oldVoice, macros, model = model).samples, "$oldVoice model $model HOLD $hold changed")
        }
    }

    @Test
    fun `square divider ranks remain hollow and COLOR introduces the octave`() {
        // Preserve the original model-four source independently of the new foundation rank.
        val hollow = Registers.probe(voice, static + mapOf("COLOR" to 0f, "EDGE" to 0f), 69, model = 4)
        val bright = Registers.probe(voice, static + mapOf("COLOR" to 1f, "EDGE" to 0f), 69, model = 4)
        val low = spectrum(hollow.samples)
        val high = spectrum(bright.samples)
        val hollowEven = (2..10 step 2).sumOf { harmonicPower(low, hollow.requestedHz, it) }
        val hollowOdd = (1..9 step 2).sumOf { harmonicPower(low, hollow.requestedHz, it) }
        assertTrue(hollowEven / hollowOdd < .001, "COLOR0 should retain a square's odd-harmonic character")
        val lowOctave = harmonicPower(low, hollow.requestedHz, 2) / harmonicPower(low, hollow.requestedHz, 1)
        val highOctave = harmonicPower(high, bright.requestedHz, 2) / harmonicPower(high, bright.requestedHz, 1)
        assertTrue(highOctave > .15 && highOctave > lowOctave * 100,
            "COLOR must introduce an actual octave rank: $lowOctave -> $highOctave")
    }

    @Test
    fun `bright driven top register keeps off harmonic alias energy low`() {
        val probe = Registers.probe(voice, static + mapOf("COLOR" to 1f, "EDGE" to 1f), 96, model = 4)
        val power = spectrum(probe.samples)
        val binHz = Dsp.RATE.toDouble() / 65536
        val band = power.indices.filter { it * binHz in 100.0..18_000.0 }
        val total = band.sumOf { power[it] }
        val offHarmonic = band.filter { bin ->
            val hz = bin * binHz
            abs(hz - (hz / probe.requestedHz).roundToInt() * probe.requestedHz) > 12.0
        }.sumOf { power[it] } / total
        println("TRANSISTOR bright driven C7 off-harmonic energy: $offHarmonic")
        assertTrue(offHarmonic < .0025, "high notes expose aliased divider/drive energy: $offHarmonic")
    }

    @Test
    fun `keying is immediate and AIR changes only its brief electronic click`() {
        val plain = Registers.probe(voice, static, 69, model = 4)
        val keyed = Registers.probe(voice, static + ("AIR" to 1f), 69, model = 4)
        assertEquals(plain.samples.size, keyed.samples.size, "a key-click must not change gate or release duration")
        val onsetEnd = (.012 * Dsp.RATE).roundToInt()
        val difference = FloatArray(onsetEnd) { keyed.samples[it] - plain.samples[it] }
        assertTrue(rms(difference) > .005, "AIR must add an audible electronic key-click")
        val settled = (.100 * Dsp.RATE).roundToInt()
        assertContentEquals(plain.samples.copyOfRange(settled, plain.samples.size),
            keyed.samples.copyOfRange(settled, keyed.samples.size), "AIR changed the settled electronic tone/release")
        assertTrue(rms(plain.samples, 0, (.010 * Dsp.RATE).roundToInt()) >
            rms(plain.samples, (.100 * Dsp.RATE).roundToInt(), (.150 * Dsp.RATE).roundToInt()) * .45,
            "the electronic organ must speak in a few milliseconds")
        assertNull(keyed.airflowSamples, "an electronic keyer must not be reported as pipe airflow")
        assertEquals(0.0, keyed.airflowRms)
        val held = static + ("HOLD" to 1f)
        assertContentEquals(Registers.probe(voice, held + ("AIR" to 0f), 69, model = 4).samples,
            Registers.probe(voice, held + ("AIR" to 1f), 69, model = 4).samples, "a held loop must omit the initial key-click")
        assertEquals(5.5, plain.motionHz)
    }

    @Test
    fun `held divider banks recur independently at the pitch extremes and cancellation is honored`() {
        val macros = mapOf("COLOR" to 1f, "AIR" to 1f, "EDGE" to 1f,
            "SPREAD" to 1f, "MOTION" to 1f, "HOLD" to 1f)
        for (model in 4..5) for (midi in listOf(24, 96)) {
            val p = Registers.probe(voice, macros, midi, .8f, model = model)
            val previous = requireNotNull(p.previousCycle)
            assertFalse(previous === p.samples)
            assertTrue(requireNotNull(p.cycleError) < 1e-5)
            assertTrue(requireNotNull(p.seamError) < Keys.MAX_SEAM_ERROR)
            val cents = abs(1200 * ln(p.effectiveHz / p.requestedHz) / ln(2.0))
            assertTrue(cents < .1, "MIDI$midi carrier moved by $cents cents")
            assertTrue(p.rawPeak <= Registers.RAW_PEAK_CEILING)
            assertTrue(p.samples.all { it.isFinite() })
        }
        var checks = 0
        assertFailsWith<CancellationException> {
            Registers.probe(voice, macros, cancelled = { ++checks >= 4 })
        }
        assertTrue(checks >= 4)
    }
}
