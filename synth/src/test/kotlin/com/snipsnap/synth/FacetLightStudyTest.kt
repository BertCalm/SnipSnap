package com.snipsnap.synth

import com.snipsnap.audio.Fft
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
import kotlin.test.assertTrue

/** Noise and modal packets need reproducible light motion, without a periodicity requirement. */
class FacetLightStudyTest {
    private val controls = listOf("ILLUMINATE", "ELEVATION", "CHROMA", "REVOLVE", "FACETS")

    private fun probe(direction: FacetLightDirection, macros: Map<String, Float> = emptyMap(),
        voice: FacetVoice = FacetVoice.BLOOM, midi: Int = 48, seconds: Float = .75f,
        alignment: Float = .1875f, velocity: Float = .8f, oversample: Int = 4) =
        FacetLightStudy.probe(voice, direction, macros, midi = midi, velocity = velocity,
            seed = 17, initialAlignment = alignment,
            options = FacetOptions(seconds = seconds, oversample = oversample))

    private fun rms(samples: FloatArray, first: Int = 0, until: Int = samples.size): Double =
        sqrt((first until until).sumOf { samples[it].toDouble() * samples[it] } / (until - first))

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val ar = rms(a)
        val br = rms(b)
        require(ar > 1e-12 && br > 1e-12)
        return sqrt(a.indices.sumOf { i ->
            val difference = a[i] / ar - b[i] / br
            difference * difference
        } / a.size)
    }

    private data class Spectrum(val bands: DoubleArray, val highFraction: Double)

    /** Broad logarithmic bands compare noisy directions without assuming harmonic carriers. */
    private fun spectrum(samples: FloatArray, from: Double = .12, seconds: Double = .45): Spectrum {
        val first = (from * Dsp.RATE).roundToInt()
        val count = minOf((seconds * Dsp.RATE).roundToInt(), samples.size - first)
        require(count > 1024)
        var size = 1
        while (size < count) size *= 2
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val bands = DoubleArray(32)
        var total = 0.0
        var high = 0.0
        for (bin in 1 until size / 2) {
            val hz = bin * Dsp.RATE.toDouble() / size
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (hz > 18_000.0) high += power
            if (hz >= 30.0 && hz < 18_000.0) {
                val band = (ln(hz / 30.0) / ln(18_000.0 / 30.0) * bands.size).toInt()
                bands[band] += power
            }
        }
        val mass = bands.sum()
        require(mass > 1e-15)
        return Spectrum(DoubleArray(bands.size) { bands[it] / mass }, high / total)
    }

    private fun spectralDistance(a: Spectrum, b: Spectrum) = a.bands.indices.sumOf { abs(a.bands[it] - b.bands[it]) }

    /** Twenty millisecond RMS variation measures packet activity after attack and before release. */
    private fun temporalVariation(samples: FloatArray): Double {
        val width = (Dsp.RATE * .02).roundToInt()
        val first = (Dsp.RATE * .25).roundToInt()
        val until = (Dsp.RATE * 1.15).roundToInt().coerceAtMost(samples.size)
        val values = (first until until - width step width).map { rms(samples, it, it + width) }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) / mean
    }

    @Test
    fun `stopped studies reproduce audio exactly while saved alignment remains illuminated`() {
        for (direction in FacetLightDirection.entries) {
            val macros = mapOf("REVOLVE" to 0f)
            val a = probe(direction, macros)
            val replay = probe(direction, macros)
            assertContentEquals(a.raw, replay.raw, "$direction raw replay")
            assertContentEquals(a.snip.samples, replay.snip.samples, "$direction native replay")
            assertEquals(a.trace, replay.trace)
            assertEquals(0f, a.diagnostics.rotorHz)
            assertEquals(.1875, a.diagnostics.startPhase)
            assertEquals(.1875, a.diagnostics.endPhase)
            assertTrue(a.trace.isNotEmpty())
            assertTrue(a.trace.all { it.phase == .1875 }, "$direction stopped phase drifted")
            assertTrue(a.trace.all { it.routeEnergy == a.trace.first().routeEnergy }, "$direction stopped geometry drifted")
            val body = rms(a.raw, (Dsp.RATE * .15).roundToInt(), (Dsp.RATE * .45).roundToInt())
            assertTrue(body > 1e-7, "$direction stopped the light source with the rotor")
            val changed = probe(direction, macros, alignment = .4375f)
            val contrast = spectralDistance(spectrum(a.raw), spectrum(changed.raw))
            println("FACET LIGHT stopped $direction bodyRms=$body angleSpectralDistance=$contrast")
            assertTrue(contrast > .02, "$direction saved alignments have insufficient spectral contrast")
        }
    }

    @Test
    fun `study corners stay finite and unsupported processing switches fail explicitly`() {
        for (direction in FacetLightDirection.entries) for (midi in listOf(24, 84)) {
            val p = probe(direction, controls.associateWith { 1f }, voice = FacetVoice.SHIMMER,
                midi = midi, velocity = 1f)
            assertTrue(p.raw.isNotEmpty() && p.snip.samples.isNotEmpty())
            assertTrue(p.raw.all { it.isFinite() } && p.snip.samples.all { it.isFinite() })
            val peak = p.raw.maxOf { abs(it) }
            println("FACET LIGHT bounds $direction MIDI$midi rawPeak=$peak statePeak=${p.diagnostics.statePeak}")
            assertTrue(peak < .25f, "$direction MIDI$midi exceeds raw headroom")
            assertTrue(p.diagnostics.statePeak.isFinite() && p.diagnostics.statePeak < .25f)
            assertTrue(p.snip.samples.maxOf { abs(it) } <= .9901f)
            assertTrue(rms(p.raw) > 1e-7, "$direction MIDI$midi has no body")
        }
        for (options in listOf(FacetOptions(seconds = .5f, resonance = false),
            FacetOptions(seconds = .5f, commonGainRouting = true),
            FacetOptions(seconds = .5f, sourceMotion = false))) {
            assertFailsWith<IllegalArgumentException> {
                FacetLightStudy.probe(FacetVoice.BLOOM, FacetLightDirection.GLASS_GLINTS, options = options)
            }
        }
        val silent = probe(FacetLightDirection.AIRY_WASH, velocity = 0f)
        assertTrue(silent.raw.all { it == 0f } && silent.snip.samples.all { it == 0f })
    }

    @Test
    fun `glass and air produce distinct moving spectra with packet activity`() {
        val macros = mapOf("REVOLVE" to .6f, "FACETS" to .5f)
        val glass = probe(FacetLightDirection.GLASS_GLINTS, macros, seconds = 2f)
        val air = probe(FacetLightDirection.AIRY_WASH, macros, seconds = 2f)
        val contrast = spectralDistance(spectrum(glass.raw, .25, .9), spectrum(air.raw, .25, .9))
        val glassVariation = temporalVariation(glass.raw)
        val airVariation = temporalVariation(air.raw)
        println("FACET LIGHT direction spectralDistance=$contrast glassPacketVariation=$glassVariation airPacketVariation=$airVariation")
        assertTrue(contrast > .2, "the two directions have insufficient spectral contrast")
        assertTrue(glassVariation > .04 && airVariation > .04, "the packet sources have insufficient temporal activity")
    }

    @Test
    fun `five coarse macro changes remain active in both directions`() {
        val baseline = controls.associateWith { 0f }
        for (direction in FacetLightDirection.entries) {
            val a = probe(direction, baseline)
            val aSpectrum = spectrum(a.raw)
            for (control in controls) {
                val b = probe(direction, baseline + (control to 1f))
                val wave = normalizedDistance(a.raw, b.raw)
                val spectral = spectralDistance(aSpectrum, spectrum(b.raw))
                val level = abs(ln(rms(b.raw) / rms(a.raw)))
                println("FACET LIGHT control $direction $control waveform=$wave spectral=$spectral logRmsRatio=$level")
                assertTrue(maxOf(wave, spectral, level) > .04, "$direction $control has insufficient audio activity")
            }
        }
    }

    @Test
    fun `bright fast high register spectra agree with eight times reference edge power`() {
        for (direction in FacetLightDirection.entries) {
            val macros = controls.associateWith { 1f }
            val four = probe(direction, macros, voice = FacetVoice.SHIMMER, midi = 84, seconds = 2f,
                velocity = 1f, oversample = 4)
            val reference = probe(direction, macros, voice = FacetVoice.SHIMMER, midi = 84, seconds = 2f,
                velocity = 1f, oversample = 8)
            val a = spectrum(four.raw, .25, 1.0)
            val b = spectrum(reference.raw, .25, 1.0)
            val contrast = spectralDistance(a, b)
            val error = sqrt(four.raw.indices.sumOf { i ->
                val difference = four.raw[i] - reference.raw[i].toDouble()
                difference * difference
            } / four.raw.size) / rms(reference.raw)
            println("FACET LIGHT 4x8x $direction relativeRmsError=$error spectralDistance=$contrast above18k=${a.highFraction} referenceAbove18k=${b.highFraction}")
            assertTrue(a.highFraction < .0012 && a.highFraction <= b.highFraction * 1.2 + 1e-7,
                "$direction has excess edge power relative to its 8x reference")
            assertTrue(contrast < .02, "$direction broad spectral balance diverges from its 8x reference")
            assertTrue(error < .06, "$direction waveform diverges from its shared-noise 8x reference")
        }
    }
}
