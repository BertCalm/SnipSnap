package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Short dry-engine probes check geometry, control activity and pitch before shared leveling. */
class FacetTest {
    private val timbral = listOf("ILLUMINATE", "ELEVATION", "CHROMA", "REVOLVE", "FACETS")

    private fun probe(voice: FacetVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 48,
        alignment: Float = .1875f, seconds: Float = .75f, velocity: Float = .8f) = Facet.probe(voice, macros,
        midi = midi, velocity = velocity, seed = 8191, initialAlignment = alignment,
        options = FacetOptions(seconds = seconds))

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** Gain-independent waveform distance also detects evolving phase and temporal detail. */
    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val aRms = rms(a)
        val bRms = rms(b)
        require(aRms > 1e-9 && bRms > 1e-9)
        return sqrt(a.indices.sumOf { i ->
            val difference = a[i] / aRms - b[i] / bRms
            difference * difference
        } / a.size)
    }

    /** Broad harmonic-band power makes timbre comparisons independent of render level. */
    private fun spectralMass(samples: FloatArray, midi: Int = 48): DoubleArray {
        val first = (Dsp.RATE * .1).roundToInt()
        val count = minOf((Dsp.RATE * .5).roundToInt(), samples.size - first)
        var size = 1
        while (size < count) size *= 2
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val root = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val power = DoubleArray(32)
        for (bin in 1 until size / 2) {
            val harmonic = (bin * Dsp.RATE.toDouble() / size / root).roundToInt() - 1
            if (harmonic in power.indices) power[harmonic] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
        }
        val sum = power.sum()
        require(sum > 1e-15)
        return DoubleArray(power.size) { power[it] / sum }
    }

    private fun spectralDistance(a: FloatArray, b: FloatArray): Double {
        val aMass = spectralMass(a)
        val bMass = spectralMass(b)
        return aMass.indices.sumOf { abs(aMass[it] - bMass[it]) }
    }

    private fun highFrequencyFraction(samples: FloatArray, aboveHz: Double): Double {
        val first = (Dsp.RATE * .1).roundToInt()
        val count = (Dsp.RATE * .5).roundToInt()
        var size = 1
        while (size < count) size *= 2
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        var total = 0.0
        var high = 0.0
        for (bin in 1 until size / 2) {
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (bin * Dsp.RATE.toDouble() / size > aboveHz) high += power
        }
        return high / total
    }

    @Test
    fun `identical finite recipes reproduce raw and conditioned samples exactly`() {
        for (voice in FacetVoice.entries) {
            val macros = mapOf("CHROMA" to .67f, "FACETS" to .43f, "REVOLVE" to .38f)
            val a = probe(voice, macros, seconds = .5f)
            val b = probe(voice, macros, seconds = .5f)
            assertContentEquals(a.raw, b.raw, "$voice raw replay")
            assertContentEquals(a.snip.samples, b.snip.samples, "$voice conditioned replay")
        }
        for (hold in listOf(.99f, 1f)) {
            val failure = assertFailsWith<IllegalArgumentException> {
                probe(FacetVoice.BLOOM, mapOf("HOLD" to hold), seconds = .5f)
            }
            assertTrue(failure.message.orEmpty().contains("finite prototype"))
        }
        for (macros in listOf(mapOf("TUNE" to .5f), mapOf("CHROMA" to -.01f),
            mapOf("CHROMA" to 1.01f), mapOf("CHROMA" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { probe(FacetVoice.BLOOM, macros, seconds = .5f) }
        }
        assertFailsWith<IllegalArgumentException> {
            Facet.probe(FacetVoice.BLOOM, beams = listOf(FacetBeam(0)), options = FacetOptions(seconds = .5f))
        }
        assertFailsWith<IllegalArgumentException> {
            Facet.probe(FacetVoice.BLOOM, beams = listOf(FacetBeam(0), FacetBeam(0)), beamMode = "OVERRIDE",
                options = FacetOptions(seconds = .5f))
        }
    }

    @Test
    fun `stopped rotation retains the saved phase and different alignments change timbre`() {
        for (voice in FacetVoice.entries) {
            val macros = mapOf("REVOLVE" to 0f, "FACETS" to .45f)
            val a = probe(voice, macros, alignment = .125f)
            val b = probe(voice, macros, alignment = .375f)
            for ((alignment, p) in listOf(.125 to a, .375 to b)) {
                assertEquals(0f, p.diagnostics.rotorHz, "$voice stopped rotor speed")
                assertEquals(alignment, p.diagnostics.startPhase)
                assertEquals(alignment, p.diagnostics.endPhase)
                assertTrue(p.trace.isNotEmpty())
                assertTrue(p.trace.all { it.phase == alignment }, "$voice stopped geometry drifted")
                assertTrue(p.trace.all { it.routeEnergy == p.trace.first().routeEnergy }, "$voice stopped routing drifted")
            }
            val wave = normalizedDistance(a.raw, b.raw)
            val spectrum = spectralDistance(a.raw, b.raw)
            println("FACET stopped $voice: waveform=$wave spectral=$spectrum")
            assertTrue(wave > .05 && spectrum > .03, "$voice saved angle has insufficient spectral contrast")
        }
    }

    @Test
    fun `adjacent density values remain continuous while coarse and fine layouts differ`() {
        for (voice in FacetVoice.entries) {
            val macros = mapOf("REVOLVE" to .5f)
            val low = probe(voice, macros + ("FACETS" to 0f), seconds = .5f)
            val high = probe(voice, macros + ("FACETS" to 1f), seconds = .5f)
            val full = normalizedDistance(low.raw, high.raw)
            // This short-window bound catches loss of density activity; the longer
            // coarse/fine audition establishes the moving reflection's musical contrast.
            assertTrue(full > .03, "$voice density range lost its timbral activity: $full")
            for (boundary in listOf(.25f, .5f, .75f)) {
                val a = probe(voice, macros + ("FACETS" to boundary - .0001f), seconds = .5f)
                val b = probe(voice, macros + ("FACETS" to boundary + .0001f), seconds = .5f)
                val adjacent = normalizedDistance(a.raw, b.raw)
                println("FACET density $voice boundary=$boundary adjacent=$adjacent full=$full")
                assertTrue(adjacent < .001 && adjacent < full * .01,
                    "$voice local density change is discontinuous: $adjacent versus full range $full")
            }
        }
    }

    @Test
    fun `beam mute is exact silence while illuminate zero remains a usable source`() {
        for (voice in FacetVoice.entries) {
            val muted = Facet.probe(voice, beams = (0..11).map { FacetBeam(it, intensity = 0f) },
                beamMode = "OVERRIDE", options = FacetOptions(seconds = .5f))
            assertTrue(muted.raw.all { it == 0f }, "$voice muted raw has residual excitation")
            assertTrue(muted.snip.samples.all { it == 0f }, "$voice muted output has residual support")
            val quiet = probe(voice, mapOf("ILLUMINATE" to 0f), seconds = .5f)
            assertTrue(rms(quiet.raw) > 1e-7, "$voice illuminate zero must preserve a source")
        }
    }

    @Test
    fun `raw output and internal states stay finite and bounded at register and control extremes`() {
        for (voice in FacetVoice.entries) for ((midi, value) in listOf(24 to 0f, 48 to .5f, 84 to 1f)) {
            val p = probe(voice, timbral.associateWith { value }, midi = midi, seconds = .5f, velocity = 1f)
            assertTrue(p.raw.isNotEmpty() && p.snip.samples.isNotEmpty())
            assertTrue(p.raw.all { it.isFinite() }, "$voice MIDI$midi nonfinite raw")
            assertTrue(p.snip.samples.all { it.isFinite() }, "$voice MIDI$midi nonfinite output")
            val rawPeak = p.raw.maxOf { abs(it) }
            assertTrue(rawPeak < 1f, "$voice MIDI$midi excessive raw peak $rawPeak")
            assertTrue(p.snip.samples.maxOf { abs(it) } <= .9901f, "$voice MIDI$midi export clips")
            assertEquals(rawPeak, p.diagnostics.rawPeak, "$voice raw diagnostic measures the actual prelevel buffer")
            assertTrue(p.diagnostics.statePeak.isFinite() && p.diagnostics.statePeak < .35f,
                "$voice MIDI$midi excessive resonator state ${p.diagnostics.statePeak}")
            assertTrue(rms(p.raw) > 1e-7, "$voice MIDI$midi silent body")
            println("FACET bounds $voice MIDI$midi rawPeak=$rawPeak statePeak=${p.diagnostics.statePeak}")
        }
    }

    @Test
    fun `all five macros change the dry sound in both low to middle intervals`() {
        for (voice in FacetVoice.entries) for (macro in timbral) {
            val values = listOf(0f, .25f, .5f).map { value -> probe(voice, mapOf(macro to value)).raw }
            for (index in 0..1) {
                val wave = normalizedDistance(values[index], values[index + 1])
                val spectrum = spectralDistance(values[index], values[index + 1])
                println("FACET control $voice $macro ${index * .25}->${(index + 1) * .25}: waveform=$wave spectral=$spectrum")
                assertTrue(wave > .008 && spectrum > .005,
                    "$voice $macro has insufficient low-middle activity: waveform=$wave spectrum=$spectrum")
            }
        }
    }

    @Test
    fun `both voices retain a confident requested fundamental across the pitched register`() {
        for (voice in FacetVoice.entries) for (midi in listOf(36, 60, 84)) {
            val p = probe(voice, midi = midi, seconds = 1f)
            val detected = assertNotNull(Pitch.detect(Snip(p.snip.samples, channels = 1, sampleRate = Dsp.RATE),
                fromSec = .2f, windowSec = .35f), "$voice MIDI$midi has no measured note")
            val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val cents = 1200 * ln(detected.hz / expected) / ln(2.0)
            println("FACET pitch $voice MIDI$midi cents=$cents confidence=${detected.confidence}")
            assertTrue(abs(cents) < 10, "$voice MIDI$midi fundamental is $cents cents away")
            assertTrue(detected.confidence > .9f, "$voice MIDI$midi periodicity confidence ${detected.confidence}")
        }
    }

    @Test
    fun `bright fast high notes agree with an eight times reference without excess high frequency edge power`() {
        for (voice in FacetVoice.entries) {
            val macros = timbral.associateWith { 1f }
            fun render(oversample: Int) = Facet.probe(voice, macros, midi = 84, velocity = 1f,
                seed = 8191, initialAlignment = .1875f,
                options = FacetOptions(seconds = 1f, oversample = oversample)).raw
            val four = render(4)
            val reference = render(8)
            assertEquals(reference.size, four.size)
            val error = sqrt(four.indices.sumOf { i ->
                val difference = four[i] - reference[i].toDouble()
                difference * difference
            } / four.size) / rms(reference)
            val fourHigh = highFrequencyFraction(four, 18_000.0)
            val referenceHigh = highFrequencyFraction(reference, 18_000.0)
            println("FACET 4x8x $voice relativeRmsError=$error above18k=$fourHigh referenceAbove18k=$referenceHigh")
            assertTrue(error < .0002, "$voice 4x differs from its 8x reference by $error of reference RMS")
            assertTrue(fourHigh < 5e-8 && fourHigh <= referenceHigh * 1.1,
                "$voice has excess high-frequency power versus 8x: $fourHigh versus $referenceHigh")
        }
    }
}
