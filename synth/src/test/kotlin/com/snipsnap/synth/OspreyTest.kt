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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audio measurements complement the diagnostic taps; a changed macro map alone cannot pass. */
class OspreyTest {
    private val dry = mapOf("DEPTH" to 0f, "SPACE" to 0f, "HOLD" to 0f)

    private fun probe(macros: Map<String, Float> = dry, midi: Int = 60,
        seed: Int = 871, seconds: Float = 6f): Osprey.Probe =
        Osprey.probe(OspreyVoice.SWELL, macros, midi = midi, seed = seed, durationSeconds = seconds)

    private fun channel(stereo: FloatArray, channel: Int = 0): FloatArray =
        FloatArray(stereo.size / 2) { stereo[it * 2 + channel] }

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    /** Residual after best scalar fit removes output gain from causal comparisons. */
    private fun shapeError(reference: FloatArray, candidate: FloatArray): Double {
        assertEquals(reference.size, candidate.size)
        var cross = 0.0
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        for (i in reference.indices) {
            cross += reference[i].toDouble() * candidate[i]
            referenceEnergy += reference[i].toDouble() * reference[i]
            candidateEnergy += candidate[i].toDouble() * candidate[i]
        }
        val scale = cross / referenceEnergy.coerceAtLeast(1e-30)
        var error = 0.0
        for (i in reference.indices) {
            val delta = candidate[i] - reference[i] * scale
            error += delta * delta
        }
        return sqrt(error / candidateEnergy.coerceAtLeast(1e-30))
    }

    private data class Spectrum(val power: DoubleArray, val binHz: Double)

