package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Loose guards against the first audition's measured masking failure. Model one's stable center
 * left 99.3% of powered spectral energy in the fundamental and reduced the full mix's circular /
 * stretched envelope difference to .21 dB. These screens preserve substantial internal motion
 * and upper-body energy. They neither predict audibility nor replace the owner's listening verdict.
 * Every comparison fixes MIDI, velocity and seed, removes overall level, and ignores waveform phase.
 */
class ApsisVoicingTest {
    private class Fixture(val probe: Apsis.Probe) {
        val features by lazy { features(probe.samples, probe.diagnostics.sourceEndSeconds) }
    }

    private val defaultNew by lazy { fixture() }
    private val defaultLegacy by lazy { fixture(model = 1) }
    private val circle by lazy { fixture(mapOf("STRETCH" to 0f)) }
    private val elongated by lazy { fixture(mapOf("STRETCH" to 1f)) }
    private val coupled by lazy { fixture(mapOf("COUPLE" to .85f)) }
    private val uncoupled by lazy { fixture(mapOf("COUPLE" to .85f), bypass = true) }

    @Test
    fun `the revised full mix retains substantial body color beyond the fundamental`() {
        val legacyUpper = 1 - defaultLegacy.features.fundamentalShare
        val newUpper = 1 - defaultNew.features.fundamentalShare
        println("APSIS full-spectrum nonfundamental power: legacy=$legacyUpper revised=$newUpper")
        assertTrue(newUpper >= .10, "steady root again masks the upper bodies: nonfundamental share=$newUpper")
        assertTrue(newUpper >= legacyUpper + .05,
            "the revised body balance lost its structural difference from model one")
    }

    @Test
    fun `circular and stretched passages change the full articulation after removing overall gain`() {
        val envelopeDb = envelopeDistance(circle.features, elongated.features)
        val spectralDistance = spectralDistance(circle.features, elongated.features)
        println("APSIS circle/stretch: 100ms envelope shape=$envelopeDb dB, spectrum TV=$spectralDistance")
        assertTrue(envelopeDb >= 1.0,
            "orbital timing no longer changes the full envelope substantially: $envelopeDb dB")
        assertTrue(spectralDistance >= .03,
            "orbital timing changes only level while body color is masked: spectrum TV=$spectralDistance")
    }

    @Test
    fun `shared exchange redistributes meaningful full mix spectral power`() {
        val distance = spectralDistance(coupled.features, uncoupled.features)
        println("APSIS coupling full-spectrum TV=$distance")
        assertTrue(distance > .02,
            "coupling affects only a nearly masked spectral residue: full-spectrum TV=$distance")
    }

    @Test
    fun `a hard low passage keeps its root when the contact gains a local upper carrier`() {
        // This exact first-listen fixture exposed competing contact/source fundamental phases.
        // Upper contacts retain their body-local phase; the root must keep the common source phase.
        val midi = 36
        val p = Apsis.probe(ApsisVoice.PASSAGE, midi = midi, velocity = 1f,
            config = ApsisConfig(modelVersion = 2), options = Apsis.ProbeOptions(
                normalize = false, recordStems = false, recordTraces = false))
        val wanted = Keys.midiHz(midi)
        val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, .15f, .35f)
        val cents = FineTuning.cents(measured, wanted.toDouble())
        println("APSIS hard low contact root=$cents cents")
        assertTrue(abs(cents) < 20, "contact fundamental again competes with the requested low root: $cents cents")
    }

    private fun fixture(overrides: Map<String, Float> = emptyMap(), model: Int = 2,
        bypass: Boolean = false): Fixture = Fixture(Apsis.probe(
            ApsisVoice.PASSAGE, Apsis.defaults(ApsisVoice.PASSAGE) + overrides,
            midi = 60, velocity = .8f, config = ApsisConfig(modelVersion = model),
            options = Apsis.ProbeOptions(normalize = false, recordStems = false, recordTraces = false,
                ablation = if (bypass) Apsis.Ablation.NO_COUPLING else Apsis.Ablation.NONE)))

    private data class Features(val spectra: List<DoubleArray>, val envelopeDb: DoubleArray,
        val fundamentalShare: Double)

    private companion object {
        const val FFT_SIZE = 4096
        const val HOP = 441
        const val ENVELOPE_HALF_WINDOW = 2205
        val window = FloatArray(FFT_SIZE) { (.5 - .5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }

        fun features(samples: FloatArray, sourceEndSeconds: Double): Features {
            val rate = Dsp.RATE
            val first = (.8 * rate).toInt()
            val last = ((sourceEndSeconds - .4) * rate).toInt()
                .coerceAtMost(samples.size - max(FFT_SIZE / 2, ENVELOPE_HALF_WINDOW))
            require(last > first) { "voicing measurement needs a powered interior" }
            val lowBin = (40.0 * FFT_SIZE / rate).toInt().coerceAtLeast(1)
            val highBin = (10_000.0 * FFT_SIZE / rate).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
            val wanted = Keys.midiHz(60).toDouble()
            val radius = max(20.0, 2.1 * rate / FFT_SIZE)
            val spectra = ArrayList<DoubleArray>()
            val envelopes = ArrayList<Double>()
            val accumulated = DoubleArray(samples.size + 1)
            for (i in samples.indices) accumulated[i + 1] = accumulated[i] + samples[i].toDouble() * samples[i]
            var rootPower = 0.0
            var fullPower = 0.0
            for (center in first..last step HOP) {
                val real = FloatArray(FFT_SIZE) { k -> samples[center - FFT_SIZE / 2 + k] * window[k] }
                val imaginary = FloatArray(FFT_SIZE)
                Fft.forward(real, imaginary)
                val power = DoubleArray(highBin - lowBin + 1) { offset ->
                    val bin = offset + lowBin
                    real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                }
                val sum = power.sum().coerceAtLeast(1e-30)
                for (i in power.indices) {
                    val frequency = (i + lowBin) * rate.toDouble() / FFT_SIZE
                    if (abs(frequency - wanted) < radius) rootPower += power[i]
                }
                fullPower += sum
                for (i in power.indices) power[i] /= sum
                spectra += power
                val from = center - ENVELOPE_HALF_WINDOW
                val until = center + ENVELOPE_HALF_WINDOW
                val rms = sqrt((accumulated[until] - accumulated[from]).coerceAtLeast(0.0) / (until - from))
                envelopes += 20 * ln(rms.coerceAtLeast(1e-20)) / ln(10.0)
            }
            return Features(spectra, envelopes.toDoubleArray(), rootPower / fullPower.coerceAtLeast(1e-30))
        }

        fun spectralDistance(a: Features, b: Features): Double {
            require(a.spectra.size == b.spectra.size)
            return a.spectra.indices.sumOf { frame ->
                a.spectra[frame].indices.sumOf { bin -> abs(a.spectra[frame][bin] - b.spectra[frame][bin]) } * .5
            } / a.spectra.size
        }

        fun envelopeDistance(a: Features, b: Features): Double {
            require(a.envelopeDb.size == b.envelopeDb.size)
            val difference = DoubleArray(a.envelopeDb.size) { a.envelopeDb[it] - b.envelopeDb[it] }
            val mean = difference.average()
            return sqrt(difference.sumOf { (it - mean) * (it - mean) } / difference.size)
        }
    }
}
