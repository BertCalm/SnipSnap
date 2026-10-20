package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the listening experiment's physical and audible claims independently:
 * energy belongs to the chain, pitch to the scanner, and motion must change the
 * waveform after a scalar loudness change has been removed. These tests do not
 * prescribe a spring implementation or make a judgement about musical quality.
 */
class ScannedChainPrototypeTest {
    private val rate = 44_100

    private fun config(name: String = "test") = ScannedChainPrototype.Config(
        name = name,
        seconds = 0.8f,
        attackSeconds = 0.008f,
        releaseSeconds = 0.05f,
        drive = 0f,
    )

    private fun hz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun shape(vararg harmonics: Pair<Int, Double>): FloatArray = FloatArray(128) { i ->
        harmonics.sumOf { (harmonic, amplitude) -> amplitude * sin(2 * PI * harmonic * i / 128) }.toFloat()
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** Hann-windowed FFT peak with sub-bin interpolation, measured from audio. */
    private fun dominantHz(samples: FloatArray, start: Int, size: Int = 32_768): Double {
        val re = FloatArray(size) { i ->
            (samples[start + i] * (0.5 - 0.5 * cos(2 * PI * i / (size - 1)))).toFloat()
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        val power = DoubleArray(size / 2) { i -> re[i].toDouble() * re[i] + im[i].toDouble() * im[i] }
        val best = (2 until power.size - 1).maxBy { power[it] }
        val left = ln(power[best - 1].coerceAtLeast(1e-30))
        val center = ln(power[best].coerceAtLeast(1e-30))
        val right = ln(power[best + 1].coerceAtLeast(1e-30))
        val offset = (0.5 * (left - right) / (left - 2 * center + right)).coerceIn(-0.5, 0.5)
        return (best + offset) * rate / size
    }

    /** Whole-period correlation keeps a missing fundamental distinct from FFT leakage. */
    private fun amplitude(samples: FloatArray, frequency: Double, start: Int, count: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in start until start + count) {
            val phase = 2 * PI * frequency * i / rate
            real += samples[i] * cos(phase)
            imaginary += samples[i] * sin(phase)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / count
    }

    /** Average three phase-aligned cycles; normalize away the gesture envelope. */
    private fun unitCycle(samples: FloatArray, frequency: Double, atSeconds: Double): DoubleArray {
        val cycle = (atSeconds * frequency).roundToInt()
        val out = DoubleArray(128) { phase ->
            (0 until 3).sumOf { offset ->
                val position = (cycle + offset + phase / 128.0) * rate / frequency
                val lo = position.toInt()
                val fraction = position - lo
                samples[lo] * (1 - fraction) + samples[lo + 1] * fraction
            } / 3
        }
        val mean = out.average()
        for (i in out.indices) out[i] -= mean
        val norm = sqrt(out.sumOf { it * it } / out.size)
        assertTrue(norm > 1e-6, "cycle is audible before normalization")
        return DoubleArray(out.size) { out[it] / norm }
    }

    /** Residual after the best scalar fit: neither a decay nor a polarity flip counts as new timbre. */
    private fun scalarResidual(first: DoubleArray, second: DoubleArray): Double {
        val scale = first.indices.sumOf { first[it] * second[it] } / first.sumOf { it * it }
        return sqrt(first.indices.sumOf { val difference = second[it] - scale * first[it]; difference * difference } / first.size)
    }

    @Test
    fun `unexcited chain stays silent and a passive damped chain loses energy`() {
        val settings = config().copy(dampingPerSecond = 1.5f, nonlinear = 0f)
        val quiet = ScannedChainPrototype.render(settings, FloatArray(128))
        assertTrue(quiet.raw.samples.all { it == 0f }, "there is no hidden oscillator or ongoing exciter")
        assertEquals(0.0, quiet.diagnostics.initialEnergy)
        assertEquals(0.0, quiet.diagnostics.finalEnergy)

        val excited = ScannedChainPrototype.render(settings, shape(1 to 0.55, 4 to 0.3, 7 to 0.15))
        val diagnostic = excited.diagnostics
        assertTrue(diagnostic.initialEnergy > 0)
        assertTrue(diagnostic.finalEnergy < diagnostic.initialEnergy * 0.8, "passive damping removes physical energy")
        assertTrue(diagnostic.maxEnergy <= diagnostic.initialEnergy * 1.02, "a stable undriven solver does not manufacture energy")
        assertTrue(diagnostic.dampingLoss > 0)
        assertEquals(0.0, diagnostic.driveWork)
        assertTrue(diagnostic.finite)
        assertTrue(abs(diagnostic.energyBalanceError) < diagnostic.initialEnergy * 0.001,
            "potential, kinetic energy and damping agree for the centered chain")

        val defaultPassive = ScannedChainPrototype.render(config()).diagnostics
        assertTrue(abs(defaultPassive.energyBalanceError) < defaultPassive.initialEnergy * 0.001,
            "default nonlinear centered chain closes its energy ledger: ${defaultPassive.energyBalanceError}")
    }

    @Test
    fun `fresh sequential and concurrent renders are deterministic and own their output`() {
        val settings = config().copy(seconds = 0.25f, seed = 749)
        val input = shape(1 to 0.65, 3 to 0.2, 8 to 0.15)
        val before = input.copyOf()
        val reference = ScannedChainPrototype.render(settings, input)
        val again = ScannedChainPrototype.render(settings, input)
        assertContentEquals(before, input, "render must not evolve the caller's saved shape")
        assertContentEquals(reference.raw.samples, again.raw.samples)
        assertContentEquals(reference.finalShape, again.finalShape)

        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = workers.invokeAll(List(2) {
                Callable { ScannedChainPrototype.render(settings, input) }
            }).map { it.get() }
            for (result in results) {
                assertContentEquals(reference.raw.samples, result.raw.samples)
                assertContentEquals(reference.finalShape, result.finalShape)
                assertTrue(reference.raw.samples !== result.raw.samples, "output buffers must not be shared")
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `physical motion changes cycle shape after loudness and polarity are removed`() {
        val input = shape(1 to 0.55, 4 to 0.3, 7 to 0.15)
        val settings = config().copy(midi = 60, dampingPerSecond = 0.3f, nonlinear = 0f)
        val frozen = ScannedChainPrototype.render(settings.copy(frozen = true), input)
        val moving = ScannedChainPrototype.render(settings, input)
        val frozenDifference = scalarResidual(
            unitCycle(frozen.raw.samples, hz(settings.midi), 0.1),
            unitCycle(frozen.raw.samples, hz(settings.midi), 0.6),
        )
        val movingDifference = scalarResidual(
            unitCycle(moving.raw.samples, hz(settings.midi), 0.1),
            unitCycle(moving.raw.samples, hz(settings.midi), 0.6),
        )
        assertTrue(frozenDifference < 0.005, "static waveform has residual $frozenDifference")
        assertTrue(movingDifference > 0.05, "motion changes waveform beyond a gain curve: $movingDifference")
        assertTrue(movingDifference > frozenDifference * 10)
        assertContentEquals(frozen.initialShape, frozen.finalShape)
    }

    @Test
    fun `snapshot interpolation preserves a nearly stationary waveform without gain gating`() {
        val settings = config().copy(seconds = 0.3f, motion = 0.001f, mechanicalHz = 0.1f,
            nonlinear = 0f, dampingPerSecond = 0f, drive = 0f, snapshotRate = 500)
        val input = shape(1 to 0.25, 3 to 0.05)
        val moving = ScannedChainPrototype.render(settings, input).raw.samples
        val frozen = ScannedChainPrototype.render(settings.copy(frozen = true), input).raw.samples
        val residualPower = moving.indices.sumOf {
            val difference = moving[it].toDouble() - frozen[it]
            difference * difference
        }
        val referencePower = frozen.sumOf { it.toDouble() * it }
        assertTrue(referencePower > 0)
        val relativeResidual = sqrt(residualPower / referencePower)
        // Physical motion at .0001 Hz changes this shape negligibly in .3 s.
        // Both endpoints of the interpolator must preserve its amplitude;
        // dropping the right endpoint creates order-one 500 Hz gain gating.
        assertTrue(relativeResidual < 1e-4, "near-static moving/frozen residual is $relativeResidual")
    }

    @Test
    fun `scanner follows nominal notes and does not invent a missing fundamental`() {
        for (midi in listOf(36, 60, 96)) {
            val result = ScannedChainPrototype.render(
                config().copy(midi = midi, frozen = true, seconds = 1f),
                shape(1 to 0.8),
            )
            val measured = dominantHz(result.raw.samples, start = 4_410)
            val cents = 1_200 * ln(measured / hz(midi)) / ln(2.0)
            assertTrue(abs(cents) < 5, "MIDI $midi measured $measured Hz ($cents cents)")
        }

        // A pure second spatial mode scans at 880 Hz. A 440 Hz sine mixed in for
        // an easier pitch reading would fail this independent source-identity check.
        val missing = ScannedChainPrototype.render(
            config().copy(midi = 69, frozen = true), shape(2 to 0.8),
        ).raw.samples
        val fundamental = amplitude(missing, 440.0, start = 4_410, count = 22_050)
        val second = amplitude(missing, 880.0, start = 4_410, count = 22_050)
        assertTrue(second > 0.01)
        assertTrue(fundamental < second * 0.001, "the initial shape alone supplies scanner harmonics")
    }

    @Test
    fun `changing note leaves physical motion untouched while motion changes the chain`() {
        val input = shape(1 to 0.65, 4 to 0.35)
        val settings = config().copy(nonlinear = 0f)
        val low = ScannedChainPrototype.render(settings.copy(midi = 36), input)
        val high = ScannedChainPrototype.render(settings.copy(midi = 96), input)
        assertContentEquals(low.finalShape, high.finalShape, "note must not retune the spring simulation")
        assertEquals(low.diagnostics.physicsSteps, high.diagnostics.physicsSteps)
        assertEquals(low.diagnostics.finalEnergy, high.diagnostics.finalEnergy)
        assertTrue(high.diagnostics.scannerHz > low.diagnostics.scannerHz)

        val slow = ScannedChainPrototype.render(settings.copy(motion = 0.25f), input)
        val fast = ScannedChainPrototype.render(settings.copy(motion = 1f), input)
        assertEquals(slow.diagnostics.scannerHz, fast.diagnostics.scannerHz, "motion must not transpose the scanner")
        val shapeDifference = sqrt(slow.finalShape.indices.sumOf {
            val difference = slow.finalShape[it].toDouble() - fast.finalShape[it]
            difference * difference
        } / slow.finalShape.size)
        assertTrue(shapeDifference > 0.01, "motion changes mechanical evolution")
    }

    @Test
    fun `bloom drive performs physical work and excites an initially quiet chain`() {
        val settings = ScannedChainPrototype.factoryConfigs().first { it.voice == ScannedChainPrototype.Voice.BLOOM }
            .copy(seconds = 0.8f, velocity = 1f, drive = 1f, releaseSeconds = 0.05f)
        val passive = ScannedChainPrototype.render(settings.copy(drive = 0f))
        val driven = ScannedChainPrototype.render(settings)
        val difference = sqrt(passive.finalShape.indices.sumOf {
            val displacement = driven.finalShape[it].toDouble() - passive.finalShape[it]
            displacement * displacement
        } / passive.finalShape.size)
        assertTrue(difference > 0.001, "drive must change the physical chain, not just output gain")
        assertEquals(0.0, passive.diagnostics.driveWork)
        assertTrue(abs(driven.diagnostics.driveWork) > 1e-6, "time-varying drive performs signed mechanical work")

        val quietDriven = ScannedChainPrototype.render(settings, FloatArray(128))
        assertEquals(0.0, quietDriven.diagnostics.initialEnergy)
        assertTrue(quietDriven.diagnostics.finalEnergy > 1e-6, "external force creates actual chain energy")
        assertTrue(quietDriven.diagnostics.driveWork > 1e-6, "a chain starting at rest absorbs driver work")
        assertTrue(quietDriven.finalShape.any { abs(it) > 0.001f })
        assertTrue(rms(quietDriven.raw.samples) > 1e-4, "physical excitation is audible")
        assertTrue(quietDriven.raw.samples.all { it.isFinite() })
    }

    @Test
    fun `factory voices and physical control endpoints render bounded clean audio`() {
        for (factory in ScannedChainPrototype.factoryConfigs()) {
            val voice = factory.voice
            val cases = listOf(
                factory.copy(seconds = 0.8f, attackSeconds = 0.008f, releaseSeconds = 0.05f),
                config().copy(voice = voice, velocity = 0.05f, motion = 0f, mechanicalHz = 0.1f,
                    dampingPerSecond = 0f, nonlinear = 0f, restFraction = 0f),
                config().copy(voice = voice, velocity = 1f, motion = 1f, mechanicalHz = 4f,
                    dampingPerSecond = 12f, nonlinear = 8f, restFraction = 0.35f),
            )
            for (settings in cases) {
                val result = ScannedChainPrototype.render(settings)
                val samples = result.raw.samples
                assertEquals(rate, result.raw.sampleRate)
                assertEquals(1, result.raw.channels)
                assertEquals((0.8 * rate).roundToInt(), samples.size)
                assertTrue(samples.all { it.isFinite() }, "$voice settings=$settings")
                assertTrue(result.diagnostics.finite)
                assertTrue(result.raw.peak() <= 1f, "$voice must leave output in PCM range")
                val level = rms(samples)
                assertTrue(level > 1e-5, "$voice settings=$settings is audible")
                assertTrue(abs(samples.average()) < level * 0.02, "$voice DC is small relative to its signal")
                assertTrue(abs(samples.first()) < 1e-3f && abs(samples.last()) < 1e-3f, "$voice has bounded gesture endpoints")
            }
        }
    }

    @Test
    fun `zero motion freezes the complete physical state`() {
        val cases = listOf(
            60 to shape(1 to 0.55, 4 to 0.3, 7 to 0.15),
            // At MIDI 92, spatial harmonic 11 straddles the scanner's safety
            // reserve. Freeze must not change bandwidth and masquerade as timbre.
            92 to shape(1 to 0.65, 11 to 0.35),
        )
        for ((midi, input) in cases) {
            val settings = config().copy(midi = midi, dampingPerSecond = 2f, drive = 0f)
            val zero = ScannedChainPrototype.render(settings.copy(motion = 0f), input)
            val frozen = ScannedChainPrototype.render(settings.copy(frozen = true), input)
            assertEquals(frozen.diagnostics.retainedHarmonics, zero.diagnostics.retainedHarmonics,
                "freeze must preserve scanner bandwidth at MIDI $midi")
            assertContentEquals(frozen.raw.samples, zero.raw.samples, "same frozen waveform at MIDI $midi")
            assertContentEquals(zero.initialShape, zero.finalShape)
            assertEquals(zero.diagnostics.initialEnergy, zero.diagnostics.finalEnergy)
            assertEquals(0, zero.diagnostics.physicsSteps)
        }
    }
}