    /** 1.49 seconds of body, Hann window, zero padding for interpolated peak measurement. */
    private fun spectrum(stereo: FloatArray, startSeconds: Double = .35): Spectrum {
        val samples = channel(stereo)
        val first = (startSeconds * Dsp.RATE).roundToInt()
        val count = minOf(65_536, samples.size - first)
        require(count >= 8_192) { "spectral measurement needs a body window" }
        val n = 131_072
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        return Spectrum(DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] },
            Dsp.RATE.toDouble() / n)
    }

    private fun peakNear(spectrum: Spectrum, target: Double, tolerance: Double = .08): Double {
        val low = ((target * (1 - tolerance)) / spectrum.binHz).toInt().coerceAtLeast(1)
        val high = ((target * (1 + tolerance)) / spectrum.binHz).toInt().coerceAtMost(spectrum.power.lastIndex - 1)
        val best = (low..high).maxBy { spectrum.power[it] }
        val a = ln(spectrum.power[best - 1].coerceAtLeast(1e-30))
        val b = ln(spectrum.power[best].coerceAtLeast(1e-30))
        val c = ln(spectrum.power[best + 1].coerceAtLeast(1e-30))
        val offset = (.5 * (a - c) / (a - 2 * b + c)).coerceIn(-.5, .5)
        return (best + offset) * spectrum.binHz
    }

    /** Quarter-octave power bins compare timbre independently of final loudness. */
    private fun spectralMass(samples: FloatArray): DoubleArray {
        val spectrum = spectrum(samples)
        val mass = DoubleArray(40)
        for (k in 1 until spectrum.power.size) {
            val hz = k * spectrum.binHz
            if (hz < 30 || hz > 18_000) continue
            val band = (ln(hz / 30) / ln(2.0) * 4).toInt().coerceIn(mass.indices)
            mass[band] += spectrum.power[k]
        }
        val total = mass.sum()
        assertTrue(total > 1e-12, "spectral comparison needs audible audio")
        return DoubleArray(mass.size) { mass[it] / total }
    }

    private fun highFrequencyFraction(samples: FloatArray, aboveHz: Double): Double {
        val spectrum = spectrum(samples)
        var high = 0.0
        var total = 0.0
        for (k in 1 until spectrum.power.size) {
            total += spectrum.power[k]
            if (k * spectrum.binHz > aboveHz) high += spectrum.power[k]
        }
        return high / total.coerceAtLeast(1e-30)
    }

    private fun temporalMass(samples: FloatArray): DoubleArray {
        val mass = DoubleArray(40)
        for (i in samples.indices) mass[minOf(mass.lastIndex, i * mass.size / samples.size)] +=
            samples[i].toDouble() * samples[i]
        val total = mass.sum()
        assertTrue(total > 1e-12, "envelope comparison needs audible audio")
        return DoubleArray(mass.size) { mass[it] / total }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]) }

    @Test
    fun `same complete recipe repeats samples taps events and metrics exactly`() {
        val macros = mapOf("SHORE" to .37f, "SEA" to .73f, "MOTION" to .61f,
            "DEPTH" to .8f, "SPACE" to .55f, "HOLD" to .2f)
        val a = probe(macros)
        // Another render must not advance shared random streams or leave modal/reverb state behind.
        probe(macros + ("SEA" to .1f), seed = -741, seconds = 2f)
        val b = probe(macros)
        for ((first, second) in listOf(a.samples to b.samples, a.ocean to b.ocean,
            a.primary to b.primary, a.sympathy to b.sympathy, a.creatures to b.creatures, a.dry to b.dry)) {
            assertContentEquals(first, second)
        }
        assertEquals(a.callEvents, b.callEvents)
        assertEquals(a.metrics, b.metrics)
        assertTrue(a.callEvents.isNotEmpty(), "the repeatability test must exercise creature scheduling")
    }

    @Test
    fun `different seeds change stochastic sound and call schedules`() {
        val macros = dry + mapOf("SEA" to .9f, "MOTION" to .85f, "DEPTH" to 1f)
        val a = probe(macros, seed = 101)
        val b = probe(macros, seed = 102)
        assertTrue(shapeError(a.samples, b.samples) > .1, "different seeds only changed output gain")
        assertFalse(a.callEvents == b.callEvents, "different seeds retained the same call schedule")
    }

    @Test
    fun `output format is stereo 44100 with the requested finite duration`() {
        for (seconds in listOf(.25f, 2.5f, 8f)) {
            val snip = Osprey.render(OspreyVoice.SWELL, dry, durationSeconds = seconds)
            assertEquals(2, snip.channels)
            assertEquals(44_100, snip.sampleRate)
            assertEquals((seconds * snip.sampleRate).roundToInt(), snip.frameCount)
        }
    }

    @Test
    fun `HOLD extends a finite excitation and zero velocity remains silent`() {
        val finite = probe(dry, seconds = 6f)
        val extended = probe(dry + ("HOLD" to 1f), seconds = 6f)
        assertTrue(extended.metrics.excitationStopSeconds > finite.metrics.excitationStopSeconds,
            "HOLD did not extend the source gesture")
        assertTrue(extended.metrics.excitationStopSeconds < 6f, "HOLD excitation never stopped")
        assertEquals(finite.samples.size, extended.samples.size)
        val silent = Osprey.render(OspreyVoice.SWELL, mapOf("DEPTH" to 1f, "SPACE" to 1f),
            velocity = 0f, durationSeconds = 2f)
        assertTrue(silent.samples.all { it == 0f }, "zero excitation velocity retained audio")
    }

    @Test
    fun `SPACE zero is exactly dry and dry contains only the three audible layers`() {
        val p = probe(dry + mapOf("DEPTH" to .8f, "SEA" to .7f))
        assertContentEquals(p.dry, p.samples)
        var largestError = 0.0
        for (i in p.dry.indices) largestError = maxOf(largestError,
            abs(p.dry[i] - (p.primary[i].toDouble() + p.sympathy[i] + p.creatures[i])))
        assertTrue(largestError < 1e-6, "dry taps do not reconstruct the mix: $largestError")
        assertTrue(rms(p.ocean) > 1e-6, "ocean diagnostic tap was empty")
        assertTrue(rms(p.sympathy) > 1e-6, "sympathetic network never contributed")
    }

    @Test
    fun `DEPTH zero mutes calls without consuming the ocean random stream differently`() {
        val macros = dry + mapOf("SEA" to .8f, "MOTION" to .7f)
        val silent = probe(macros)
        val calls = probe(macros + ("DEPTH" to 1f))
        assertTrue(silent.creatures.all { it == 0f }, "DEPTH zero left audible creatures")
        assertTrue(rms(calls.creatures) > 1e-5, "DEPTH one did not produce calls")
        assertTrue(shapeError(silent.ocean, calls.ocean) < 1e-6, "adding calls changed the fluid source")
        assertContentEquals(silent.modeHz, calls.modeHz)
        val surfaceHigh = highFrequencyFraction(silent.primary, 1_000.0)
        val submergedHigh = highFrequencyFraction(calls.primary, 1_000.0)
        assertTrue(submergedHigh < surfaceHigh * .85,
            "DEPTH did not darken upper body radiation independently of gain: $surfaceHigh -> $submergedHigh")
    }

    @Test
    fun `continuous shore has no sound discontinuity at the interior region boundaries`() {
        for (boundary in listOf(.25f, .5f, .75f)) {
            val low = probe(dry + mapOf("SHORE" to boundary - .00001f, "SEA" to .45f), seconds = 3f)
            val high = probe(dry + mapOf("SHORE" to boundary + .00001f, "SEA" to .45f), seconds = 3f)
            val error = shapeError(low.primary, high.primary)
            assertTrue(error < .025, "SHORE jumps at $boundary: relative residual $error")
        }
        // One scheduled scene also covers the interpolated creature fields. Event thresholds
        // are discrete, so this checks a representative active scene, not every possible seed.
        val scene = dry + mapOf("SEA" to .65f, "MOTION" to .6f, "DEPTH" to .7f)
        val low = probe(scene + ("SHORE" to .5f - .00001f))
        val high = probe(scene + ("SHORE" to .5f + .00001f))
        assertTrue(low.callEvents.isNotEmpty() && rms(low.creatures) > 1e-5,
            "whole-scene continuity needs audible calls")
        val sceneError = shapeError(low.samples, high.samples)
        println("OSPREY shore boundary scene residual=$sceneError; low calls=${low.callEvents}; high calls=${high.callEvents}")
        assertTrue(sceneError < .025,
            "interpolated creatures create an audible jump at the representative shore boundary: residual=$sceneError")
    }

    @Test
    fun `root is audible and accurately tuned at MIDI 48 60 and 72 across all shores`() {
        for ((midi, target) in listOf(48 to 130.81278265, 60 to 261.62556530, 72 to 523.25113060)) {
            for (shore in listOf(0f, .25f, .5f, .75f, 1f)) {
                val p = probe(dry + mapOf("SHORE" to shore, "SEA" to 0f, "MOTION" to 0f), midi, seconds = 3f)
                val measured = peakNear(spectrum(p.primary), target)
                val cents = 1200 * ln(measured / target) / ln(2.0)
                assertTrue(abs(cents) < 15, "MIDI $midi shore $shore: root $measured Hz is $cents cents out")
                assertTrue(abs(p.rootHz - target) / target < .0001, "wrong root diagnostic at MIDI $midi")
                for (rootIndex in listOf(0, 4, 8)) {
                    assertTrue(abs(p.modeHz[rootIndex] - target) / target < .0001,
                        "instrument slot $rootIndex does not anchor the played note")
                }
            }
        }
    }

    @Test
    fun `modal frequencies transpose by octaves with the played root`() {
        val macros = dry + mapOf("SHORE" to .42f, "SEA" to .2f)
        val low = probe(macros, midi = 48, seconds = 2f)
        val middle = probe(macros, midi = 60, seconds = 2f)
        val high = probe(macros, midi = 72, seconds = 2f)
        assertEquals(16, middle.modeHz.size)
        for (i in middle.modeHz.indices) {
            assertTrue(abs(middle.modeHz[i] / low.modeHz[i] - 2f) < .0002f, "mode $i did not transpose 48 to 60")
            assertTrue(abs(high.modeHz[i] / middle.modeHz[i] - 2f) < .0002f, "mode $i did not transpose 60 to 72")
        }
        // An upper mode must be present in the audio too, rather than only in a coefficient report.
        val target = middle.modeHz[1].toDouble()
        val measured = peakNear(spectrum(middle.primary), target, tolerance = .025)
        assertTrue(abs(measured - target) / target < .015, "second mode absent or mistuned: $target -> $measured")
    }

    @Test
    fun `SEA changes audible timbre and MOTION changes the audible contour independently of gain`() {
        val calm = probe(dry + mapOf("SEA" to 0f, "MOTION" to .3f))
        val storm = probe(dry + mapOf("SEA" to 1f, "MOTION" to .3f))
        val spectralChange = distance(spectralMass(calm.samples), spectralMass(storm.samples))
        assertTrue(spectralChange > .04, "SEA moved less than 2 percent of spectral power: $spectralChange")
        val still = probe(dry + mapOf("SEA" to .5f, "MOTION" to 0f))
        val moving = probe(dry + mapOf("SEA" to .5f, "MOTION" to 1f))
        val contourChange = distance(temporalMass(still.samples), temporalMass(moving.samples))
        assertTrue(contourChange > .06, "MOTION moved less than 3 percent of temporal power: $contourChange")
    }

    @Test
    fun `neighboring pure shores produce distinct audible resonator spectra`() {
        val coasts = listOf(0f, .25f, .5f, .75f, 1f).map {
            probe(dry + mapOf("SHORE" to it, "SEA" to .35f), seconds = 3f)
        }
        val spectra = coasts.map { spectralMass(it.primary) }
        val differences = (0 until spectra.lastIndex).map { distance(spectra[it], spectra[it + 1]) }
        val upperFractions = coasts.map { highFrequencyFraction(it.primary, it.rootHz * 1.5) }
        println("OSPREY neighboring shore spectral L1=$differences; upper-band power fractions=$upperFractions")
        for (i in 0 until spectra.lastIndex) {
            val difference = differences[i]
            assertTrue(difference > .04, "neighboring shore $i/${i + 1} moved less than 2 percent of power: $difference")
        }
    }

    @Test
    fun `finite excitation stops and modal energy decays without feedback recovery`() {
        for (shore in listOf(0f, .25f, .5f, .75f, 1f)) {
            val p = probe(dry + mapOf("SHORE" to shore, "SEA" to 1f, "MOTION" to 1f), seconds = 8f)
            assertEquals(0, p.metrics.nonFiniteRecoveries, "shore $shore required a DSP recovery")
            assertTrue(p.metrics.peakModalEnergy > 1e-10, "decay test never excited the bank")
            assertTrue(p.metrics.finalModalEnergy < p.metrics.peakModalEnergy * .01,
                "shore $shore modal energy did not decay: ${p.metrics}")
            assertTrue(p.metrics.excitationStopSeconds < 7f, "gesture never stopped before the tail")
            val left = channel(p.dry)
            val lastSecond = rms(left, left.size - Dsp.RATE, left.size)
            val body = rms(left, Dsp.RATE / 2, Dsp.RATE * 2)
            assertTrue(lastSecond < body * .15, "shore $shore audible tail did not fall: $body -> $lastSecond")
        }
    }

    @Test
    fun `macro corners and register extremes stay finite bounded and free from material DC`() {
        val controls = listOf("SHORE", "SEA", "MOTION", "DEPTH", "SPACE", "HOLD")
        val settings = listOf(emptyMap(), controls.associateWith { 0f }, controls.associateWith { 1f },
            mapOf("SHORE" to .51f, "SEA" to 1f, "MOTION" to 0f, "DEPTH" to 1f, "SPACE" to 1f, "HOLD" to 0f))
        for (midi in listOf(36, 60, 84)) for (macros in settings) {
            val p = probe(macros, midi, seconds = 4f)
            assertEquals(0, p.metrics.nonFiniteRecoveries, "MIDI $midi $macros required recovery")
            assertTrue(p.metrics.rawPeak.isFinite(), "raw peak was non-finite")
            assertTrue(p.samples.all { it.isFinite() && it in -1f..1f }, "MIDI $midi $macros escaped output bounds")
            assertTrue(rms(p.samples) > 1e-4, "MIDI $midi $macros was effectively silent")
            for (c in 0..1) {
                val samples = channel(p.samples, c)
                val dc = abs(samples.average())
                assertTrue(dc < .003 && dc < rms(samples) * .03,
                    "MIDI $midi $macros channel $c DC $dc is material")
            }
        }
    }
}
